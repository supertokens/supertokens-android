package com.supertokens.session;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.supertokens.session.EventHandler.EventType.*;

public class LegacyEventContractTest extends SessionTestFixture {
    private void handler(EventHandler handler) throws Exception {
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .tokenTransferMethod("header").eventHandler(handler).reconfigure().isSuccess());
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    @Test public void noSessionNotificationsAreSynchronousAndNotDuplicatedBothTransports() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            List<EventHandler.EventType> events = new ArrayList<>();
            Thread producer = Thread.currentThread();
            handler(event -> { assertSame(producer, Thread.currentThread()); events.add(event); });
            server.enqueue(new MockResponse().setResponseCode(401));
            assertEquals(401, request(url, null, "/absent"));
            assertEquals(Arrays.asList(UNAUTHORISED), events);
            events.clear();
            server.enqueue(new MockResponse().setResponseCode(401).addHeader("front-token", "remove"));
            assertEquals(401, request(url, null, "/absent-remove"));
            assertEquals(Arrays.asList(UNAUTHORISED), events);
            events.clear();
            assertFalse(SuperTokens.attemptRefreshingSession(context));
            assertEquals(Arrays.asList(UNAUTHORISED), events);
            events.clear();
            SuperTokens.signOut(context);
            assertEquals(Arrays.asList(SIGN_OUT), events);
        }
    }

    @Test public void loginRefreshAndRevocationMatchOracleOrderBothTransports() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            List<EventHandler.EventType> events = new ArrayList<>();
            Thread producer = Thread.currentThread();
            handler(event -> { assertSame(producer, Thread.currentThread()); events.add(event); });
            events.clear();
            server.enqueue(rotation(tokens("A", "one")));
            assertEquals(200, request(url, null, "/login"));
            assertEquals(Arrays.asList(SESSION_CREATED), events);
            events.clear();
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(rotation(tokens("A", "two")));
            server.enqueue(new MockResponse());
            assertEquals(200, request(url, null, "/protected"));
            assertEquals(Arrays.asList(ACCESS_TOKEN_PAYLOAD_UPDATED, REFRESH_SESSION), events);
            events.clear();
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(new MockResponse().setResponseCode(401).addHeader("front-token", "remove"));
            assertEquals(401, request(url, null, "/revoked"));
            assertEquals(Arrays.asList(UNAUTHORISED), events);
        }
    }

    @Test public void acceptedBatchSurvivesOwnerReplacementBeforeDelivery() throws Exception {
        List<EventHandler.EventType> events = new ArrayList<>();
        handler(events::add);
        SessionCommitResult accepted = SessionState.install(context, SessionState.snapshot().getOwner(), tokens("A", "one"), null);
        assertTrue(accepted.isSuccess());
        install("B", "two");
        events.clear();
        assertFalse(accepted.getSnapshot().isCurrent());
        SessionState.dispatch(accepted);
        assertEquals(Arrays.asList(SESSION_CREATED), events);
    }

    @Test public void rawFrontItemMatchesOraclePayloadEventOnly() throws Exception {
        List<EventHandler.EventType> events = new ArrayList<>();
        handler(events::add);
        FrontToken.setItem(context, tokens("A", "one").getFrontToken());
        assertTrue(events.isEmpty());
        FrontToken.setItem(context, tokens("A", "two").getFrontToken());
        assertEquals(Arrays.asList(ACCESS_TOKEN_PAYLOAD_UPDATED), events);
        events.clear();
        FrontToken.setItem(context, "remove");
        assertTrue(events.isEmpty());
        assertEquals(SessionSnapshot.Status.ABSENT, SessionState.snapshot().getStatus());
    }

    @Test public void slowProducerCannotStealOrCapAnotherProducersEvents() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<Thread> delivered = new CopyOnWriteArrayList<>();
        handler(event -> {
            delivered.add(Thread.currentThread());
            if (event == SESSION_CREATED) { entered.countDown(); await(release); }
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = worker.submit(() -> { try { install("A", "first"); } catch (Exception e) { throw new AssertionError(e); } });
            await(entered);
            for (int i = 0; i < 80; i++) {
                install("A", "next-" + i);
                assertEquals(i + 2, delivered.size());
                assertSame(Thread.currentThread(), delivered.get(i + 1));
            }
            assertFalse(first.isDone());
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals(81, delivered.size());
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test public void handlerMayReenterGettersAndMutationsOutsideStateAndStorageLocks() throws Exception {
        List<EventHandler.EventType> events = new ArrayList<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        handler(event -> {
            events.add(event);
            if (event == SESSION_CREATED) {
                assertNotNull(SuperTokens.getAccessToken(context));
                try {
                    assertTrue(worker.submit(() -> SuperTokens.clearSession(context,
                            SessionState.snapshot().getOwner()).isSuccess()).get(5, TimeUnit.SECONDS));
                } catch (Exception e) { throw new AssertionError(e); }
            }
        });
        try {
            install("A", "one");
            assertEquals(Arrays.asList(SESSION_CREATED, SIGN_OUT), events);
            assertEquals(SessionSnapshot.Status.ABSENT, SessionState.snapshot().getStatus());
        } finally { worker.shutdownNow(); }
    }

    @Test public void throwingLoginHandlerLeavesCommittedStateAndPropagatesOriginalBothTransports() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            RuntimeException failure = new IllegalArgumentException("handler failure");
            handler(event -> { if (event == SESSION_CREATED) throw failure; });
            SessionTokens login = tokens("A", "one");
            server.enqueue(rotation(login));
            try { request(url, null, "/login"); fail("Handler failure must escape"); }
            catch (RuntimeException expected) { assertSame(failure, expected); }
            assertEquals(login.getAccessToken(), SuperTokens.getAccessToken(context));
        }
    }

    @Test public void explicitRefreshHandlerFailureKeepsOriginalExceptionAndReleasesFlights() throws Exception {
        RuntimeException failure = new IllegalStateException("refresh handler");
        handler(event -> { if (event == REFRESH_SESSION) throw failure; });
        SessionSnapshot installed = install("A", "one");
        for (int i = 0; i < 10; i++) {
            SessionTokens next = tokens("A", "next-" + i);
            server.enqueue(rotation(next));
            try {
                SuperTokens.resolveAccessToken(context, installed.getOwner(), true, null);
                fail("Handler failure must escape");
            } catch (RuntimeException expected) { assertSame(failure, expected); }
            assertEquals(next.getAccessToken(), SuperTokens.getAccessToken(context));
        }
    }

    @Test public void refreshPayloadHandlerFailureStopsRemainingBatchAfterCommitBothTransports() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            List<EventHandler.EventType> events = new ArrayList<>();
            RuntimeException failure = new IllegalStateException("payload handler");
            handler(event -> {
                events.add(event);
                if (event == ACCESS_TOKEN_PAYLOAD_UPDATED) throw failure;
            });
            install("A", "one");
            events.clear();
            SessionTokens refreshed = tokens("A", "two");
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(rotation(refreshed));
            try { request(url, null, "/protected"); fail("Legacy refresh handler failure must surface"); }
            catch (java.io.IOException expected) {
                assertEquals(java.io.IOException.class, expected.getClass());
                assertSame(failure, expected.getCause());
            }
            assertEquals(Arrays.asList(ACCESS_TOKEN_PAYLOAD_UPDATED), events);
            assertEquals(refreshed.getAccessToken(), SuperTokens.getAccessToken(context));
        }
    }

    @Test public void explicitRefreshPreservesReentrantHandlerException() throws Exception {
        RuntimeException failure = new IllegalStateException("nested handler");
        SessionTokens replacement = tokens("B", "replacement");
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .tokenTransferMethod("header")
                .eventHandler(event -> { if (event == ACCESS_TOKEN_PAYLOAD_UPDATED) throw failure; })
                .customHeaderProvider(type -> {
                    SuperTokens.installSession(context, SessionState.snapshot().getOwner(), replacement);
                    return java.util.Collections.emptyMap();
                }).reconfigure().isSuccess());
        SessionSnapshot initial = install("A", "one");
        try {
            SuperTokens.resolveAccessToken(context, initial.getOwner(), true, null);
            fail("Nested handler must escape unchanged");
        } catch (RuntimeException expected) { assertSame(failure, expected); }
        assertEquals(replacement.getAccessToken(), SuperTokens.getAccessToken(context));
        assertEquals(0, server.getRequestCount());
    }

    @Test public void explicitRefreshPreservesDeprecatedHelperException() throws Exception {
        RuntimeException failure = new IllegalStateException("legacy helper handler");
        AtomicBoolean enabled = new AtomicBoolean();
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .tokenTransferMethod("header")
                .eventHandler(event -> { if (enabled.get()) throw failure; })
                .customHeaderProvider(type -> {
                    Utils.fireSessionUpdateEventsIfNecessary(false, 200, "present");
                    return java.util.Collections.emptyMap();
                }).reconfigure().isSuccess());
        SessionSnapshot initial = install("A", "one");
        enabled.set(true);
        try {
            SuperTokens.resolveAccessToken(context, initial.getOwner(), true, null);
            fail("Legacy helper handler must escape unchanged");
        } catch (RuntimeException expected) { assertSame(failure, expected); }
        assertEquals(0, server.getRequestCount());
        assertSame(initial, SessionState.snapshot());
    }

    @Test public void refreshBatchKeepsItsOrderWhenFirstHandlerReplacesOwner() throws Exception {
        List<EventHandler.EventType> events = new ArrayList<>();
        AtomicBoolean replace = new AtomicBoolean();
        SessionTokens replacement = tokens("B", "replacement");
        handler(event -> {
            events.add(event);
            if (event == ACCESS_TOKEN_PAYLOAD_UPDATED && replace.compareAndSet(true, false)) {
                assertTrue(SuperTokens.installSession(context, SessionState.snapshot().getOwner(), replacement).isSuccess());
            }
        });
        SessionSnapshot installed = install("A", "one");
        events.clear();
        replace.set(true);
        server.enqueue(rotation(tokens("A", "two")));
        assertEquals(SessionResolution.Status.STALE_OWNER,
                SuperTokens.resolveAccessToken(context, installed.getOwner(), true, null).getStatus());
        assertEquals(Arrays.asList(ACCESS_TOKEN_PAYLOAD_UPDATED, ACCESS_TOKEN_PAYLOAD_UPDATED, REFRESH_SESSION), events);
        assertEquals(replacement.getAccessToken(), SuperTokens.getAccessToken(context));
    }

    @Test public void refreshFollowersFinishWhileLeaderHandlerIsBlockedWithoutDuplicateEvents() throws Exception {
        CountDownLatch wireEntered = new CountDownLatch(1), releaseWire = new CountDownLatch(1);
        CountDownLatch handlerEntered = new CountDownLatch(1), releaseHandler = new CountDownLatch(1);
        List<Thread> refreshThreads = new CopyOnWriteArrayList<>();
        handler(event -> {
            if (event == REFRESH_SESSION) {
                assertNotNull(SuperTokens.getAccessToken(context));
                refreshThreads.add(Thread.currentThread());
                handlerEntered.countDown();
                await(releaseHandler);
            }
        });
        SessionSnapshot initial = install("A", "one");
        MockResponse response = rotation(tokens("A", "two"));
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                wireEntered.countDown(); await(releaseWire); return response;
            }
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> producer = new AtomicReference<>();
        try {
            Future<SessionResolution> leader = workers.submit(() -> {
                producer.set(Thread.currentThread());
                return SuperTokens.resolveAccessToken(context, initial.getOwner(), true, null);
            });
            await(wireEntered);
            RefreshCoordinator.Acquisition joined = SessionState.acquireRefresh(initial.getOwner(), true, null,
                    SuperTokensHttpURLConnection.refreshTransport(), initial.getRefreshTokenRevision(), false);
            assertFalse(joined.leader);
            Future<SessionResolution> follower = workers.submit(() -> joined.flight.await());
            releaseWire.countDown();
            await(handlerEntered);
            assertTrue(follower.get(5, TimeUnit.SECONDS).isSuccess());
            assertFalse(leader.isDone());
            assertEquals(Arrays.asList(producer.get()), refreshThreads);
            releaseHandler.countDown();
            assertTrue(leader.get(5, TimeUnit.SECONDS).isSuccess());
            assertEquals(1, server.getRequestCount());
        } finally { releaseWire.countDown(); releaseHandler.countDown(); workers.shutdownNow(); }
    }

    @Test public void throwingLegacyLeaderDoesNotFailPublicRefreshFollowers() throws Exception {
        publicFollowersOfThrowingLeader(false);
    }

    @Test public void strictPublicFollowerStillRejectsExpiredLegacyCommitWhenLeaderHandlerThrows() throws Exception {
        publicFollowersOfThrowingLeader(true);
    }

    private void publicFollowersOfThrowingLeader(boolean expired) throws Exception {
        CountDownLatch wireEntered = new CountDownLatch(1), releaseWire = new CountDownLatch(1);
        CountDownLatch handlerEntered = new CountDownLatch(1), releaseHandler = new CountDownLatch(1);
        RuntimeException failure = new IllegalStateException("leader handler");
        List<EventHandler.EventType> events = new CopyOnWriteArrayList<>();
        List<Thread> eventThreads = new CopyOnWriteArrayList<>();
        handler(event -> {
            events.add(event);
            eventThreads.add(Thread.currentThread());
            if (event == REFRESH_SESSION) {
                handlerEntered.countDown(); await(releaseHandler); throw failure;
            }
        });
        SessionSnapshot initial = install("A", "one");
        events.clear(); eventThreads.clear();
        long expiry = expired ? 1 : 4102444800000L;
        SessionTokens refreshed = tokens("A", "primary-user", expiry, expiry, 5);
        MockResponse response = rotation(refreshed);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                wireEntered.countDown(); await(releaseWire); return response;
            }
        });
        ExecutorService workers = Executors.newFixedThreadPool(3);
        AtomicReference<Thread> leaderThread = new AtomicReference<>();
        AtomicReference<Thread> strictThread = new AtomicReference<>(), legacyThread = new AtomicReference<>();
        CountDownLatch strictStarted = new CountDownLatch(1), legacyStarted = new CountDownLatch(1);
        try {
            Future<Throwable> leader = workers.submit(() -> {
                leaderThread.set(Thread.currentThread());
                try { SuperTokens.attemptRefreshingSession(context); return null; }
                catch (Exception error) { return error; }
            });
            await(wireEntered);
            Future<SessionResolution> strict = workers.submit(() -> {
                strictThread.set(Thread.currentThread()); strictStarted.countDown();
                return SuperTokens.resolveAccessToken(context, initial.getOwner(), true, null);
            });
            Future<Boolean> legacy = workers.submit(() -> {
                legacyThread.set(Thread.currentThread()); legacyStarted.countDown();
                return SuperTokens.attemptRefreshingSession(context);
            });
            await(strictStarted); await(legacyStarted);
            awaitPublicFollower(strictThread.get()); awaitPublicFollower(legacyThread.get());
            releaseWire.countDown();
            await(handlerEntered);
            SessionResolution strictResult = strict.get(5, TimeUnit.SECONDS);
            assertEquals(expired ? SessionResolution.Status.REFRESH_FAILED : SessionResolution.Status.SUCCESS, strictResult.getStatus());
            if (expired) assertNotNull(strictResult.getError());
            assertEquals(Boolean.TRUE, legacy.get(5, TimeUnit.SECONDS));
            assertFalse(leader.isDone());
            assertSame(initial.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            assertEquals(refreshed.getAccessToken(), SuperTokens.getSessionSnapshot(context).getTokens().getAccessToken());
            assertEquals(Arrays.asList(ACCESS_TOKEN_PAYLOAD_UPDATED, REFRESH_SESSION), events);
            assertEquals(Arrays.asList(leaderThread.get(), leaderThread.get()), eventThreads);
            releaseHandler.countDown();
            Throwable error = leader.get(5, TimeUnit.SECONDS);
            assertEquals(java.io.IOException.class, error.getClass());
            assertSame(failure, error.getCause());
            assertEquals(1, server.getRequestCount());
            assertEquals(2, events.size());
        } finally { releaseWire.countDown(); releaseHandler.countDown(); workers.shutdownNow(); }
    }

    private void awaitPublicFollower(Thread follower) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            for (StackTraceElement frame : follower.getStackTrace()) {
                if (frame.getClassName().equals(RefreshCoordinator.Flight.class.getName())
                        && frame.getMethodName().equals("await")) return;
            }
            Thread.yield();
        }
        fail("Public follower did not join the blocked refresh flight");
    }

    @Test public void slowThrowingObserverCannotDelayAnotherProducersLegacyEvent() throws Exception {
        CountDownLatch observerEntered = new CountDownLatch(1), releaseObserver = new CountDownLatch(1);
        List<EventHandler.EventType> events = new CopyOnWriteArrayList<>();
        handler(events::add);
        SessionSubscription observer = SuperTokens.observeSessions(context, snapshot -> {
            observerEntered.countDown(); await(releaseObserver); throw new IllegalStateException("observer");
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            observer.activate();
            Future<?> first = worker.submit(() -> { try { install("A", "one"); } catch (Exception e) { throw new AssertionError(e); } });
            await(observerEntered);
            install("A", "two");
            assertEquals(Arrays.asList(SESSION_CREATED, ACCESS_TOKEN_PAYLOAD_UPDATED), events);
            releaseObserver.countDown();
            first.get(5, TimeUnit.SECONDS);
            install("A", "three");
            assertEquals(Arrays.asList(SESSION_CREATED, ACCESS_TOKEN_PAYLOAD_UPDATED, ACCESS_TOKEN_PAYLOAD_UPDATED), events);
        } finally { releaseObserver.countDown(); observer.close(); worker.shutdownNow(); }
    }

    @Test public void runtimeReconfigurationSignOutIsDeliveredAfterConfigurationLockReleased() throws Exception {
        install("A", "one");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        List<EventHandler.EventType> events = new ArrayList<>();
        try {
            handler(event -> {
                events.add(event);
                try {
                    worker.submit(() -> {
                        synchronized (SuperTokens.class) { assertEquals(SessionSnapshot.Status.ABSENT, SessionState.snapshot().getStatus()); }
                    }).get(5, TimeUnit.SECONDS);
                } catch (Exception e) { throw new AssertionError(e); }
            });
            assertEquals(Arrays.asList(SIGN_OUT), events);
        } finally { worker.shutdownNow(); }
    }
}
