package com.supertokens.session;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class InFlightPromotionTest extends SessionTestFixture {
    @Test public void staleRefreshRevisionIsRejectedBeforeDispatch() throws Exception {
        SessionSnapshot initial = install("A", "initial");
        SessionRequestTag captured = SuperTokens.captureRequestTag(context);
        SessionTokens rotated = tokens("A", "rotated");
        server.enqueue(rotation(rotated));
        assertEquals(200, request(false, null, "/rotate"));
        SessionSnapshot current = SuperTokens.getSessionSnapshot(context);
        assertSame(initial.getOwner(), current.getOwner());
        assertNotEquals(initial.getRefreshTokenRevision(), current.getRefreshTokenRevision());
        try {
            SessionTransport.requireCurrentRefresh(captured);
            fail("stale refresh revision must be rejected");
        } catch (StaleSessionException expected) { }
        int requests = server.getRequestCount();
        try {
            SuperTokensHttpURLConnection.refresh(context, captured.configuration, initial);
            fail("stale refresh must not dispatch");
        } catch (StaleSessionException expected) { }
        assertEquals(requests, server.getRequestCount());
    }

    @Test public void strictRotatingAndDifferentIdentityResponsesStayFenced() throws Exception {
        SessionSnapshot initial = install("A", "initial");
        RefreshCoordinator.Acquisition flight = SessionState.acquireRefresh(initial.getOwner(), true,
                null, SuperTokensHttpURLConnection.refreshTransport(), initial.getRefreshTokenRevision());
        assertTrue(flight.leader);
        assertTrue(SessionState.startRefresh(flight.flight));
        SessionTokens same = tokens("A", "changed");
        SessionTokens other = tokens("B", "changed");
        java.util.Map<String, java.util.List<String>> headers = new java.util.HashMap<>();
        headers.put("front-token", java.util.Collections.singletonList(same.getFrontToken()));
        headers.put("st-access-token", java.util.Collections.singletonList(same.getAccessToken()));
        SessionState.applyOrdinaryResponse(context, SuperTokens.captureRequestTag(context),
                new SessionResponse(200, "", headers), false);
        assertSame(initial, SuperTokens.getSessionSnapshot(context));
        headers.put("st-refresh-token", java.util.Collections.singletonList(same.getRefreshToken()));
        SessionState.applyOrdinaryResponse(context, SuperTokens.captureRequestTag(context),
                new SessionResponse(200, "", headers), true);
        assertSame(initial, SuperTokens.getSessionSnapshot(context));
        headers.remove("st-refresh-token");
        headers.put("front-token", java.util.Collections.singletonList(other.getFrontToken()));
        headers.put("st-access-token", java.util.Collections.singletonList(other.getAccessToken()));
        SessionState.applyOrdinaryResponse(context, SuperTokens.captureRequestTag(context),
                new SessionResponse(200, "", headers), true);
        assertSame(initial, SuperTokens.getSessionSnapshot(context));
        SessionState.completeRefresh(context, flight.flight, null, new java.io.IOException("test cleanup"));
    }

    @Test public void legacyPromotionPreservesFlightAndRefreshWins() throws Exception {
        List<EventHandler.EventType> events = new CopyOnWriteArrayList<>();
        new SuperTokens.Builder(context, server.url("/").toString()).eventHandler(events::add).reconfigure();
        SessionSnapshot initial = install("A", "initial");
        SessionTokens promoted = tokens("A", "promoted");
        SessionTokens refreshed = tokens("A", "refreshed");
        events.clear();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (request.getPath().contains("/session/refresh")) {
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("refresh timed out");
                    return rotation(refreshed);
                }
                return promotion(promoted);
            }
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> refresh = worker.submit(() -> SuperTokens.attemptRefreshingSession(context));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(200, request(false, null, "/update"));
            SessionSnapshot during = SuperTokens.getSessionSnapshot(context);
            assertSame(initial.getOwner(), during.getOwner());
            assertEquals(initial.getRefreshTokenRevision(), during.getRefreshTokenRevision());
            assertEquals(initial.getTokens().getRefreshToken(), during.getTokens().getRefreshToken());
            assertEquals(promoted.getAccessToken(), during.getTokens().getAccessToken());
            release.countDown();
            assertTrue(refresh.get(5, TimeUnit.SECONDS));
            assertEquals(refreshed.getAccessToken(), SuperTokens.getAccessToken(context));
            assertEquals(Arrays.asList(EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED,
                    EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED, EventHandler.EventType.REFRESH_SESSION), events);
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
