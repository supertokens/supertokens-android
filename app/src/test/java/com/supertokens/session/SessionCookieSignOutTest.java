package com.supertokens.session;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionCookieSignOutTest extends SessionTestFixture {
    private CookieHandler previousCookies;
    private SessionSnapshot initial;

    @Before public void cookieSession() throws Exception {
        previousCookies = CookieHandler.getDefault();
        CookieHandler.setDefault(new CookieManager());
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .tokenTransferMethod("cookie").maxRetryAttemptsForSessionRefresh(1).reconfigure().isSuccess());
        server.enqueue(cookieResponse("before"));
        assertEquals(200, request(true, null, "/login"));
        server.takeRequest(1, TimeUnit.SECONDS);
        initial = SuperTokens.getSessionSnapshot(context);
        assertNull(initial.getTokens());
        assertEquals(SessionSnapshot.Status.AVAILABLE, initial.getStatus());
    }

    @After public void restoreCookies() { CookieHandler.setDefault(previousCookies); }

    @Test public void rejectedCookieRefreshesAndSignsOut() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(cookieResponse("after"));
        server.enqueue(signedOut());
        SuperTokens.signOut(context);
        assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        assertWire("/auth/signout", "before");
        assertWire("/auth/session/refresh", "before");
        assertWire("/auth/signout", "after");
        assertEquals(4, server.getRequestCount());
    }

    @Test public void repeatedCookieRejectionHonorsBound() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(cookieResponse("after"));
        server.enqueue(new MockResponse().setResponseCode(401));
        try { SuperTokens.signOut(context); fail("bound ignored"); }
        catch (IllegalAccessException expected) { assertTrue(expected.getMessage().contains("maximum session refresh limit")); }
        assertWire("/auth/signout", "before");
        assertWire("/auth/session/refresh", "before");
        assertWire("/auth/signout", "after");
        assertSame(initial.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
        assertEquals(4, server.getRequestCount());
    }

    @Test public void expiredRefreshClearsWithoutRetryingSignOut() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("front-token", "remove"));
        SuperTokens.signOut(context);
        assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        assertEquals(3, server.getRequestCount());
        assertWire("/auth/signout", "before");
        assertWire("/auth/session/refresh", "before");
    }

    @Test public void delayedRejectionReusesCompletedRefresh() throws Exception { overlap(false, null); }
    @Test public void rejectionJoinsInFlightRefresh() throws Exception { overlap(true, null); }
    @Test public void replacementDuringRefreshCancelsSignOut() throws Exception { overlap(true, "replace"); }
    @Test public void clearDuringRefreshCancelsSignOut() throws Exception { overlap(true, "clear"); }
    @Test public void reconfigurationDuringRefreshCancelsSignOut() throws Exception { overlap(true, "config"); }

    private void overlap(boolean holdRefresh, String cancellation) throws Exception {
        CountDownLatch signoutArrived = new CountDownLatch(1), releaseSignout = new CountDownLatch(1);
        CountDownLatch refreshArrived = new CountDownLatch(1), releaseRefresh = new CountDownLatch(1);
        AtomicInteger signouts = new AtomicInteger(), refreshes = new AtomicInteger();
        MockResponse refreshed = cookieResponse("after");
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (request.getPath().equals("/auth/session/refresh")) {
                    refreshes.incrementAndGet(); refreshArrived.countDown();
                    if (holdRefresh && !releaseRefresh.await(5, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(504);
                    return refreshed;
                }
                if (signouts.incrementAndGet() == 1) {
                    signoutArrived.countDown();
                    if (!releaseSignout.await(5, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(504);
                    return new MockResponse().setResponseCode(401);
                }
                return signedOut();
            }
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> logoutThread = new AtomicReference<>();
        try {
            Future<?> logout = workers.submit(() -> {
                logoutThread.set(Thread.currentThread());
                try { SuperTokens.signOut(context); if (cancellation != null) fail("retired owner succeeded"); }
                catch (StaleSessionException expected) { assertNotNull(cancellation); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            assertTrue(signoutArrived.await(3, TimeUnit.SECONDS));
            Future<SessionResolution> refresh = workers.submit(() -> SuperTokens.resolveAccessToken(context,
                    initial.getOwner(), true, null));
            assertTrue(refreshArrived.await(3, TimeUnit.SECONDS));
            if (!holdRefresh) assertTrue(refresh.get(3, TimeUnit.SECONDS).isSuccess());
            SessionSnapshot replacement = null;
            if (cancellation != null) {
                if (cancellation.equals("replace")) replacement = install("B", "replacement");
                else if (cancellation.equals("clear")) replacement = SuperTokens.clearSession(context, initial.getOwner()).getSnapshot();
                else replacement = new SuperTokens.Builder(context, server.url("/").toString())
                        .apiBasePath("/other").tokenTransferMethod("cookie").reconfigure().getSnapshot();
            }
            releaseSignout.countDown();
            if (holdRefresh && cancellation == null) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                boolean joined = false;
                while (!joined && System.nanoTime() < deadline) {
                    for (StackTraceElement frame : logoutThread.get().getStackTrace()) {
                        if (frame.getClassName().equals(RefreshCoordinator.Flight.class.getName())
                                && frame.getMethodName().equals("await")) joined = true;
                    }
                    if (!joined) Thread.sleep(5);
                }
                assertTrue("signout must join the existing cookie refresh flight", joined);
            }
            releaseRefresh.countDown();
            SessionResolution resolved = refresh.get(3, TimeUnit.SECONDS);
            logout.get(3, TimeUnit.SECONDS);
            assertEquals(1, refreshes.get());
            assertEquals(cancellation == null ? 2 : 1, signouts.get());
            if (replacement != null) {
                assertEquals(SessionResolution.Status.STALE_OWNER, resolved.getStatus());
                assertSame(replacement.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
                assertEquals(replacement.getRevision(), SuperTokens.getSessionSnapshot(context).getRevision());
            } else assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        } finally {
            releaseSignout.countDown(); releaseRefresh.countDown(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private MockResponse cookieResponse(String value) throws Exception {
        return new MockResponse().addHeader("front-token", tokens("cookie", value).getFrontToken())
                .addHeader("Set-Cookie", "sAccessToken=" + value + "; Path=/");
    }

    private void assertWire(String path, String cookie) throws Exception {
        RecordedRequest wire = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(wire);
        assertEquals(path, wire.getPath());
        assertNull(wire.getHeader("Authorization"));
        assertTrue(wire.getHeader("Cookie").contains("sAccessToken=" + cookie));
    }

    private static MockResponse signedOut() {
        return new MockResponse().addHeader("front-token", "remove").setBody("{\"status\":\"OK\"}");
    }
}
