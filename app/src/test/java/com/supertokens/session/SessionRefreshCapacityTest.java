package com.supertokens.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

public class SessionRefreshCapacityTest extends SessionTestFixture {
    @Test public void publicRefreshCapacityIsReleasedAfterEightRetiredLeaders() throws Exception {
        Map<String, FakeBackend> lineages = new ConcurrentHashMap<>();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                assertEquals("/auth/session/refresh", request.getPath());
                FakeBackend backend = lineages.get(request.getHeader("Authorization"));
                assertNotNull("Refresh borrowed an unregistered credential", backend);
                return backend.dispatch(request);
            }
        });
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<SessionResolution>> pending = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                SessionTokens tokens = tokens("retired-" + i, "before");
                FakeBackend backend = new FakeBackend(tokens);
                lineages.put("Bearer " + tokens.getRefreshToken(), backend);
                SessionCommitResult installed = SuperTokens.installSession(context, SuperTokens.getSessionSnapshot(context).getOwner(), tokens);
                assertTrue(installed.isSuccess());
                CountDownLatch arrived = new CountDownLatch(1);
                backend.gateRefreshes(arrived, release);
                SessionSnapshot captured = installed.getSnapshot();
                pending.add(workers.submit(() -> SuperTokens.resolveAccessToken(context, captured.getOwner(), true, null)));
                assertTrue(arrived.await(5, TimeUnit.SECONDS));
            }
            SessionTokens latestTokens = tokens("latest", "before");
            FakeBackend latestBackend = new FakeBackend(latestTokens);
            lineages.put("Bearer " + latestTokens.getRefreshToken(), latestBackend);
            SessionCommitResult latest = SuperTokens.installSession(context, SuperTokens.getSessionSnapshot(context).getOwner(), latestTokens);
            assertTrue(latest.isSuccess());
            SessionResolution bounded = SuperTokens.resolveAccessToken(context, latest.getSnapshot().getOwner(), true, null);
            assertEquals(SessionResolution.Status.REFRESH_FAILED, bounded.getStatus());
            assertEquals("Too many unfinished refresh transports", bounded.getError().getMessage());
            assertEquals(0, latestBackend.refreshAttempts.get());
            release.countDown();
            for (Future<SessionResolution> future : pending) assertEquals(SessionResolution.Status.STALE_OWNER, future.get(5, TimeUnit.SECONDS).getStatus());
            assertTrue(SuperTokens.resolveAccessToken(context, latest.getSnapshot().getOwner(), true, null).isSuccess());
            assertEquals(9, server.getRequestCount());
            for (FakeBackend backend : lineages.values()) {
                assertEquals(1, backend.refreshAttempts.get());
                assertEquals(1, backend.maximumRefreshOverlap.get());
                assertEquals(0, backend.reuseRejections.get());
            }
            assertEquals(SessionSnapshot.Status.AVAILABLE, SuperTokens.getSessionSnapshot(context).getStatus());
        } finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS)); }
    }
}
