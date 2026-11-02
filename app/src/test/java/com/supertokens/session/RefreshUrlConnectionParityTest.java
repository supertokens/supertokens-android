package com.supertokens.session;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;
import static org.junit.Assert.*;

public class RefreshUrlConnectionParityTest extends SessionTestFixture {
    @Test public void legacyRefreshFollowsSameOriginRedirect() throws Exception {
        SessionSnapshot snapshot = install("session", "before");
        server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", "/redirected"));
        server.enqueue(rotation(tokens("session", "after")));
        SessionResponse response = SuperTokensHttpURLConnection.refresh(context,
                SessionState.capture().configuration, snapshot, true);
        assertEquals(200, response.code);
        assertEquals("POST", server.takeRequest().getMethod());
        assertEquals("/redirected", server.takeRequest().getPath());
    }

    @Test public void refreshNeverSendsCredentialsToCrossOriginRedirect() throws Exception {
        SessionSnapshot snapshot = install("session", "before");
        try (MockWebServer foreign = new MockWebServer()) {
            foreign.start();
            server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", foreign.url("/stolen")));
            try {
                SuperTokensHttpURLConnection.refresh(context, SessionState.capture().configuration, snapshot, true);
                fail("cross-origin redirect accepted");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("outside captured session scope"));
            }
            assertEquals(0, foreign.getRequestCount());
        }
    }

    @Test public void legacyRefreshHasNoTenSecondDeadline() throws Exception {
        SessionSnapshot snapshot = install("session", "before");
        server.enqueue(rotation(tokens("session", "after")).setHeadersDelay(11, TimeUnit.SECONDS));
        assertEquals(200, SuperTokensHttpURLConnection.refresh(context,
                SessionState.capture().configuration, snapshot, true).code);
    }

    @Test public void legacyGetterFollowsRedirectWithoutTenSecondDeadline() throws Exception {
        long expired = System.currentTimeMillis() - 60000;
        SessionTokens old = tokens("session", "user", expired, expired, 5);
        SuperTokens.resetForTests();
        seedLegacy(old);
        initializeSession();
        SessionTokens refreshed = tokens("session", "after");
        server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", "/redirected"));
        server.enqueue(rotation(refreshed).setHeadersDelay(11, TimeUnit.SECONDS));

        assertEquals(refreshed.getAccessToken(), SuperTokens.getAccessToken(context));
        assertEquals("/auth/session/refresh", server.takeRequest().getPath());
        assertEquals("/redirected", server.takeRequest().getPath());
    }

    @Test public void strictRefreshRetainsDeadline() throws Exception {
        SessionSnapshot snapshot = install("session", "before");
        server.enqueue(rotation(tokens("session", "after")).setHeadersDelay(11, TimeUnit.SECONDS));
        try {
            SuperTokensHttpURLConnection.refresh(context, SessionState.capture().configuration, snapshot, false);
            fail("strict refresh exceeded deadline");
        } catch (IOException expected) {
            assertNotNull(expected);
        }
    }

    @Test public void replacedOwnerCannotFollowRedirect() throws Exception {
        SessionSnapshot snapshot = install("session", "before");
        java.net.CookieHandler previous = java.net.CookieHandler.getDefault();
        java.util.List<java.net.URI> cookieWrites = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.net.CookieHandler.setDefault(new java.net.CookieHandler() {
            @Override public java.util.Map<String, java.util.List<String>> get(java.net.URI uri,
                    java.util.Map<String, java.util.List<String>> headers) {
                return java.util.Collections.emptyMap();
            }
            @Override public void put(java.net.URI uri, java.util.Map<String, java.util.List<String>> headers) {
                cookieWrites.add(uri);
            }
        });
        server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", "/redirected")
                .addHeader("Set-Cookie", "probe=stale; Path=/manual-only")
                .setHeadersDelay(1, TimeUnit.SECONDS));
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> refresh = executor.submit(() -> {
                try {
                    SuperTokensHttpURLConnection.refresh(context, SessionState.capture().configuration, snapshot, true);
                    fail("stale refresh followed redirect");
                } catch (IOException expected) {
                    assertTrue(expected instanceof StaleSessionException);
                }
            });
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
            install("replacement", "after");
            refresh.get(5, TimeUnit.SECONDS);
            assertEquals(1, server.getRequestCount());
            for (java.net.URI uri : cookieWrites) assertNotEquals("/manual-only", uri.getPath());
        } finally {
            executor.shutdownNow();
            java.net.CookieHandler.setDefault(previous);
        }
    }

}
