package com.supertokens.session;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionResourceBoundsTest extends SessionTestFixture {
    @Test public void followerThirtySecondTimeoutDoesNotReleaseRunningLeader() throws Exception {
        SessionSnapshot owner = install("wait-bound", "before");
        SessionTokens rotated = tokens("wait-bound", "after");
        java.util.Map<String, java.util.List<String>> headers = new java.util.HashMap<>();
        headers.put("st-access-token", java.util.Collections.singletonList(rotated.getAccessToken()));
        headers.put("st-refresh-token", java.util.Collections.singletonList(rotated.getRefreshToken()));
        headers.put("front-token", java.util.Collections.singletonList(rotated.getFrontToken()));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        RefreshCoordinator.Transport neverLead = (ctx, config, captured) -> {
            throw new AssertionError("Follower must not execute transport");
        };
        try {
            Future<SessionResolution> leader = worker.submit(() -> RefreshCoordinator.resolve(context,
                    owner.getOwner(), true, null, (ctx, config, captured) -> {
                        started.countDown();
                        try {
                            if (!release.await(40, TimeUnit.SECONDS)) throw new IOException("fixture wait exceeded");
                        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                        return new SessionResponse(200, "OK", headers);
                    }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            long before = System.nanoTime();
            SessionResolution follower = RefreshCoordinator.resolve(context, owner.getOwner(), true, null, neverLead);
            long waited = System.nanoTime() - before;
            assertEquals(SessionResolution.Status.REFRESH_FAILED, follower.getStatus());
            assertEquals("Timed out waiting for shared session refresh", follower.getError().getMessage());
            assertTrue(waited >= TimeUnit.SECONDS.toNanos(29));
            assertTrue(waited < TimeUnit.SECONDS.toNanos(35));
            RefreshCoordinator.Acquisition joined = SessionState.acquireRefresh(owner.getOwner(), true,
                    null, neverLead, owner.getRefreshTokenRevision());
            assertFalse(joined.leader);
            assertFalse(joined.flight.isDone());
            release.countDown();
            assertTrue(leader.get(3, TimeUnit.SECONDS).isSuccess());
            assertTrue(joined.flight.await().isSuccess());
            assertEquals(rotated.getRefreshToken(), SuperTokens.getSessionSnapshot(context).getTokens().getRefreshToken());
        } finally {
            release.countDown(); worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void blockedPreparationPastDeadlineCannotDispatchAfterReplacement() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString()).customHeaderProvider(type -> {
            if (callbacks.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
            return null;
        }).reconfigure().isSuccess());
        SessionSnapshot old = install("old", "before");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        RefreshDeadline.setClockForTests(clock::get);
        try {
            Future<SessionResolution> held = worker.submit(() -> SuperTokens.resolveAccessToken(context,
                    old.getOwner(), true, null));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            clock.set(TimeUnit.SECONDS.toNanos(11));
            SessionSnapshot replacement = install("new", "before");
            server.enqueue(rotation(tokens("new", "after")));
            assertTrue(SuperTokens.resolveAccessToken(context, replacement.getOwner(), true, null).isSuccess());
            release.countDown();
            assertEquals(SessionResolution.Status.STALE_OWNER, held.get(3, TimeUnit.SECONDS).getStatus());
            assertEquals("Only replacement refresh may reach wire", 1, server.getRequestCount());
            assertSame(replacement.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
        } finally {
            release.countDown(); worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            RefreshDeadline.setClockForTests(null);
        }
    }

    @Test public void bothTransportsPreservePublishedFdiNegotiation() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            install("fdi", "before");
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(rotation(tokens("fdi", "after")));
            server.enqueue(new MockResponse().setResponseCode(200));
            assertEquals(200, request(url, null, "/profile"));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            okhttp3.mockwebserver.RecordedRequest refresh = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(refresh);
            assertEquals("/auth/session/refresh", refresh.getPath());
            assertEquals("1.16,1.17,1.18,1.19,2.0,3.0,3.1,4.0,4.1", refresh.getHeader("fdi-version"));
            assertEquals("session", refresh.getHeader("rid"));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
        }
    }

    @Test public void repeatedReplacementBoundsRunningRetiredWorkersAndReleasesCapacity() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<SessionResolution>> pending = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                SessionSnapshot owner = install("retired-" + i, "before");
                CountDownLatch started = new CountDownLatch(1);
                pending.add(workers.submit(() -> RefreshCoordinator.resolve(context, owner.getOwner(), true, null,
                        (ctx, config, snapshot) -> {
                            started.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("fixture wait exceeded");
                            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                            throw new IOException("retired transport finished");
                        })));
                assertTrue(started.await(2, TimeUnit.SECONDS));
            }
            SessionSnapshot finalOwner = install("final", "before");
            SessionResolution bounded = SuperTokens.resolveAccessToken(context, finalOwner.getOwner(), true, null);
            assertEquals(SessionResolution.Status.REFRESH_FAILED, bounded.getStatus());
            assertEquals("Too many unfinished refresh transports", bounded.getError().getMessage());
            assertEquals(0, server.getRequestCount());
            release.countDown();
            for (Future<SessionResolution> task : pending)
                assertEquals(SessionResolution.Status.STALE_OWNER, task.get(3, TimeUnit.SECONDS).getStatus());
            server.enqueue(rotation(tokens("final", "after")));
            assertTrue(SuperTokens.resolveAccessToken(context, finalOwner.getOwner(), true, null).isSuccess());
        } finally {
            release.countDown(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void throwingCallbackErrorsAndExceptionsDoNotLeakFlightCapacity() throws Exception {
        for (int i = 0; i < 16; i++) {
            final boolean fatal = (i & 1) == 0;
            assertTrue(new SuperTokens.Builder(context, server.url("/").toString()).customHeaderProvider(type -> {
                if (fatal) throw new AssertionError("fixture callback error");
                throw new IllegalArgumentException("fixture callback exception");
            }).reconfigure().isSuccess());
            SessionSnapshot owner = install("callback", "before-" + i);
            try {
                SessionResolution result = SuperTokens.resolveAccessToken(context, owner.getOwner(), true, null);
                assertFalse(fatal);
                assertEquals(SessionResolution.Status.REFRESH_FAILED, result.getStatus());
            } catch (AssertionError error) {
                assertTrue(fatal);
                assertEquals("fixture callback error", error.getMessage());
            }
        }
        assertEquals(0, server.getRequestCount());
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString()).reconfigure().isSuccess());
        SessionSnapshot owner = install("recovered", "before");
        server.enqueue(rotation(tokens("recovered", "after")));
        assertTrue(SuperTokens.resolveAccessToken(context, owner.getOwner(), true, null).isSuccess());
    }

    @Test public void strictHangingSocketTimesOutWhileReplacementProgresses() throws Exception {
        SessionSnapshot old = install("old", "before");
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        OkHttpClient base = new OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build();
        OkHttpClient strict = base.newBuilder().addInterceptor(new SuperTokensInterceptor(base)).build();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        long start = System.nanoTime();
        try {
            Future<Integer> held = worker.submit(() -> request(strict, null, "/profile"));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            SessionSnapshot replacement = install("new", "before");
            assertFalse(old.isCurrent());
            server.enqueue(rotation(tokens("new", "after")));
            assertTrue(SuperTokens.resolveAccessToken(context, replacement.getOwner(), true, null).isSuccess());
            try { held.get(15, TimeUnit.SECONDS); fail("Retired request must fail"); }
            catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause() instanceof StaleSessionException);
            }
            assertTrue("Strict refresh wire must be bounded", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(15));
            assertSame(replacement.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
        } finally {
            strict.dispatcher().cancelAll(); strict.connectionPool().evictAll();
            strict.dispatcher().executorService().shutdownNow();
            worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
