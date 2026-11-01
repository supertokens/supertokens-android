package com.supertokens.session;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyGetterStaleCharacterizationTest extends SessionTestFixture {
    private boolean replaceDuringPreparation;

    @Override protected void initializeSession() throws Exception {
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                .customHeaderProvider(type -> {
                    if (replaceDuringPreparation) {
                        try { install("B", "replacement"); } catch (Exception error) { throw new AssertionError(error); }
                    }
                    return java.util.Collections.emptyMap();
                }).build();
    }

    @Test public void securePayloadSupersededBeforeDispatchDoesNotBorrowB() throws Exception {
        seedLegacy(tokens("A", "user-a", 1700000100000L, 1700000100000L, 5));
        restart(true);
        replaceDuringPreparation = true;
        try {
            SuperTokens.getAccessTokenPayloadSecurely(context);
            fail("superseded expired payload returned");
        } catch (java.io.IOException expected) {
            assertEquals(java.io.IOException.class, expected.getClass());
            assertEquals("Could not refresh session", expected.getMessage());
            assertNull(expected.getCause());
        }
        assertEquals("B", SuperTokens.getSessionSnapshot(context).getSessionHandle());
        assertEquals(0, server.getRequestCount());
    }

    @Test public void doesSessionExistReturnsFalseOnSupersededRefresh() throws Exception {
        assertEquals(Boolean.FALSE, supersede(() -> SuperTokens.doesSessionExist(context)));
    }

    @Test public void getAccessTokenReturnsNullOnSupersededRefresh() throws Exception {
        assertNull(supersede(() -> SuperTokens.getAccessToken(context)));
    }

    @Test public void securePayloadUsesLegacyFailureWithoutStaleCause() throws Exception {
        Object result = supersede(() -> SuperTokens.getAccessTokenPayloadSecurely(context));
        assertEquals(java.io.IOException.class, result.getClass());
        assertEquals("Could not refresh session", ((Exception) result).getMessage());
        assertNull(((Exception) result).getCause());
    }

    @Test public void strictResolutionRetainsStaleOwnerAndError() throws Exception {
        Object value = supersede(() -> SuperTokens.resolveAccessToken(context,
                SuperTokens.getSessionSnapshot(context).getOwner(), true, null));
        SessionResolution resolution = (SessionResolution) value;
        assertEquals(SessionResolution.Status.STALE_OWNER, resolution.getStatus());
        assertTrue(resolution.getError() instanceof StaleSessionException);
    }

    private Object supersede(Callable<?> operation) throws Exception {
        seedLegacy(tokens("A", "user-a", 1700000100000L, 1700000100000L, 5));
        restart(true);
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
            Future<?> pending = worker.submit(operation);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            SessionSnapshot replacement = install("B", "replacement");
            release.countDown();
            Object result;
            try { result = pending.get(5, TimeUnit.SECONDS); }
            catch (ExecutionException failure) { result = failure.getCause(); }
            assertSame(replacement.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            assertEquals(1, server.getRequestCount());
            return result;
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }
}
