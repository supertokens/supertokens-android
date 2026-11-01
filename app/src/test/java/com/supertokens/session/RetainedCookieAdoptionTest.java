package com.supertokens.session;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
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
import static org.mockito.Mockito.*;

public class RetainedCookieAdoptionTest extends SessionTestFixture {
    private SuperTokensPersistentCookieStore retainedJar() throws Exception {
        TestPreferences cookiePreferences = new TestPreferences();
        when(context.getSharedPreferences(eq("supertokens-cookie-shared-prefs"), anyInt()))
                .thenReturn(cookiePreferences.preferences);
        SuperTokensPersistentCookieStore store = new SuperTokensPersistentCookieStore(context);
        HttpCookie cookie = new HttpCookie("sAccessToken", "retained-a");
        cookie.setPath("/"); cookie.setMaxAge(3600);
        store.add(server.url("/").uri(), cookie);
        // Cookie persistence uses apply(); model completion before restart, not crash-safe immediate durability.
        cookiePreferences.flush();
        cookiePreferences.restart();
        SuperTokensPersistentCookieStore restored = new SuperTokensPersistentCookieStore(context);
        assertEquals("retained-a", restored.getCookies().get(0).getValue());
        CookieHandler.setDefault(new CookieManager(restored, CookiePolicy.ACCEPT_ALL));
        return restored;
    }

    private MockResponse front(String user) throws Exception {
        return new MockResponse().setBody("OK").addHeader("front-token",
                tokens("handle-" + user, user, 4102444800000L, 4102444800000L, 5).getFrontToken());
    }

    @Test public void clearRetainsJarButRequiresFreshResponseForAdoption() throws Exception { retained(false); }
    @Test public void reconfigureRetainsJarButRejectsOldOwnerResponse() throws Exception { retained(true); }

    private void retained(boolean reconfigure) throws Exception {
        SuperTokensPersistentCookieStore jar = retainedJar();
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .tokenTransferMethod("cookie").reconfigure().isSuccess());
        server.enqueue(front("a"));
        assertEquals(200, request(true, null, "/login"));
        SessionSnapshot old = SuperTokens.getSessionSnapshot(context);
        assertEquals("a", old.getUserId());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        MockResponse oldResponse = front("a");
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                assertTrue(request.getHeader("Cookie").contains("retained-a"));
                if (request.getPath().equals("/pending")) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(504);
                    return oldResponse;
                }
                return new MockResponse().setBody("OK");
            }
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> pending = worker.submit(() -> request(true, null, "/pending"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            if (reconfigure) assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                    .tokenTransferMethod("cookie").eventHandler(event -> {}).reconfigure().isSuccess());
            else assertTrue(SuperTokens.clearSession(context, old.getOwner()).isSuccess());
            SessionSnapshot absent = SuperTokens.getSessionSnapshot(context);
            assertEquals(SessionSnapshot.Status.ABSENT, absent.getStatus());
            assertFalse(old.isCurrent());
            assertFalse(SuperTokens.doesSessionExist(context));
            assertEquals("retained-a", jar.getCookies().get(0).getValue());
            release.countDown();
            assertEquals(200, (int) pending.get(5, TimeUnit.SECONDS));
            assertSame(absent, SuperTokens.getSessionSnapshot(context));
            assertEquals(200, request(true, null, "/plain"));
            assertSame(absent, SuperTokens.getSessionSnapshot(context));
            // Endpoint names are not an adoption gate: a fresh in-scope response with session headers can adopt A.
            server.setDispatcher(new okhttp3.mockwebserver.QueueDispatcher());
            server.enqueue(front("a"));
            SessionRequestTag fresh = SuperTokens.captureRequestTag(context);
            assertEquals(200, request(true, fresh, "/fresh-response"));
            assertNotNull(fresh.getAcceptedSnapshot());
            assertEquals("a", fresh.getAcceptedSnapshot().getUserId());
            assertNotSame(old.getOwner(), fresh.getAcceptedSnapshot().getOwner());
            assertNotEquals(old.getAdoptionId(), fresh.getAcceptedSnapshot().getAdoptionId());
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test public void cookieModeWithoutCookieHandlerRejectsBeforeWireAndRetainsState() throws Exception {
        retainedJar();
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .tokenTransferMethod("cookie").reconfigure().isSuccess());
        server.enqueue(front("a"));
        assertEquals(200, request(true, null, "/login"));
        server.takeRequest(1, TimeUnit.SECONDS);
        SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
        CookieHandler.setDefault(null);
        try {
            request(true, null, "/plain");
            fail("Cookie URLConnection requires a CookieManager");
        } catch (IllegalAccessException expected) {
            assertEquals("Please initialise a CookieManager with a SuperTokensPersistentCookieStore", expected.getMessage());
        }
        assertEquals(1, server.getRequestCount());
        assertSame(before, SuperTokens.getSessionSnapshot(context));
    }
}
