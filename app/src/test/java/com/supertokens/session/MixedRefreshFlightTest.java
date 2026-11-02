package com.supertokens.session;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public class MixedRefreshFlightTest extends SessionTestFixture {
    private final List<EventHandler.EventType> events = new CopyOnWriteArrayList<>();
    private final AtomicInteger preparations = new AtomicInteger();
    private volatile CountDownLatch firstPreparation;
    private volatile CountDownLatch releaseFirst;
    private volatile CountDownLatch secondPreparation;
    private volatile CountDownLatch releaseSecond;

    @Override protected void initializeSession() throws Exception {
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                .eventHandler(events::add).customHeaderProvider(type -> {
                    if (type == CustomHeaderProvider.RequestType.REFRESH) {
                        int n = preparations.incrementAndGet();
                        if (n == 1 && firstPreparation != null) await(firstPreparation, releaseFirst);
                        if (n == 2 && secondPreparation != null) await(secondPreparation, releaseSecond);
                    }
                    return null;
                }).build();
    }

    private static void await(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("test latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private RefreshCoordinator.Acquisition join(SessionSnapshot before, RefreshCoordinator.Transport transport,
                                                boolean legacy) {
        RefreshCoordinator.Acquisition result = SessionState.acquireRefresh(before.getOwner(), true, null,
                transport, before.getRefreshTokenRevision(), legacy);
        assertFalse(result.leader);
        assertNotNull(result.flight);
        return result;
    }

    @Test public void coordinatorStrictLeaderSharesSuccessWithAcquiredLegacyFollowers() throws Exception {
        SessionSnapshot before = install("mixed-success", "before");
        SessionTokens rotated = tokens("mixed-success", "after");
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("front-token", Collections.singletonList(rotated.getFrontToken()));
        headers.put("st-access-token", Collections.singletonList(rotated.getAccessToken()));
        headers.put("st-refresh-token", Collections.singletonList(rotated.getRefreshToken()));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger leaderCalls = new AtomicInteger(), followerCalls = new AtomicInteger();
        RefreshCoordinator.Transport leaderTransport = (ctx, config, captured) -> {
            leaderCalls.incrementAndGet();
            await(entered, release);
            return new SessionResponse(200, "", headers);
        };
        RefreshCoordinator.Transport followerTransport = (ctx, config, captured) -> {
            followerCalls.incrementAndGet();
            throw new IOException("follower must not dispatch");
        };
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<SessionResolution> leader = worker.submit(() -> RefreshCoordinator.resolve(context,
                    before.getOwner(), true, null, leaderTransport, false));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            RefreshCoordinator.Acquisition first = join(before, followerTransport, true);
            RefreshCoordinator.Acquisition second = join(before, followerTransport, true);
            assertSame(first.flight, second.flight);
            release.countDown();
            SessionResolution result = leader.get(5, TimeUnit.SECONDS);
            assertTrue(result.isSuccess());
            assertSame(result.getSnapshot(), first.flight.await().getSnapshot());
            assertSame(result.getSnapshot(), second.flight.await().getSnapshot());
            assertSame(before.getOwner(), result.getSnapshot().getOwner());
            assertEquals(rotated.getRefreshToken(), result.getSnapshot().getTokens().getRefreshToken());
            assertEquals(1, leaderCalls.get());
            assertEquals(0, followerCalls.get());
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void coordinatorLegacyAcquisitionPromotesStrictLedFlightUnderD9() throws Exception {
        SessionSnapshot before = install("mixed-failure", "before");
        events.clear();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        RefreshCoordinator.Transport transport = (ctx, config, captured) -> {
            calls.incrementAndGet();
            await(entered, release);
            return new SessionResponse(429, "", Collections.singletonMap("Retry-After",
                    Collections.singletonList("3600")));
        };
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<SessionResolution> leader = worker.submit(() -> RefreshCoordinator.resolve(context,
                    before.getOwner(), true, null, transport, false));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            RefreshCoordinator.Acquisition follower = join(before, transport, true);
            release.countDown();
            SessionResolution failure = leader.get(5, TimeUnit.SECONDS);
            assertEquals(SessionResolution.Status.REFRESH_FAILED, failure.getStatus());
            assertSame(failure.getError(), follower.flight.await().getError());
            assertSame(before, SuperTokens.getSessionSnapshot(context));
            assertEquals(Collections.singletonList(EventHandler.EventType.SIGN_OUT), events);
            assertEquals(SessionResolution.Status.REFRESH_FAILED, RefreshCoordinator.resolve(context,
                    before.getOwner(), true, null, transport, false).getStatus());
            assertEquals("D9: compatible joined flight leaves no strict cooldown", 2, calls.get());
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void coordinatorAcquisitionsShareLegacyDisconnectRetry() throws Exception {
        SessionSnapshot before = install("mixed-disconnect", "before");
        firstPreparation = new CountDownLatch(1);
        releaseFirst = new CountDownLatch(1);
        secondPreparation = new CountDownLatch(1);
        releaseSecond = new CountDownLatch(1);
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        RefreshCoordinator.Transport legacy = SuperTokensHttpURLConnection.refreshTransport(true);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<SessionResolution> leader = worker.submit(() -> RefreshCoordinator.resolve(context,
                    before.getOwner(), true, null, legacy, true));
            assertTrue(firstPreparation.await(5, TimeUnit.SECONDS));
            List<RefreshCoordinator.Acquisition> followers = new ArrayList<>();
            followers.add(join(before, legacy, false));
            followers.add(join(before, legacy, true));
            releaseFirst.countDown();
            assertTrue(secondPreparation.await(5, TimeUnit.SECONDS));
            followers.add(join(before, legacy, false));
            followers.add(join(before, legacy, true));
            releaseSecond.countDown();
            SessionResolution failure = leader.get(5, TimeUnit.SECONDS);
            assertEquals(SessionResolution.Status.REFRESH_FAILED, failure.getStatus());
            for (RefreshCoordinator.Acquisition follower : followers) {
                assertSame(followers.get(0).flight, follower.flight);
                assertSame(failure.getError(), follower.flight.await().getError());
            }
            assertEquals("SDK preparations, not independent OkHttp wire retries", 2, preparations.get());
            assertEquals(2, server.getRequestCount());
            assertSame(before, SuperTokens.getSessionSnapshot(context));
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void resetInvalidatesOtherThreadsPendingObserver() throws Exception {
        CountDownLatch registered = new CountDownLatch(1), resume = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = worker.submit(() -> {
                try {
                    SessionState.observeNextFollowerForTests(calls::incrementAndGet);
                    await(registered, resume);
                    SessionState.notifyFollowerAcquiredForTests();
                    SessionState.observeNextFollowerForTests(calls::incrementAndGet);
                    SessionState.notifyFollowerAcquiredForTests();
                    SessionState.notifyFollowerAcquiredForTests();
                } finally {
                    SessionState.clearFollowerObserverForTests();
                }
            });
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            SessionState.resetForTests();
            resume.countDown();
            result.get(5, TimeUnit.SECONDS);
            assertEquals("reset invalidates the old registration; a new one is one-shot", 1, calls.get());
        } finally {
            resume.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void observerCleanupMustRunOnRegisteringThread() throws Exception {
        CountDownLatch registered = new CountDownLatch(1), resume = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = worker.submit(() -> {
                try {
                    SessionState.observeNextFollowerForTests(calls::incrementAndGet);
                    await(registered, resume);
                    SessionState.notifyFollowerAcquiredForTests();
                    SessionState.observeNextFollowerForTests(calls::incrementAndGet);
                    SessionState.clearFollowerObserverForTests();
                    SessionState.notifyFollowerAcquiredForTests();
                } finally {
                    SessionState.clearFollowerObserverForTests();
                }
            });
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            SessionState.clearFollowerObserverForTests();
            resume.countDown();
            result.get(5, TimeUnit.SECONDS);
            assertEquals("another thread cannot clear the registration; owner cleanup can", 1, calls.get());
        } finally {
            resume.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Callable<Object> publicRefresh(boolean legacy, SessionOwner owner, CountDownLatch admitted) {
        return () -> {
            if (admitted != null) SessionState.observeNextFollowerForTests(admitted::countDown);
            try {
                if (legacy) return SuperTokens.attemptRefreshingSession(context);
                return SuperTokens.resolveAccessToken(context, owner, true, null);
            } catch (IOException error) {
                return error;
            } finally {
                SessionState.clearFollowerObserverForTests();
            }
        };
    }

    @Test public void publicMixedFollowersShareStrictAndLegacyLeaderResults() throws Exception {
        for (boolean legacyLeader : new boolean[]{false, true}) {
            for (boolean success : new boolean[]{true, false}) {
                preparations.set(0);
                String handle = "public-" + legacyLeader + "-" + success;
                SessionSnapshot before = install(handle, "before");
                SessionTokens after = tokens(handle, "after");
                firstPreparation = new CountDownLatch(1);
                releaseFirst = new CountDownLatch(1);
                secondPreparation = null;
                CountDownLatch admitted = new CountDownLatch(2);
                server.enqueue(success ? rotation(after)
                        : new MockResponse().setResponseCode(429).setHeader("Retry-After", "3600"));
                ExecutorService workers = Executors.newFixedThreadPool(3);
                int requests = server.getRequestCount();
                try {
                    Future<Object> leader = workers.submit(publicRefresh(legacyLeader, before.getOwner(), null));
                    assertTrue(firstPreparation.await(5, TimeUnit.SECONDS));
                    Future<Object> strict = workers.submit(publicRefresh(false, before.getOwner(), admitted));
                    Future<Object> legacy = workers.submit(publicRefresh(true, before.getOwner(), admitted));
                    assertTrue(admitted.await(5, TimeUnit.SECONDS));
                    assertEquals(1, preparations.get());
                    releaseFirst.countDown();
                    Object leaderResult = leader.get(5, TimeUnit.SECONDS);
                    Object strictResult = strict.get(5, TimeUnit.SECONDS);
                    Object legacyResult = legacy.get(5, TimeUnit.SECONDS);
                    assertTrue(strictResult instanceof SessionResolution);
                    SessionResolution strictResolution = (SessionResolution) strictResult;
                    if (success) {
                        assertTrue(strictResolution.isSuccess());
                        assertEquals(after.getAccessToken(), strictResolution.getAccessToken());
                        assertEquals(Boolean.TRUE, legacyResult);
                        if (legacyLeader) assertEquals(Boolean.TRUE, leaderResult);
                        else {
                            assertTrue(leaderResult instanceof SessionResolution);
                            assertTrue(((SessionResolution) leaderResult).isSuccess());
                            assertEquals(after.getAccessToken(), ((SessionResolution) leaderResult).getAccessToken());
                        }
                        assertEquals(after.getRefreshToken(), SuperTokens.getSessionSnapshot(context)
                                .getTokens().getRefreshToken());
                    } else {
                        assertEquals(SessionResolution.Status.REFRESH_FAILED, strictResolution.getStatus());
                        assertNotNull(strictResolution.getError());
                        assertTrue(legacyResult instanceof IOException);
                        // The current shared flight passes the same failure to both callers.
                        assertSame(strictResolution.getError(), legacyResult);
                        if (legacyLeader) assertSame(strictResolution.getError(), leaderResult);
                        else {
                            assertTrue(leaderResult instanceof SessionResolution);
                            assertEquals(SessionResolution.Status.REFRESH_FAILED,
                                    ((SessionResolution) leaderResult).getStatus());
                            assertSame(strictResolution.getError(), ((SessionResolution) leaderResult).getError());
                        }
                        assertSame(before, SuperTokens.getSessionSnapshot(context));
                    }
                    assertEquals("one URLConnection SDK preparation", 1, preparations.get());
                    assertEquals("one observed HTTP exchange", requests + 1, server.getRequestCount());
                } finally {
                    releaseFirst.countDown();
                    workers.shutdownNow();
                    assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
                    firstPreparation = null;
                }
            }
        }
    }

    @Test public void publicFollowersShareLegacyDisconnectRetryAcrossBothPreparations() throws Exception {
        SessionSnapshot before = install("public-retry", "before");
        firstPreparation = new CountDownLatch(1);
        releaseFirst = new CountDownLatch(1);
        secondPreparation = new CountDownLatch(1);
        releaseSecond = new CountDownLatch(1);
        CountDownLatch firstJoined = new CountDownLatch(2), secondJoined = new CountDownLatch(2);
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        int requests = server.getRequestCount();
        ExecutorService workers = Executors.newFixedThreadPool(5);
        try {
            Future<Object> leader = workers.submit(publicRefresh(true, before.getOwner(), null));
            assertTrue(firstPreparation.await(5, TimeUnit.SECONDS));
            Future<Object> strict1 = workers.submit(publicRefresh(false, before.getOwner(), firstJoined));
            Future<Object> legacy1 = workers.submit(publicRefresh(true, before.getOwner(), firstJoined));
            assertTrue(firstJoined.await(5, TimeUnit.SECONDS));
            releaseFirst.countDown();
            assertTrue(secondPreparation.await(5, TimeUnit.SECONDS));
            Future<Object> strict2 = workers.submit(publicRefresh(false, before.getOwner(), secondJoined));
            Future<Object> legacy2 = workers.submit(publicRefresh(true, before.getOwner(), secondJoined));
            assertTrue(secondJoined.await(5, TimeUnit.SECONDS));
            releaseSecond.countDown();
            Object leaderResult = leader.get(5, TimeUnit.SECONDS);
            Object a = strict1.get(5, TimeUnit.SECONDS), b = strict2.get(5, TimeUnit.SECONDS);
            Object c = legacy1.get(5, TimeUnit.SECONDS), d = legacy2.get(5, TimeUnit.SECONDS);
            assertTrue(leaderResult instanceof IOException);
            assertTrue(a instanceof SessionResolution);
            assertTrue(b instanceof SessionResolution);
            assertEquals(SessionResolution.Status.REFRESH_FAILED, ((SessionResolution) a).getStatus());
            assertEquals(SessionResolution.Status.REFRESH_FAILED, ((SessionResolution) b).getStatus());
            // Identity here checks the implementation's shared flight, not a public API guarantee.
            assertSame(leaderResult, ((SessionResolution) a).getError());
            assertSame(leaderResult, ((SessionResolution) b).getError());
            assertSame(leaderResult, c);
            assertSame(leaderResult, d);
            assertEquals("two URLConnection SDK preparations", 2, preparations.get());
            assertEquals("two observed HTTP exchanges", requests + 2, server.getRequestCount());
            assertSame(before, SuperTokens.getSessionSnapshot(context));
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            firstPreparation = null;
            secondPreparation = null;
        }
    }

    @Test public void replacementAndReconfigurationFenceCoordinatorMixedRetry() throws Exception {
        for (boolean reconfigure : new boolean[]{false, true}) {
            preparations.set(0);
            SessionSnapshot before = install("mixed-fence-" + reconfigure, "before");
            firstPreparation = new CountDownLatch(1);
            releaseFirst = new CountDownLatch(1);
            secondPreparation = new CountDownLatch(1);
            releaseSecond = new CountDownLatch(1);
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
            RefreshCoordinator.Transport transport = SuperTokensHttpURLConnection.refreshTransport(true);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            int requests = server.getRequestCount();
            try {
                Future<SessionResolution> leader = worker.submit(() -> RefreshCoordinator.resolve(context,
                        before.getOwner(), true, null, transport, true));
                assertTrue(firstPreparation.await(5, TimeUnit.SECONDS));
                RefreshCoordinator.Acquisition follower = join(before, transport, false);
                releaseFirst.countDown();
                assertTrue(secondPreparation.await(5, TimeUnit.SECONDS));
                if (reconfigure) {
                    new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                            .reconfigure();
                } else {
                    install("replacement", "after");
                }
                SessionSnapshot current = SuperTokens.getSessionSnapshot(context);
                releaseSecond.countDown();
                assertEquals(SessionResolution.Status.STALE_OWNER, leader.get(5, TimeUnit.SECONDS).getStatus());
                assertEquals(SessionResolution.Status.STALE_OWNER, follower.flight.await().getStatus());
                assertSame(current, SuperTokens.getSessionSnapshot(context));
                assertEquals("retry was prepared but fenced before dispatch", requests + 1, server.getRequestCount());
                assertEquals(2, preparations.get());
            } finally {
                releaseFirst.countDown();
                releaseSecond.countDown();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
                firstPreparation = null;
                secondPreparation = null;
            }
        }
    }
}
