package com.supertokens.session;

import java.io.File;
import java.net.URLClassLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionLifecycleTest extends SessionTestFixture {
    @Test public void refreshRetainsCorrelationAndHydrationRetainsDurableIdentity() throws Exception {
        SessionCommitResult installed = SuperTokens.installSession(context,
                SuperTokens.getSessionSnapshot(context).getOwner(), tokens("A", "initial"), "host-adoption-42");
        assertTrue(installed.isSuccess());
        SessionSnapshot a = installed.getSnapshot();
        SessionTokens rotated = tokens("A", "rotated");
        server.enqueue(rotation(rotated));
        SessionResolution resolution = SuperTokens.resolveAccessToken(context, a.getOwner(), true, null);
        assertTrue(resolution.isSuccess());
        SessionSnapshot refreshed = resolution.getSnapshot();
        assertSame(a.getOwner(), refreshed.getOwner());
        assertEquals(a.getAdoptionId(), refreshed.getAdoptionId());
        assertEquals("host-adoption-42", refreshed.getAdoptionCorrelation());
        assertEquals("Bearer " + a.getTokens().getRefreshToken(), server.takeRequest(1, TimeUnit.SECONDS).getHeader("Authorization"));
        SuperTokens.resetForTests();
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header").build();
        SessionSnapshot hydrated = SuperTokens.getSessionSnapshot(context);
        assertNotSame(refreshed.getOwner(), hydrated.getOwner());
        assertEquals(refreshed.getRevision(), hydrated.getRevision());
        assertEquals(refreshed.getAdoptionId(), hydrated.getAdoptionId());
        assertEquals("host-adoption-42", hydrated.getAdoptionCorrelation());
        assertEquals(rotated.getRefreshToken(), hydrated.getTokens().getRefreshToken());
        assertEquals(rotated.getAccessToken(), SuperTokens.getAccessToken(context));
    }

    @Test public void explicitIdenticalInstallChangesOwnerAndDurableAdoption() throws Exception {
        SessionSnapshot a = install("A", "same");
        SessionCommitResult result = SuperTokens.installSession(context, a.getOwner(), a.getTokens(), "prospective-adoption");
        assertTrue(result.isSuccess());
        SessionSnapshot b = result.getSnapshot();
        assertNotSame(a.getOwner(), b.getOwner());
        assertFalse(a.isCurrent());
        assertTrue(b.isCurrent());
        assertNotEquals(a.getAdoptionId(), b.getAdoptionId());
        assertEquals("prospective-adoption", b.getAdoptionCorrelation());
        assertEquals("primary-user", b.getUserId());
        assertEquals("public", b.getTenantId());
        assertEquals("A", b.getSessionHandle());
        assertEquals(SessionCommitResult.Status.STALE_OWNER, SuperTokens.clearSession(context, a.getOwner()).getStatus());
        b.getAccessTokenPayload().put("claim", "tampered");
        assertEquals("same", SuperTokens.getSessionSnapshot(context).getAccessTokenPayload().getString("claim"));
        SessionSnapshot absent = SuperTokens.clearSession(context, b.getOwner()).getSnapshot();
        assertEquals(SessionSnapshot.Status.ABSENT, absent.getStatus());
        assertNull(absent.getTokens());
        assertNull(absent.getAdoptionCorrelation());
        assertNotEquals(b.getAdoptionId(), absent.getAdoptionId());
    }

    @Test public void normalizedRepeatBuildPreservesOwnerAndExplicitConfigChangeClears() throws Exception {
        SessionSnapshot a = install("A", "configured");
        new SuperTokens.Builder(context, server.url("/").toString()).apiBasePath("/auth/").tokenTransferMethod("header").build();
        assertSame(a.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
        try {
            new SuperTokens.Builder(context, server.url("/").toString()).apiBasePath("/other").tokenTransferMethod("header").build();
            fail("configuration replacement needs explicit reconfigure");
        } catch (IllegalStateException expected) { }
        assertSame(a.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
        SessionCommitResult result = new SuperTokens.Builder(context, server.url("/").toString())
                .apiBasePath("/other").tokenTransferMethod("header").reconfigure();
        assertTrue(result.isSuccess());
        assertEquals(SessionSnapshot.Status.ABSENT, result.getSnapshot().getStatus());
        assertNotEquals(a.getConfigurationId(), result.getSnapshot().getConfigurationId());
        assertNotSame(a.getOwner(), result.getSnapshot().getOwner());
    }

    @Test public void malformedImportCannotReplaceUsableSession() throws Exception {
        SessionSnapshot a = install("A", "valid");
        SessionTokens good = tokens("B", "candidate");
        SessionTokens[] malformed = {
                new SessionTokens("not-a-jwt", good.getRefreshToken(), good.getFrontToken()),
                new SessionTokens(good.getAccessToken(), "", good.getFrontToken()),
                new SessionTokens(good.getAccessToken(), good.getRefreshToken(), "not-base64"),
                new SessionTokens(good.getAccessToken(), good.getRefreshToken(), a.getTokens().getFrontToken())
        };
        for (SessionTokens candidate : malformed) {
            assertEquals(SessionCommitResult.Status.INVALID_SESSION,
                    SuperTokens.installSession(context, a.getOwner(), candidate).getStatus());
            assertSame(a.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            assertEquals(a.getRevision(), SuperTokens.getSessionSnapshot(context).getRevision());
        }
        for (String correlation : new String[]{" ", new String(new char[513]).replace('\0', 'x')}) {
            assertEquals(SessionCommitResult.Status.INVALID_SESSION,
                    SuperTokens.installSession(context, a.getOwner(), good, correlation).getStatus());
        }
    }

    @Test public void observerHandshakeExceptionAndReentrantCloseDoNotStrandDelivery() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<SessionSubscription> holder = new AtomicReference<>();
        CountDownLatch first = new CountDownLatch(1), closed = new CountDownLatch(1);
        SessionSubscription subscription = SuperTokens.observeSessions(context, snapshot -> {
            assertSame(snapshot.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            if (calls.incrementAndGet() == 1) {
                first.countDown();
                throw new IllegalStateException("host callback failure");
            }
            holder.get().close();
            closed.countDown();
        });
        holder.set(subscription);
        try {
            assertEquals(SessionSnapshot.Status.ABSENT, subscription.getInitialSnapshot().getStatus());
            install("A", "pending");
            assertEquals(0, calls.get());
            subscription.activate();
            subscription.activate();
            assertTrue(first.await(5, TimeUnit.SECONDS));
            install("B", "after-exception");
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            install("C", "after-close");
            assertEquals(2, calls.get());
        } finally { subscription.close(); }
    }

    @Test public void replacementRefreshProgressesWhileOldOwnerSocketIsHeld() throws Exception {
        SessionSnapshot a = install("A", "initial");
        SessionTokens rotatedA = tokens("A", "rotated"), rotatedB = tokens("B", "rotated");
        CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger refreshes = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                refreshes.incrementAndGet();
                if (("Bearer " + a.getTokens().getRefreshToken()).equals(request.getHeader("Authorization"))) {
                    arrived.countDown();
                    if (!release.await(8, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(504);
                    return rotation(rotatedA);
                }
                return rotation(rotatedB);
            }
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<SessionResolution> old = workers.submit(() -> SuperTokens.resolveAccessToken(context, a.getOwner(), true, null));
            assertTrue(arrived.await(5, TimeUnit.SECONDS));
            SessionSnapshot b = install("B", "initial");
            Future<SessionResolution> replacement = workers.submit(() -> SuperTokens.resolveAccessToken(context, b.getOwner(), true, null));
            SessionResolution refreshed = replacement.get(5, TimeUnit.SECONDS);
            assertTrue("B must finish before A is released", refreshed.isSuccess());
            assertEquals(rotatedB.getAccessToken(), refreshed.getAccessToken());
            release.countDown();
            assertEquals(SessionResolution.Status.STALE_OWNER, old.get(5, TimeUnit.SECONDS).getStatus());
            assertSame(b.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            assertEquals(rotatedB.getRefreshToken(), SuperTokens.getSessionSnapshot(context).getTokens().getRefreshToken());
            assertEquals(2, refreshes.get());
        } finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test public void mixedTransportsAndResolverShareRefreshLineage() throws Exception {
        SessionSnapshot a = install("A", "initial");
        SessionTokens rotated = tokens("A", "rotated");
        CountDownLatch protectedArrived = new CountDownLatch(3), refreshArrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1), resolverStarted = new CountDownLatch(1);
        AtomicInteger refreshes = new AtomicInteger(), retried = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (request.getPath().equals("/auth/session/refresh")) {
                    refreshes.incrementAndGet();
                    refreshArrived.countDown();
                    if (!release.await(8, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(504);
                    return rotation(rotated);
                }
                if (("Bearer " + rotated.getAccessToken()).equals(request.getHeader("Authorization"))) {
                    retried.incrementAndGet();
                    return new MockResponse().setBody("fresh");
                }
                protectedArrived.countDown();
                if (!protectedArrived.await(5, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(504);
                return new MockResponse().setResponseCode(401);
            }
        });
        okhttp3.OkHttpClient independent = new okhttp3.OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS)
                .addInterceptor(new SuperTokensInterceptor()).build();
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            Future<Integer> ok = workers.submit(() -> request(false, null, "/profile-ok"));
            Future<Integer> url = workers.submit(() -> request(true, null, "/profile-url"));
            Future<Integer> other = workers.submit(() -> request(independent, null, "/profile-other"));
            assertTrue(protectedArrived.await(5, TimeUnit.SECONDS));
            assertTrue(refreshArrived.await(5, TimeUnit.SECONDS));
            Future<SessionResolution> getter = workers.submit(() -> {
                resolverStarted.countDown();
                return SuperTokens.resolveAccessToken(context, a.getOwner(), true, a.getTokens().getAccessToken());
            });
            assertTrue(resolverStarted.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(Integer.valueOf(200), ok.get(5, TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(200), url.get(5, TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(200), other.get(5, TimeUnit.SECONDS));
            assertEquals(rotated.getAccessToken(), getter.get(5, TimeUnit.SECONDS).getAccessToken());
            assertEquals(1, refreshes.get());
            assertEquals(3, retried.get());
            assertSame(a.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
        } finally {
            release.countDown(); workers.shutdownNow();
            independent.dispatcher().cancelAll();
            independent.connectionPool().evictAll();
            independent.dispatcher().executorService().shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void precompiledPublishedSdkConsumerLinksAndExecutesAgainstCandidate() throws Exception {
        SuperTokens.resetForTests();
        File classes = new File(System.getProperty("supertokens.oldSdkClasses",
                "/private/var/folders/5r/6bl83v_92vg_zgq58303jlc00000gn/T/opencode/upstream-session-abi/classes"));
        assertTrue("provide original precompiled 0.5.4 consumer via supertokens.oldSdkClasses", new File(classes, "compatibility/OldSdkConsumer.class").isFile());
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{classes.toURI().toURL()}, getClass().getClassLoader())) {
            Class<?> consumer = Class.forName("compatibility.OldSdkConsumer", true, loader);
            assertSame(loader, consumer.getClassLoader());
            consumer.getMethod("exerciseAbsentSession", android.content.Context.class).invoke(null, context);
        }
    }
}
