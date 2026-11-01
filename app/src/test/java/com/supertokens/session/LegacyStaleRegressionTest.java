package com.supertokens.session;

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

public class LegacyStaleRegressionTest extends SessionTestFixture {
    @Test public void refreshSupersededDuringHeaderCallbackDoesNotDispatch() throws Exception {
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                .customHeaderProvider(type -> {
                    try { install("B", "replacement"); } catch (Exception e) { throw new AssertionError(e); }
                    return java.util.Collections.emptyMap();
                }).reconfigure();
        install("A", "before");
        assertFalse(SuperTokens.attemptRefreshingSession(context));
        assertEquals("B", SuperTokens.getSessionSnapshot(context).getSessionHandle());
        assertEquals(0, server.getRequestCount());
    }

    @Test public void supersededLegacyRefreshReturnsFalseWithoutBorrowingReplacement() throws Exception {
        install("A", "before");
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final SessionTokens refreshed = tokens("A", "after");
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return rotation(refreshed);
            }
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> pending = worker.submit(() -> SuperTokens.attemptRefreshingSession(context));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            SessionSnapshot replacement = install("B", "replacement");
            release.countDown();
            assertFalse(pending.get(5, TimeUnit.SECONDS));
            assertSame(replacement.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            assertEquals(tokens("B", "replacement").getAccessToken(), SuperTokens.getAccessToken(context));
            assertEquals(1, server.getRequestCount());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }
}
