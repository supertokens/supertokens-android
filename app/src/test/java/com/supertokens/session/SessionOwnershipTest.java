package com.supertokens.session;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionOwnershipTest extends SessionTestFixture {
    @Test public void delayedPromotionCannotOverwriteReplacement() throws Exception { crossing(false, false, false); }
    @Test public void delayedUrlPromotionCannotOverwriteReplacement() throws Exception { crossing(true, false, false); }
    @Test public void delayedRemovalCannotClearReplacement() throws Exception { crossing(false, true, false); }
    @Test public void delayedUrlRemovalCannotClearReplacement() throws Exception { crossing(true, true, false); }
    @Test public void taggedStaleOkHttp200ThrowsTypedError() throws Exception { crossing(false, false, true); }
    @Test public void taggedStaleUrl200ThrowsTypedError() throws Exception { crossing(true, false, true); }

    private void crossing(boolean url, boolean remove, boolean tagged) throws Exception {
        SessionSnapshot a = install("A", "initial");
        SessionRequestTag tag = tagged ? SuperTokens.captureRequestTag(context) : null;
        SessionTokens promoted = tokens("A", "promoted");
        CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<RecordedRequest> wire = new AtomicReference<>();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                wire.set(request);
                arrived.countDown();
                if (!release.await(8, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(504);
                return remove ? new MockResponse().setBody("old-body").addHeader("front-token", "remove") : promotion(promoted);
            }
        });
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> pending = workers.submit(() -> request(url, tag, "/profile"));
            assertTrue("A reached wire", arrived.await(5, TimeUnit.SECONDS));
            assertEquals("Bearer " + a.getTokens().getAccessToken(), wire.get().getHeader("Authorization"));
            SessionSnapshot b = install("B", "replacement");
            release.countDown();
            if (tagged) {
                try { pending.get(5, TimeUnit.SECONDS); fail("tagged stale response accepted"); }
                catch (java.util.concurrent.ExecutionException failure) { assertTrue(failure.getCause() instanceof StaleSessionException); }
                assertNull(tag.getAcceptedSnapshot());
            } else assertEquals(Integer.valueOf(200), pending.get(5, TimeUnit.SECONDS));
            SessionSnapshot current = SuperTokens.getSessionSnapshot(context);
            assertSame(b.getOwner(), current.getOwner());
            assertEquals(b.getRevision(), current.getRevision());
            assertEquals(b.getTokens().getAccessToken(), current.getTokens().getAccessToken());
            assertEquals(b.getTokens().getRefreshToken(), current.getTokens().getRefreshToken());
            assertEquals(1, server.getRequestCount());
        } finally {
            release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void mixedProtectedRequestsAllReachWireBeforeAnyResponse() throws Exception {
        SessionSnapshot a = install("A", "parallel");
        CountDownLatch arrived = new CountDownLatch(4), release = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                arrived.countDown();
                return new MockResponse().setResponseCode(release.await(8, TimeUnit.SECONDS) ? 200 : 504);
            }
        });
        ExecutorService workers = Executors.newFixedThreadPool(4);
        java.util.List<Future<Integer>> pending = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) { final boolean url = i % 2 == 0; pending.add(workers.submit(() -> request(url, null, "/parallel"))); }
            assertTrue("protected requests must not serialize network I/O", arrived.await(5, TimeUnit.SECONDS));
            release.countDown();
            for (Future<Integer> result : pending) assertEquals(Integer.valueOf(200), result.get(5, TimeUnit.SECONDS));
            for (int i = 0; i < 4; i++) assertEquals("Bearer " + a.getTokens().getAccessToken(), server.takeRequest(1, TimeUnit.SECONDS).getHeader("Authorization"));
        } finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test public void staleRemovalCannotClearNewerSameOwnerPromotion() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            SessionSnapshot a = install("A", "initial");
            SessionTokens promoted = tokens("A", "newer");
            CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    if (request.getPath().equals("/held-remove")) {
                        arrived.countDown();
                        if (!release.await(8, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(504);
                        return new MockResponse().addHeader("front-token", "remove");
                    }
                    return promotion(promoted);
                }
            });
            ExecutorService workers = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> pending = workers.submit(() -> request(url, null, "/held-remove"));
                assertTrue(arrived.await(5, TimeUnit.SECONDS));
                assertEquals(200, request(!url, null, "/promote"));
                SessionSnapshot newer = SuperTokens.getSessionSnapshot(context);
                assertSame(a.getOwner(), newer.getOwner());
                assertTrue(newer.getAccessTokenRevision() > a.getAccessTokenRevision());
                release.countDown();
                assertEquals(Integer.valueOf(200), pending.get(5, TimeUnit.SECONDS));
                assertEquals(newer.getRevision(), SuperTokens.getSessionSnapshot(context).getRevision());
                assertEquals(promoted.getAccessToken(), SuperTokens.getSessionSnapshot(context).getTokens().getAccessToken());
            } finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
        }
    }
}
