package com.supertokens.session;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class CustomAuthorizationTest extends SessionTestFixture {
    private int custom(boolean url, String auth, SessionRequestTag tag) throws Exception {
        if (url) {
            HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(server.url("/protected").url(),
                    con -> con.setRequestProperty("Authorization", auth), tag);
            try { return connection.getResponseCode(); } finally { connection.disconnect(); }
        }
        Request.Builder request = new Request.Builder().url(server.url("/protected")).header("Authorization", auth);
        if (tag != null) request.tag(SessionRequestTag.class, tag);
        try (Response response = client.newCall(request.build()).execute()) { return response.code(); }
    }

    private RecordedRequest wire() throws Exception {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        return request;
    }

    @Test public void customAuthorizationParticipatesInRefreshRetryAndSavingBothTransports() throws Exception {
        for (boolean url : new boolean[]{false, true}) for (boolean tagged : new boolean[]{false, true}) {
            for (String auth : new String[]{"Basic dXNlcjpwYXNz", "Custom caller-value"}) {
                SessionSnapshot before = install("custom", "before");
                SessionTokens refreshed = tokens("custom", "refreshed"), saved = tokens("custom", "saved");
                server.enqueue(new MockResponse().setResponseCode(401));
                server.enqueue(rotation(refreshed).addHeader("anti-csrf", refreshed.getAntiCSRF()));
                server.enqueue(promotion(saved));
                assertEquals(200, custom(url, auth, tagged ? SuperTokens.captureRequestTag(context) : null));
                RecordedRequest first = wire(), refresh = wire(), retry = wire();
                assertEquals(auth, first.getHeader("Authorization"));
                assertEquals(auth, retry.getHeader("Authorization"));
                assertEquals("anti-csrf", first.getHeader("rid"));
                assertEquals(before.getTokens().getAntiCSRF(), first.getHeader("anti-csrf"));
                assertEquals(refreshed.getAntiCSRF(), retry.getHeader("anti-csrf"));
                assertEquals("/auth/session/refresh", refresh.getPath());
                assertEquals("Bearer " + before.getTokens().getRefreshToken(), refresh.getHeader("Authorization"));
                assertEquals(saved.getAccessToken(), SuperTokens.getAccessToken(context));
                assertSame(before.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            }
        }
    }

    @Test public void foreignBearerAndDownstreamCustomReplacementCannotMutateSession() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            SessionSnapshot before = install("foreign", "before");
            server.enqueue(rotation(tokens("foreign", "untrusted")).setResponseCode(401));
            assertEquals(401, custom(url, "Bearer caller-token", null));
            assertEquals("Bearer caller-token", wire().getHeader("Authorization"));
            assertSame(before, SuperTokens.getSessionSnapshot(context));
        }
        for (boolean tagged : new boolean[]{false, true}) {
            SessionSnapshot before = install("downstream", "before");
            OkHttpClient original = client;
            client = original.newBuilder().addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                    .header("Authorization", "Basic replaced").build())).build();
            server.enqueue(rotation(tokens("downstream", "untrusted")));
            try {
                try {
                    assertEquals(200, custom(false, "Basic expected", tagged ? SuperTokens.captureRequestTag(context) : null));
                    assertFalse("Tagged replacement must fail", tagged);
                } catch (java.io.IOException expected) { assertTrue(tagged); }
                assertEquals("Basic replaced", wire().getHeader("Authorization"));
                assertSame(before, SuperTokens.getSessionSnapshot(context));
            } finally { client = original; }
        }
    }

    @Test public void exposedCookieAccessDoesNotBecomeManagedBearerAndCustomStillParticipates() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
            assertTrue(new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("cookie").reconfigure().isSuccess());
            SessionTokens login = tokens("cookie-custom", "before");
            server.enqueue(promotion(login).addHeader("anti-csrf", login.getAntiCSRF()));
            assertEquals(200, request(url, null, "/login"));
            wire();
            SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
            assertNull(before.getTokens());
            server.enqueue(new MockResponse().setResponseCode(401).addHeader("front-token", "remove"));
            assertEquals(401, custom(url, "Bearer " + login.getAccessToken(), null));
            wire();
            assertSame(before, SuperTokens.getSessionSnapshot(context));
            SessionTokens after = tokens("cookie-custom", "after");
            server.enqueue(promotion(after));
            assertEquals(200, custom(url, "Basic cookie-caller", null));
            RecordedRequest sent = wire();
            assertEquals("Basic cookie-caller", sent.getHeader("Authorization"));
            assertEquals(login.getAntiCSRF(), sent.getHeader("anti-csrf"));
            assertEquals(after.getAccessToken(), SuperTokens.getAccessToken(context));
            assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
        }
    }

    @Test public void staleDispatchPreservesCallerAuthButStripsSessionAntiCsrfBothTransports() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            install("stale", "before");
            server.enqueue(new MockResponse().setBody("ok").addHeader("front-token", "remove"));
            if (url) {
                HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(server.url("/protected").url(), con -> {
                    con.setRequestProperty("Authorization", "Basic caller");
                    try { install("replacement", "after"); } catch (Exception e) { throw new AssertionError(e); }
                });
                try { assertEquals(200, connection.getResponseCode()); } finally { connection.disconnect(); }
            } else {
                SuperTokensInterceptor interceptor = new SuperTokensInterceptor();
                SessionTransport.beforeDispatchForTests = () -> {
                    try { install("replacement", "after"); } catch (Exception e) { throw new AssertionError(e); }
                };
                OkHttpClient original = client;
                client = new OkHttpClient.Builder().addInterceptor(interceptor).build();
                try { assertEquals(200, custom(false, "Basic caller", null)); }
                finally {
                    SessionTransport.beforeDispatchForTests = null;
                    client.connectionPool().evictAll();
                    client.dispatcher().executorService().shutdownNow();
                    client = original;
                }
            }
            RecordedRequest sent = wire();
            assertEquals("Basic caller", sent.getHeader("Authorization"));
            assertTrue(sent.getHeader("anti-csrf") == null || sent.getHeader("anti-csrf").isEmpty());
            assertEquals("replacement", SuperTokens.getSessionSnapshot(context).getSessionHandle());
        }
    }
}
