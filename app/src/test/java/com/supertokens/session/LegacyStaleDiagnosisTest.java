package com.supertokens.session;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyStaleDiagnosisTest extends SessionTestFixture {
    @Test public void signoutSupersededBeforeDispatchReturnsWithoutTouchingB() throws Exception {
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                .customHeaderProvider(type -> {
                    if (type == CustomHeaderProvider.RequestType.SIGN_OUT) {
                        try { install("B", "replacement"); } catch (Exception e) { throw new AssertionError(e); }
                    }
                    return Collections.emptyMap();
                }).reconfigure();
        install("A", "before");
        SuperTokens.signOut(context);
        assertEquals("B", SuperTokens.getSessionSnapshot(context).getSessionHandle());
        assertEquals(0, server.getRequestCount());
    }

    @Test public void lateSignoutSuccessReturnsWithoutTouchingB() throws Exception {
        delayedSignout(new MockResponse().setBody("{\"status\":\"OK\"}"), null);
    }

    @Test public void lateSignoutRetainsGeneralError() throws Exception {
        delayedSignout(new MockResponse().setBody("{\"status\":\"GENERAL_ERROR\",\"message\":\"denied\"}"), SuperTokensGeneralError.class);
    }

    @Test public void lateSignoutRetainsMalformedBodyError() throws Exception {
        delayedSignout(new MockResponse().setBody("not-json"), IllegalStateException.class);
    }

    @Test public void lateSignoutRetainsHttpError() throws Exception {
        delayedSignout(new MockResponse().setResponseCode(500), java.io.IOException.class);
    }

    @Test public void lateSignout401StopsWithoutRefreshingB() throws Exception {
        delayedSignout(new MockResponse().setResponseCode(401), null);
    }

    private void delayedSignout(final MockResponse response, Class<?> expectedError) throws Exception {
        install("A", "before");
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return response;
            }
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> pending = worker.submit(() -> { SuperTokens.signOut(context); return null; });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            SessionSnapshot replacement = install("B", "replacement");
            release.countDown();
            try {
                pending.get(5, TimeUnit.SECONDS);
                assertNull(expectedError);
            } catch (ExecutionException error) {
                assertEquals(expectedError, error.getCause().getClass());
            }
            assertSame(replacement.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            assertEquals(1, server.getRequestCount());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }
}
