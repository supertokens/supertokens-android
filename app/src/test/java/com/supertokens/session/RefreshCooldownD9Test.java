package com.supertokens.session;

import java.io.IOException;
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

public class RefreshCooldownD9Test extends SessionTestFixture {
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
        assertEquals("D9: strict cooldown suppresses retries", 1, refreshes.get());
        try { request(false, null, "/protected"); fail(); } catch (IOException expected) { }
        assertEquals("D9: legacy bypasses strict cooldown", 2, refreshes.get());
        assertEquals(SessionResolution.Status.REFRESH_FAILED,
                SuperTokens.resolveAccessToken(context, before.getOwner(), true, null).getStatus());
        assertEquals("D9: legacy failure leaves no cooldown", 3, refreshes.get());
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
            assertSame("D9: joined followers share the failure", failure.getError(), follower.flight.await().getError());
            assertEquals(SessionResolution.Status.REFRESH_FAILED, RefreshCoordinator.resolve(
                    context, before.getOwner(), true, null, transport, false).getStatus());
            assertEquals("D9: later callers start a new flight", 2, calls.get());
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
