package com.supertokens.session;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionCookieDeletionTest extends SessionTestFixture {
    private CookieHandler previous;

    @Before public void removeCookieHandler() {
        previous = CookieHandler.getDefault();
        CookieHandler.setDefault(null);
    }

    @After public void restoreCookieHandler() { CookieHandler.setDefault(previous); }

    private MockResponse revoked() {
        return new MockResponse().setResponseCode(401).addHeader("front-token", "remove")
                .addHeader("st-access-token", "").addHeader("st-refresh-token", "")
                .addHeader("Set-Cookie", "sAccessToken=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; SameSite=Lax")
                .addHeader("Set-Cookie", "sRefreshToken=; Path=/auth/session/refresh; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; SameSite=Lax");
    }

    @Test public void revokedHeaderRefreshClearsWithoutCookieHandler() throws Exception {
        SessionSnapshot original = install("revoked", "before");
        server.enqueue(revoked());
        SuperTokens.resolveAccessToken(context, original.getOwner(), true, null);
        assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
        assertFalse(original.getOwner().isCurrent());
        assertEquals("header", server.takeRequest(2, TimeUnit.SECONDS).getHeader("st-auth-mode"));
    }

    @Test public void issuedAccessCookieStillRequiresHandler() throws Exception {
        SessionSnapshot original = install("issued", "before");
        server.enqueue(rotation(tokens("issued", "after")).addHeader("Set-Cookie", "sAccessToken=live; Path=/; Max-Age=3600"));
        SessionResolution result = SuperTokens.resolveAccessToken(context, original.getOwner(), true, null);
        assertTrue(result.getError() instanceof RefreshCoordinator.ConfigurationException);
        assertSame(original, SuperTokens.getSessionSnapshot(context));
    }

    @Test public void emptyUnexpiredAccessCookieStillRequiresHandler() throws Exception {
        SessionSnapshot original = install("empty", "before");
        server.enqueue(revoked().setHeader("Set-Cookie", "sAccessToken=; Path=/"));
        SessionResolution result = SuperTokens.resolveAccessToken(context, original.getOwner(), true, null);
        assertTrue(result.getError() instanceof RefreshCoordinator.ConfigurationException);
        assertSame(original, SuperTokens.getSessionSnapshot(context));
    }

    @Test public void maxAgeZeroDeletionClearsWithoutHandler() throws Exception {
        SessionSnapshot original = install("max-age", "before");
        server.enqueue(revoked().setHeader("Set-Cookie", "sAccessToken=; Path=/; Max-Age=0"));
        SuperTokens.resolveAccessToken(context, original.getOwner(), true, null);
        assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
    }

    @Test public void delayedDeletionCannotClearReplacementOwner() throws Exception {
        SessionSnapshot original = install("old", "before");
        java.util.concurrent.CountDownLatch arrived = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) throws InterruptedException {
                arrived.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(500);
                return revoked();
            }
        });
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<SessionResolution> pending = worker.submit(() ->
                    SuperTokens.resolveAccessToken(context, original.getOwner(), true, null));
            assertTrue(arrived.await(2, TimeUnit.SECONDS));
            SessionSnapshot replacement = install("replacement", "after");
            release.countDown();
            assertFalse(pending.get(3, TimeUnit.SECONDS).isSuccess());
            assertSame(replacement, SuperTokens.getSessionSnapshot(context));
        } finally {
            release.countDown(); worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void cookieModeStillRequiresHandlerBeforeDispatch() throws Exception {
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("cookie").reconfigure();
        SessionSnapshot original = install("cookie", "before");
        SessionResolution result = SuperTokens.resolveAccessToken(context, original.getOwner(), true, null);
        assertTrue(result.getError() instanceof RefreshCoordinator.ConfigurationException);
        assertEquals(0, server.getRequestCount());
    }

    @Test public void configuredHandlerStillReceivesDeletion() throws Exception {
        CookieManager manager = new CookieManager();
        CookieHandler.setDefault(manager);
        manager.put(server.url("/").uri(), java.util.Collections.singletonMap("Set-Cookie",
                java.util.Collections.singletonList("sAccessToken=old; Path=/")));
        SessionSnapshot original = install("stored", "before");
        server.enqueue(revoked());
        SuperTokens.resolveAccessToken(context, original.getOwner(), true, null);
        assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
        assertTrue(manager.getCookieStore().getCookies().isEmpty());
    }
}
