package com.supertokens.session;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
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

    @Test public void d12AllowsOnlySameHostDefaultPortUpgrade() throws Exception {
        new SuperTokens.Builder(context, "http://example.com/base").reconfigure();
        SessionState.Configuration config = SessionState.configuration();
        for (String sent : new String[]{"http://example.com/a", "http://example.com:80/a"}) {
            for (String received : new String[]{"https://example.com/b", "https://EXAMPLE.com:443/b"}) {
                assertTrue(SessionTransport.isResponseInScope(sent, received, config));
            }
        }
        assertFalse(SessionTransport.isResponseInScope("http://example.com:8080/a", "https://example.com/b", config));
        assertFalse(SessionTransport.isResponseInScope("http://example.com/a", "https://example.com:444/b", config));
        assertFalse(SessionTransport.isResponseInScope("http://example.com/a", "https://other.example.com/b", config));
        assertFalse(SessionTransport.isResponseInScope("https://example.com/a", "http://example.com/b", config));
        assertFalse(SessionTransport.isResponseInScope("http://example.com/a", "https://user@example.com/b", config));
        assertFalse(SessionTransport.isResponseInScope("http://other.example.com/a", "https://other.example.com/b", config));
    }

    @Test public void d12RedirectedOkHttpResponseCanAdoptTokens() throws Exception {
        new SuperTokens.Builder(context, "http://example.com/").tokenTransferMethod("header").reconfigure();
        SessionTokens accepted = tokens("upgrade", "accepted");
        OkHttpClient redirected = client.newBuilder().addInterceptor(chain -> {
            Request sent = chain.request();
            Request destination = sent.newBuilder().url("https://example.com/callback").build();
            Response redirect = new Response.Builder().request(sent).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(302).message("Found").header("Location", destination.url().toString()).build();
            return new Response.Builder().request(destination).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK").priorResponse(redirect)
                    .header("front-token", accepted.getFrontToken())
                    .header("st-access-token", accepted.getAccessToken())
                    .header("st-refresh-token", accepted.getRefreshToken())
                    .body(okhttp3.ResponseBody.create(null, "ok")).build();
        }).build();
        SessionRequestTag tag = SuperTokens.captureRequestTag(context);
        try (Response response = redirected.newCall(new Request.Builder().url("http://example.com/start")
                .tag(SessionRequestTag.class, tag).build()).execute()) {
            assertEquals(200, response.code());
        }
        assertEquals(accepted.getAccessToken(), SuperTokens.getAccessToken(context));
        assertNotNull(tag.getAcceptedSnapshot());
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
