package com.supertokens.session;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionSignOutRedirectTest extends SessionTestFixture {
    @Test public void platformCookieManagerStoresCookieBeforeRedirectRejection() throws Exception {
        CookieHandler previous = CookieHandler.getDefault();
        CookieManager store = new CookieManager();
        CookieHandler.setDefault(store);
        try (MockWebServer foreign = new MockWebServer()) {
            foreign.start();
            install("A", "initial");
            server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", foreign.url("/outside"))
                    .addHeader("Set-Cookie", "ambient=redirect; Path=/"));
            try { SuperTokens.signOut(context); fail(); } catch (IOException expected) { }
            assertEquals(0, foreign.getRequestCount());
            assertTrue(store.getCookieStore().getCookies().stream()
                    .anyMatch(cookie -> cookie.getName().equals("ambient") && cookie.getValue().equals("redirect")));
        } finally {
            CookieHandler.setDefault(previous);
        }
    }

    @Test public void platformCookieManagerStoresCookieBeforeStaleOwnerRejection() throws Exception {
        CookieHandler previous = CookieHandler.getDefault();
        CookieManager store = new CookieManager();
        CookieHandler.setDefault(store);
        CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            install("A", "initial");
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    arrived.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return new MockResponse().setResponseCode(307).addHeader("Location", "/auth/done")
                            .addHeader("Set-Cookie", "ambient=stale; Path=/");
                }
            });
            Future<?> pending = worker.submit(() -> {
                try { SuperTokens.signOut(context); fail(); }
                catch (StaleSessionException expected) { }
                catch (Exception error) { throw new AssertionError(error); }
            });
            assertTrue(arrived.await(3, TimeUnit.SECONDS));
            SessionSnapshot replacement = install("B", "replacement");
            release.countDown();
            pending.get(5, TimeUnit.SECONDS);
            assertSame(replacement.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            assertEquals(1, server.getRequestCount());
            assertTrue(store.getCookieStore().getCookies().stream()
                    .anyMatch(cookie -> cookie.getName().equals("ambient") && cookie.getValue().equals("stale")));
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            CookieHandler.setDefault(previous);
        }
    }

    @Test public void redirectAppliesPathCookieBeforeNextWire() throws Exception {
        CookieHandler previous = CookieHandler.getDefault();
        CookieManager store = new CookieManager();
        CookieHandler.setDefault(new CookieHandler() {
            @Override public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers)
                    throws IOException { return store.get(uri, headers); }
            @Override public void put(URI uri, Map<String, List<String>> headers) throws IOException {
                if (uri.getPath().startsWith("/auth/done")) store.put(uri, headers);
            }
        });
        try {
            install("A", "initial");
            server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", "/auth/done")
                    .addHeader("Set-Cookie", "redirect_cookie=needed; Path=/auth/done"));
            server.enqueue(new MockResponse().addHeader("front-token", "remove").setBody("{\"status\":\"OK\"}"));
            SuperTokens.signOut(context);
            server.takeRequest(1, TimeUnit.SECONDS);
            RecordedRequest next = server.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(next.getHeader("Cookie"));
            assertTrue(next.getHeader("Cookie").contains("redirect_cookie=needed"));
        } finally {
            CookieHandler.setDefault(previous);
        }
    }

    @Test public void rejectedRedirectDoesNotManuallyApplyPathCookie() throws Exception {
        CookieHandler previous = CookieHandler.getDefault();
        CookieManager store = new CookieManager();
        CookieHandler.setDefault(new CookieHandler() {
            @Override public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers)
                    throws IOException { return store.get(uri, headers); }
            @Override public void put(URI uri, Map<String, List<String>> headers) throws IOException {
                if (uri.getPath().startsWith("/auth/done")) store.put(uri, headers);
            }
        });
        try (MockWebServer foreign = new MockWebServer()) {
            foreign.start();
            install("A", "initial");
            server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", foreign.url("/auth/done"))
                    .addHeader("Set-Cookie", "redirect_cookie=unwanted; Path=/auth/done"));
            try { SuperTokens.signOut(context); fail(); } catch (IOException expected) { }
            assertTrue(store.getCookieStore().getCookies().isEmpty());
            assertEquals(0, foreign.getRequestCount());
        } finally {
            CookieHandler.setDefault(previous);
        }
    }

    @Test public void redirectMethodsAndRemoval() throws Exception {
        for (int code : new int[]{301, 302, 303, 307, 308}) {
            SessionSnapshot before = install("A", "redirect" + code);
            server.enqueue(new MockResponse().setResponseCode(code).addHeader("Location", "/auth/done")
                    .addHeader("front-token", "remove"));
            server.enqueue(new MockResponse().addHeader("front-token", "remove").setBody("{\"status\":\"OK\"}"));
            SuperTokens.signOut(context);
            RecordedRequest first = server.takeRequest(1, TimeUnit.SECONDS);
            RecordedRequest second = server.takeRequest(1, TimeUnit.SECONDS);
            assertEquals("POST", first.getMethod());
            assertEquals(code == 307 || code == 308 ? "POST" : "GET", second.getMethod());
            assertEquals("Bearer " + before.getTokens().getAccessToken(), second.getHeader("Authorization"));
            assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        }
    }

    @Test public void foreignOriginNeverReceivesCredentialsOrRemoval() throws Exception {
        SessionSnapshot before = install("A", "initial");
        try (MockWebServer foreign = new MockWebServer()) {
            foreign.start();
            server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", foreign.url("/auth/done"))
                    .addHeader("front-token", "remove"));
            try { SuperTokens.signOut(context); fail(); } catch (IOException expected) { }
            assertEquals(0, foreign.getRequestCount());
            assertSame(before.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            assertEquals(before.getRevision(), SuperTokens.getSessionSnapshot(context).getRevision());
        }
    }

    @Test public void redirectedRemovalRequiresValidSuccessBody() throws Exception {
        SessionSnapshot before = install("A", "initial");
        server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", "/auth/done"));
        server.enqueue(new MockResponse().addHeader("front-token", "remove")
                .setBody("{\"status\":\"GENERAL_ERROR\",\"message\":\"failed\"}"));
        try { SuperTokens.signOut(context); fail(); } catch (SuperTokensGeneralError expected) { }
        assertEquals(before.getRevision(), SuperTokens.getSessionSnapshot(context).getRevision());
    }

    @Test public void redirectedExpiryRemovalDoesNotRefresh() throws Exception {
        install("A", "initial");
        server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", "/auth/done"));
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("front-token", "remove"));
        SuperTokens.signOut(context);
        assertEquals(2, server.getRequestCount());
        assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
    }

    @Test public void outsidePathAndEncodedTraversalRejected() throws Exception {
        assertTrue(new SuperTokens.Builder(context, server.url("/managed").toString()).reconfigure().isSuccess());
        SessionSnapshot before = install("A", "initial");
        for (String path : new String[]{"/outside", "/managed/%2e%2e/outside"}) {
            server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", path));
            try { SuperTokens.signOut(context); fail(); } catch (IOException expected) { }
        }
        assertEquals(2, server.getRequestCount());
        assertEquals(before.getRevision(), SuperTokens.getSessionSnapshot(context).getRevision());
    }

    @Test public void redirectLoopIsBoundedAndCannotRemove() throws Exception {
        SessionSnapshot before = install("A", "initial");
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(307).addHeader("Location", "/auth/signout")
                        .addHeader("front-token", "remove");
            }
        });
        try { SuperTokens.signOut(context); fail(); } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("redirect limit"));
        }
        assertEquals(21, server.getRequestCount());
        assertEquals(before.getRevision(), SuperTokens.getSessionSnapshot(context).getRevision());
    }

    @Test public void replacementDuringRedirectStopsNextWire() throws Exception {
        install("A", "initial");
        CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                arrived.countDown();
                release.await(5, TimeUnit.SECONDS);
                return new MockResponse().setResponseCode(307).addHeader("Location", "/auth/done")
                        .addHeader("front-token", "remove");
            }
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> pending = worker.submit(() -> {
                try { SuperTokens.signOut(context); fail(); }
                catch (StaleSessionException expected) { }
                catch (Exception e) { throw new AssertionError(e); }
            });
            assertTrue(arrived.await(3, TimeUnit.SECONDS));
            SessionSnapshot replacement = install("B", "replacement");
            release.countDown();
            pending.get(5, TimeUnit.SECONDS);
            assertSame(replacement.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            assertEquals(1, server.getRequestCount());
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
