package com.supertokens.session;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.util.concurrent.TimeUnit;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;
import static org.junit.Assert.*;

public class InterceptionScopeTest extends SessionTestFixture {
    @Test public void directOtherPortCanDispatchCredentialsAndRefreshUnderD1() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            for (boolean tagged : new boolean[]{false, true}) {
                SessionSnapshot before = install("direct", "before");
                SessionRequestTag tag = tagged ? SuperTokens.captureRequestTag(context) : null;
                try (MockWebServer direct = new MockWebServer()) {
                    direct.start();
                    direct.enqueue(new MockResponse().setResponseCode(401));
                    server.enqueue(rotation(tokens("direct", "refreshed")));
                    direct.enqueue(new MockResponse().setBody("ok"));
                    if (url) {
                        java.net.HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(
                                direct.url("/other-path").url(), c -> {}, tag);
                        assertEquals(200, connection.getResponseCode());
                        connection.disconnect();
                    } else {
                        try (Response response = client.newCall(new Request.Builder().url(direct.url("/other-path"))
                                .tag(SessionRequestTag.class, tag).build()).execute()) {
                            assertEquals(200, response.code());
                        }
                    }
                    assertEquals("Bearer " + before.getTokens().getAccessToken(),
                            direct.takeRequest(2, TimeUnit.SECONDS).getHeader("Authorization"));
                    assertEquals("Bearer " + tokens("direct", "refreshed").getAccessToken(),
                            direct.takeRequest(2, TimeUnit.SECONDS).getHeader("Authorization"));
                    assertEquals("/auth/session/refresh", server.takeRequest(2, TimeUnit.SECONDS).getPath());
                    assertSame(before.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
                }
            }
        }
    }

    @Test public void legacyRefreshResponseMustMatchRefreshRequestOrigin() throws Exception {
        SessionSnapshot before = install("refresh", "before");
        try (MockWebServer other = new MockWebServer()) {
            other.start();
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", other.url("/refresh")));
            other.enqueue(rotation(tokens("refresh", "foreign")));
            try { request(false, null, "/profile"); fail("cross-origin refresh response"); }
            catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("outside captured session scope"));
            }
            assertSame(before, SuperTokens.getSessionSnapshot(context));
            assertEquals(2, server.getRequestCount());
            assertEquals(1, other.getRequestCount());
        }
    }

    @Test public void legacyRefreshSameOriginOutsideDomainPathCanRotateUnderD4() throws Exception {
        assertTrue(new SuperTokens.Builder(context, server.url("/managed").toString()).reconfigure().isSuccess());
        SessionSnapshot before = install("refresh", "before");
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/outside/refresh"));
        server.enqueue(rotation(tokens("refresh", "after")));
        server.enqueue(new MockResponse());
        assertEquals(200, request(false, null, "/profile"));
        assertSame(before.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
        assertEquals(tokens("refresh", "after").getAccessToken(), SuperTokens.getAccessToken(context));
        assertEquals(4, server.getRequestCount());
    }

    @Test public void responseFenceUsesSentOriginAndEffectivePortsNotConfiguredOriginOrPath() throws Exception {
        new SuperTokens.Builder(context, "https://example.com/base").reconfigure();
        SessionState.Configuration config = SessionState.configuration();
        assertTrue(SessionTransport.isResponseInScope("http://example.com:8080/a", "http://example.com:8080/b", config));
        assertTrue(SessionTransport.isResponseInScope("https://example.com/a", "https://example.com:443/elsewhere", config));
        assertFalse(SessionTransport.isResponseInScope("https://example.com/a", "http://example.com/a", config));
        assertFalse(SessionTransport.isResponseInScope("https://example.com/a", "https://example.com:444/a", config));
        assertFalse(SessionTransport.isResponseInScope("https://example.com/a", "https://other.example.com/a", config));
    }

    @Test public void manualCookieProjectionRejectsChangedOriginEvenWhenBothHostsAreTrusted() throws Exception {
        CookieManager cookies = new CookieManager();
        CookieHandler.setDefault(cookies);
        java.net.URL sent = server.url("/original").url();
        java.net.URL destination = new java.net.URL("http://localhost:" + (server.getPort() + 1) + "/final");
        java.net.HttpURLConnection connection = new java.net.HttpURLConnection(destination) {
            @Override public java.util.Map<String, java.util.List<String>> getHeaderFields() {
                return java.util.Collections.singletonMap("Set-Cookie", java.util.Collections.singletonList("manual=blocked; Path=/"));
            }
            @Override public void connect() {}
            @Override public void disconnect() {}
            @Override public boolean usingProxy() { return false; }
        };
        SuperTokensHttpURLConnection.manuallySetCookiesFromResponse(sent, connection, SessionState.configuration());
        assertTrue(cookies.getCookieStore().getCookies().isEmpty());
    }
}
