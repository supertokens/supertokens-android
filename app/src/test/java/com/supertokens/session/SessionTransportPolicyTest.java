package com.supertokens.session;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionTransportPolicyTest extends SessionTestFixture {
    @Test public void refreshHeaderCallbackCanReenterWithoutWaitingOnItself() throws Exception {
        AtomicReference<SessionResolution> nested = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        configure(type -> {
            callbacks.incrementAndGet();
            SessionSnapshot current = SuperTokens.getSessionSnapshot(context);
            nested.set(SuperTokens.resolveAccessToken(context, current.getOwner(), true, null));
            return Collections.singletonMap("x-refresh-callback", "reentered");
        });
        SessionSnapshot a = install("A", "initial");
        SessionTokens rotated = tokens("A", "rotated");
        server.enqueue(rotation(rotated));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<SessionResolution> outer = worker.submit(() -> SuperTokens.resolveAccessToken(context, a.getOwner(), true, null));
            assertTrue("callback must not wait for the 30-second flight timeout", outer.get(3, TimeUnit.SECONDS).isSuccess());
            assertEquals(SessionResolution.Status.REFRESH_FAILED, nested.get().getStatus());
            assertTrue(nested.get().getError().getMessage().contains("Reentrant"));
            assertEquals(1, callbacks.get());
            assertEquals(1, server.getRequestCount());
            assertEquals("reentered", server.takeRequest(1, TimeUnit.SECONDS).getHeader("x-refresh-callback"));
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test public void refreshHeaderCallbackReplacementPreventsOldWireDispatch() throws Exception {
        SessionTokens replacement = tokens("B", "replacement");
        configure(type -> {
            SessionCommitResult result = SuperTokens.installSession(context, SuperTokens.getSessionSnapshot(context).getOwner(), replacement);
            assertTrue(result.isSuccess());
            return Collections.singletonMap("x-old-owner", "must-not-send");
        });
        SessionSnapshot a = install("A", "initial");
        SessionResolution result = SuperTokens.resolveAccessToken(context, a.getOwner(), true, null);
        assertEquals(SessionResolution.Status.STALE_OWNER, result.getStatus());
        assertEquals(0, server.getRequestCount());
        assertEquals(replacement.getAccessToken(), SuperTokens.getSessionSnapshot(context).getTokens().getAccessToken());
    }

    @Test public void configurationAtoBtoAWhilePreconnectBlockedCannotResurrectDispatch() throws Exception {
        SessionSnapshot a = install("A", "initial");
        CountDownLatch callbackEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> pending = worker.submit(() -> {
                try {
                    HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(server.url("/profile").url(), con -> {
                        con.setConnectTimeout(3000); con.setReadTimeout(3000);
                        callbackEntered.countDown();
                        await(release);
                    });
                    connection.disconnect();
                    fail("retired configuration dispatched");
                } catch (StaleSessionException expected) { }
                catch (Exception error) { throw new AssertionError(error); }
            });
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
            SessionCommitResult b = new SuperTokens.Builder(context, server.url("/").toString())
                    .apiBasePath("/other-auth").tokenTransferMethod("header").reconfigure();
            assertTrue(b.isSuccess());
            SessionCommitResult again = new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header").reconfigure();
            assertTrue(again.isSuccess());
            assertEquals(a.getConfigurationId(), again.getSnapshot().getConfigurationId());
            assertNotSame(a.getOwner(), again.getSnapshot().getOwner());
            assertEquals(SessionSnapshot.Status.ABSENT, again.getSnapshot().getStatus());
            release.countDown(); pending.get(5, TimeUnit.SECONDS);
            assertEquals(0, server.getRequestCount());
        } finally { release.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test public void throwingObserverReceivesLaterRevisionAndClosesReentrantly() throws Exception {
        CountDownLatch throwing = new CountDownLatch(1), closed = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<SessionSubscription> subscription = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        SessionSubscription observer = SuperTokens.observeSessions(context, snapshot -> {
            if (calls.incrementAndGet() == 1) {
                throwing.countDown();
                throw new IllegalStateException("intentional observer exception");
            }
            try {
                assertSame(snapshot.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
                subscription.get().close();
                assertTrue(SuperTokens.clearSession(context, snapshot.getOwner()).isSuccess());
            } catch (Throwable error) { callbackFailure.set(error); }
            finally { closed.countDown(); }
        });
        subscription.set(observer);
        try {
            observer.activate();
            install("A", "first");
            assertTrue(throwing.await(5, TimeUnit.SECONDS));
            install("B", "second");
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertNull(callbackFailure.get());
            assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
            install("C", "after-close");
            assertEquals(2, calls.get());
        } finally { observer.close(); }
    }

    @Test public void strictRefreshDoesNotFollow307AndPreservesProviderHeaders() throws Exception { strictResponse(307); }
    @Test public void strictRefreshDoesNotInvoke401Authenticator() throws Exception { strictResponse(401); }

    private void strictResponse(int status) throws Exception {
        configure(type -> Collections.singletonMap("x-refresh-provider", "kept"));
        SessionSnapshot a = install("A", "initial");
        AtomicInteger authentications = new AtomicInteger(), inheritedInterceptors = new AtomicInteger();
        OkHttpClient refresh = new OkHttpClient.Builder().followRedirects(true).retryOnConnectionFailure(true)
                .authenticator((route, response) -> {
                    authentications.incrementAndGet();
                    return response.request().newBuilder().header("Authorization", "Bearer substituted").build();
                }).addInterceptor(chain -> {
                    inheritedInterceptors.incrementAndGet();
                    return chain.proceed(chain.request().newBuilder().header("x-inherited", "unsafe").build());
                }).build();
        OkHttpClient strict = new OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS)
                .addInterceptor(new SuperTokensInterceptor(refresh)).build();
        AtomicReference<RecordedRequest> refreshWire = new AtomicReference<>();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/profile")) return new MockResponse().setResponseCode(401);
                if (request.getPath().equals("/auth/session/refresh")) {
                    refreshWire.set(request);
                    return new MockResponse().setResponseCode(status).addHeader("Location", server.url("/redirect-target"))
                            .addHeader("WWW-Authenticate", "Basic realm=refresh");
                }
                return new MockResponse().setResponseCode(500);
            }
        });
        try {
            if (status == 307) {
                try { request(strict, null, "/profile"); fail("redirect treated as refresh success"); }
                catch (IOException expected) { assertTrue(expected.getMessage().contains("307")); }
                assertSame(a.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            } else {
                assertEquals(401, request(strict, null, "/profile"));
                assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
            }
            assertEquals(2, server.getRequestCount());
            assertEquals(0, authentications.get());
            assertEquals(0, inheritedInterceptors.get());
            assertNotNull(refreshWire.get());
            assertEquals("kept", refreshWire.get().getHeader("x-refresh-provider"));
            assertEquals("Bearer " + a.getTokens().getRefreshToken(), refreshWire.get().getHeader("Authorization"));
            assertNull(refreshWire.get().getHeader("x-inherited"));
        } finally { close(strict); close(refresh); }
    }

    @Test public void strictRefreshConnectionFailureIsNotReplayed() throws Exception {
        SessionSnapshot a = install("A", "initial");
        OkHttpClient refresh = new OkHttpClient.Builder().retryOnConnectionFailure(true).build();
        OkHttpClient strict = new OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS)
                .addInterceptor(new SuperTokensInterceptor(refresh)).build();
        AtomicInteger refreshes = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/profile")) return new MockResponse().setResponseCode(401);
                refreshes.incrementAndGet();
                return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
            }
        });
        try {
            try { request(strict, null, "/profile"); fail("disconnected refresh succeeded"); }
            catch (IOException expected) { assertNotNull(expected.getMessage()); }
            assertEquals(1, refreshes.get());
            assertEquals(2, server.getRequestCount());
            assertSame(a.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            assertEquals(a.getTokens().getRefreshToken(), SuperTokens.getSessionSnapshot(context).getTokens().getRefreshToken());
        } finally { close(strict); close(refresh); }
    }

    @Test public void legacyNoargRefreshRetainsDownstreamInterceptor() throws Exception {
        install("A", "initial");
        SessionTokens rotated = tokens("A", "rotated");
        AtomicInteger refreshInterceptions = new AtomicInteger();
        OkHttpClient legacy = new OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS)
                .addInterceptor(new SuperTokensInterceptor()).addInterceptor(chain -> {
                    if (chain.request().url().encodedPath().equals("/auth/session/refresh")) refreshInterceptions.incrementAndGet();
                    return chain.proceed(chain.request().newBuilder().header("x-downstream", "retained").build());
                }).build();
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(rotation(rotated));
        server.enqueue(new MockResponse().setBody("fresh"));
        try {
            assertEquals(200, request(legacy, null, "/profile"));
            assertEquals(1, refreshInterceptions.get());
            for (int i = 0; i < 3; i++) assertEquals("retained", server.takeRequest(1, TimeUnit.SECONDS).getHeader("x-downstream"));
            assertEquals(rotated.getAccessToken(), SuperTokens.getSessionSnapshot(context).getTokens().getAccessToken());
        } finally { close(legacy); }
    }

    @Test public void unmanagedExplicitBearerCannotMutateSessionBothTransports() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            SessionSnapshot a = install("A", "initial");
            server.enqueue(rotation(tokens("B", "unmanaged-response")));
            if (url) {
                HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(server.url("/unmanaged").url(), con -> {
                    con.setRequestProperty("Authorization", "Bearer external"); con.setReadTimeout(5000); con.setConnectTimeout(5000);
                });
                try { assertEquals(200, connection.getResponseCode()); } finally { connection.disconnect(); }
            } else {
                try (Response response = client.newCall(new Request.Builder().url(server.url("/unmanaged"))
                        .header("Authorization", "Bearer external").build()).execute()) { assertEquals(200, response.code()); }
            }
            assertEquals("Bearer external", server.takeRequest(1, TimeUnit.SECONDS).getHeader("Authorization"));
            assertSame(a.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            assertEquals(a.getRevision(), SuperTokens.getSessionSnapshot(context).getRevision());
        }
    }

    @Test public void taggedMismatchingBearerIsRejectedBeforeEitherTransportDispatches() throws Exception {
        install("A", "initial");
        SessionRequestTag tag = SuperTokens.captureRequestTag(context);
        try {
            try (Response response = client.newCall(new Request.Builder().url(server.url("/tagged"))
                    .tag(SessionRequestTag.class, tag).header("Authorization", "Bearer external").build()).execute()) {
                fail("tagged mismatched bearer dispatched");
            }
        } catch (IOException expected) { assertNotNull(expected.getMessage()); }
        try {
            HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(server.url("/tagged").url(), con -> {
                con.setRequestProperty("Authorization", "Bearer external"); con.setConnectTimeout(3000); con.setReadTimeout(3000);
            }, tag);
            connection.disconnect(); fail("tagged URL mismatched bearer dispatched");
        } catch (IOException expected) { assertNotNull(expected.getMessage()); }
        assertEquals(0, server.getRequestCount());
    }

    @Test public void signoutGeneralErrorAndMalformedBodiesRetainTupleDespiteRemovalHeaders() throws Exception {
        SessionSnapshot a = install("A", "initial");
        server.enqueue(new MockResponse().addHeader("front-token", "remove")
                .setBody("{\"status\":\"GENERAL_ERROR\",\"message\":\"cannot sign out\"}"));
        try { SuperTokens.signOut(context); fail("GENERAL_ERROR accepted"); }
        catch (SuperTokensGeneralError expected) { assertEquals("cannot sign out", expected.getMessage()); }
        assertSame(a.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
        server.enqueue(new MockResponse().addHeader("front-token", "remove").setBody("not-json"));
        try { SuperTokens.signOut(context); fail("malformed signout accepted"); }
        catch (IllegalStateException expected) { assertNotNull(expected.getCause()); }
        assertSame(a.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
        assertEquals(a.getRevision(), SuperTokens.getSessionSnapshot(context).getRevision());
        assertEquals(a.getTokens().getRefreshToken(), SuperTokens.getSessionSnapshot(context).getTokens().getRefreshToken());
        for (int i = 0; i < 2; i++) {
            RecordedRequest wire = server.takeRequest(1, TimeUnit.SECONDS);
            assertEquals("/auth/signout", wire.getPath());
            assertEquals("Bearer " + a.getTokens().getAccessToken(), wire.getHeader("Authorization"));
        }
    }

    @Test public void successfulRemoteSignoutClearsExactCurrentOwner() throws Exception {
        SessionSnapshot a = install("A", "initial");
        server.enqueue(new MockResponse().addHeader("front-token", "remove").setBody("{\"status\":\"OK\"}"));
        SuperTokens.signOut(context);
        assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
        assertFalse(a.isCurrent());
        assertEquals(1, server.getRequestCount());
    }

    @Test public void delayedRemoteSignoutCannotClearReplacement() throws Exception {
        SessionSnapshot a = install("A", "initial");
        CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                arrived.countDown();
                return release.await(8, TimeUnit.SECONDS)
                        ? new MockResponse().addHeader("front-token", "remove").setBody("{\"status\":\"OK\"}")
                        : new MockResponse().setResponseCode(504);
            }
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> pending = worker.submit(() -> {
                try { SuperTokens.signOut(context); fail("stale signout reported success"); }
                catch (StaleSessionException expected) { }
                catch (Exception error) { throw new AssertionError(error); }
            });
            assertTrue(arrived.await(5, TimeUnit.SECONDS));
            SessionSnapshot b = install("B", "replacement");
            release.countDown(); pending.get(5, TimeUnit.SECONDS);
            assertSame(b.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            assertEquals(b.getRevision(), SuperTokens.getSessionSnapshot(context).getRevision());
            assertEquals(b.getTokens().getRefreshToken(), SuperTokens.getSessionSnapshot(context).getTokens().getRefreshToken());
            assertEquals("Bearer " + a.getTokens().getAccessToken(), server.takeRequest(1, TimeUnit.SECONDS).getHeader("Authorization"));
            assertEquals(1, server.getRequestCount());
        } finally { release.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    private void configure(CustomHeaderProvider provider) throws Exception {
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                .customHeaderProvider(provider).reconfigure().isSuccess());
    }

    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(8, TimeUnit.SECONDS)) throw new IOException("test barrier timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
    }

    private static void close(OkHttpClient client) {
        client.dispatcher().cancelAll(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow();
    }
}
