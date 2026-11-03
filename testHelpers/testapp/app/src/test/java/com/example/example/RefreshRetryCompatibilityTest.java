package com.example.example;

import android.content.Context;
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensHttpURLConnection;
import com.supertokens.session.SuperTokensInterceptor;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mockito;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class RefreshRetryCompatibilityTest {
    @Parameterized.Parameters(name = "{0}") public static Collection<Object[]> transports() {
        return Arrays.asList(new Object[][] {{"okhttp"}, {"urlconnection"}});
    }

    private final String transport;
    private MockWebServer server;
    private OkHttpClient client;
    private CookieHandler cookies;
    private Context context;
    private org.mockito.MockedStatic<android.util.Base64> androidBase64;

    public RefreshRetryCompatibilityTest(String transport) { this.transport = transport; }

    @Before public void setup() throws Exception {
        SuperTokens.resetForTests();
        androidBase64 = Mockito.mockStatic(android.util.Base64.class);
        androidBase64.when(() -> android.util.Base64.decode(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(invocation -> java.util.Base64.getDecoder().decode((String) invocation.getArgument(0)));
        cookies = CookieHandler.getDefault();
        CookieHandler.setDefault(null);
        context = Mockito.mock(Context.class);
        Mockito.when(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(new SPMockBuilder().createSharedPreferences());
        server = new MockWebServer();
        server.start();
        new SuperTokens.Builder(context, server.url("/").toString())
                .maxRetryAttemptsForSessionRefresh(1)
                .customHeaderProvider(type -> Collections.singletonMap("X-Refresh-Only", "provided"))
                .build();
        client = new OkHttpClient.Builder().addInterceptor(new SuperTokensInterceptor())
                .readTimeout(5, TimeUnit.SECONDS).build();
    }

    @After public void cleanup() throws Exception {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
        server.shutdown();
        SuperTokens.resetForTests();
        CookieHandler.setDefault(cookies);
        androidBase64.close();
    }

    private MockResponse tokens(String prefix) {
        String front = java.util.Base64.getEncoder().encodeToString(
                "{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{}}".getBytes(StandardCharsets.UTF_8));
        return new MockResponse().setHeader("st-access-token", prefix + "-access")
                .setHeader("st-refresh-token", prefix + "-refresh")
                .setHeader("front-token", front).setHeader("anti-csrf", prefix + "-csrf");
    }

    private int request(String path, boolean post) throws Exception {
        if (transport.equals("okhttp")) {
            Request.Builder builder = new Request.Builder().url(server.url(path)).header("X-Caller", "caller");
            if (post) builder.post(RequestBody.create(null, "request-body"));
            try (Response response = client.newCall(builder.build()).execute()) { return response.code(); }
        }
        HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(server.url(path).url(), con -> {
            con.setReadTimeout(5000);
            con.setRequestProperty("X-Caller", "caller");
            if (post) {
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                try (java.io.OutputStream output = con.getOutputStream()) {
                    output.write("request-body".getBytes(StandardCharsets.UTF_8));
                }
            }
        });
        try { return connection.getResponseCode(); } finally { connection.disconnect(); }
    }

    private RecordedRequest next(String path) throws Exception {
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals(path, request.getPath());
        return request;
    }

    @Test public void callerBodyAndHeadersSurviveRefreshAndRetry() throws Exception {
        server.enqueue(tokens("old"));
        assertEquals(200, request("/login", false));
        next("/login");
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(tokens("new"));
        server.enqueue(new MockResponse());
        assertEquals(200, request("/ordinary", true));
        RecordedRequest first = next("/ordinary");
        RecordedRequest refresh = next("/auth/session/refresh");
        RecordedRequest retry = next("/ordinary");
        for (RecordedRequest ordinary : Arrays.asList(first, retry)) {
            assertEquals("POST", ordinary.getMethod());
            assertEquals("request-body", ordinary.getBody().readUtf8());
            assertEquals("caller", ordinary.getHeader("X-Caller"));
            assertNull(ordinary.getHeader("X-Refresh-Only"));
        }
        assertEquals("Bearer old-access", first.getHeader("Authorization"));
        assertEquals("old-csrf", first.getHeader("anti-csrf"));
        assertEquals("Bearer old-refresh", refresh.getHeader("Authorization"));
        assertEquals("old-csrf", refresh.getHeader("anti-csrf"));
        assertEquals("provided", refresh.getHeader("X-Refresh-Only"));
        assertNull(refresh.getHeader("X-Caller"));
        assertEquals("Bearer new-access", retry.getHeader("Authorization"));
        assertEquals("new-csrf", retry.getHeader("anti-csrf"));
    }

    @Test public void repeated401StopsAtConfiguredRetryLimit() throws Exception {
        server.enqueue(tokens("old"));
        assertEquals(200, request("/login", false));
        next("/login");
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(tokens("new"));
        server.enqueue(new MockResponse().setResponseCode(401));
        try {
            request("/ordinary", true);
            fail("Expected retry-limit error");
        } catch (java.io.IOException | IllegalAccessException expected) {
            assertTrue(expected.getMessage().contains("maximum session refresh limit"));
        }
        next("/ordinary");
        next("/auth/session/refresh");
        next("/ordinary");
        assertEquals(4, server.getRequestCount());
    }
}
