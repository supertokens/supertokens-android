package com.supertokens.session;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class RefreshFailureCompatibilityTest extends SessionTestFixture {
    private final List<EventHandler.EventType> events = new CopyOnWriteArrayList<>();

    @Override protected void initializeSession() throws Exception {
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                .eventHandler(events::add).build();
    }

    @Test public void bare401RetainsSessionAndOnlyLegacyEmitsUnauthorised() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            for (boolean tagged : new boolean[]{false, true}) {
                SessionSnapshot before = install("bare-" + url + tagged, "before");
                events.clear();
                server.enqueue(new MockResponse().setResponseCode(401));
                server.enqueue(new MockResponse().setResponseCode(401).setBody("missing refresh"));
                try {
                    request(url, tagged ? SuperTokens.captureRequestTag(context) : null, "/protected");
                    fail("Bare refresh 401 must fail");
                } catch (IOException expected) {
                    assertFalse(expected instanceof StaleSessionException);
                }
                assertSame(before, SuperTokens.getSessionSnapshot(context));
                assertEquals(tagged ? 0 : 1, events.size());
                if (!tagged) assertEquals(EventHandler.EventType.UNAUTHORISED, events.get(0));
            }
        }
    }

    @Test public void legacyBypassesStrictCooldownAndStrictStillSuppressesRetries() throws Exception {
        SessionSnapshot before = install("cooldown", "before");
        AtomicInteger refreshes = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/auth/session/refresh")) {
                    refreshes.incrementAndGet();
                    return new MockResponse().setResponseCode(429).setHeader("Retry-After", "3600");
                }
                return new MockResponse().setResponseCode(401);
            }
        });
        assertEquals(SessionResolution.Status.REFRESH_FAILED,
                SuperTokens.resolveAccessToken(context, before.getOwner(), true, null).getStatus());
        assertEquals(SessionResolution.Status.REFRESH_FAILED,
                SuperTokens.resolveAccessToken(context, before.getOwner(), true, null).getStatus());
        assertEquals(1, refreshes.get());
        try { request(false, null, "/protected"); fail(); } catch (IOException expected) { }
        assertEquals(2, refreshes.get());
        assertEquals(SessionResolution.Status.REFRESH_FAILED,
                SuperTokens.resolveAccessToken(context, before.getOwner(), true, null).getStatus());
        assertEquals(3, refreshes.get());
    }

    @Test public void strictFollowerSharesLegacyFailureButCanStartNewFlight() throws Exception {
        SessionSnapshot before = install("shared", "before");
        CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        RefreshCoordinator.Transport transport = (ctx, config, snapshot) -> {
            calls.incrementAndGet();
            arrived.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            throw new IOException("network");
        };
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<SessionResolution> leader = worker.submit(() -> RefreshCoordinator.resolve(
                    context, before.getOwner(), true, null, transport, true));
            assertTrue(arrived.await(5, TimeUnit.SECONDS));
            RefreshCoordinator.Acquisition follower = SessionState.acquireRefresh(before.getOwner(), true, null,
                    transport, before.getRefreshTokenRevision(), false);
            assertFalse(follower.leader);
            release.countDown();
            SessionResolution failure = leader.get(5, TimeUnit.SECONDS);
            assertSame(failure.getError(), follower.flight.await().getError());
            assertEquals(SessionResolution.Status.REFRESH_FAILED, RefreshCoordinator.resolve(
                    context, before.getOwner(), true, null, transport, false).getStatus());
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
