package com.example.example;

import android.content.Context;
import android.content.SharedPreferences;
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensHttpURLConnection;
import com.supertokens.session.SuperTokensInterceptor;
import com.supertokens.session.EventHandler;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final List<EventHandler.EventType> events = new CopyOnWriteArrayList<>();
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
                .eventHandler(events::add)
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

    @Test public void refresh401WithoutFrontTokenPropagatesErrorAndPreservesCredentials() throws Exception {
        server.enqueue(tokens("old"));
        assertEquals(200, request("/login", false));
        next("/login");
        SharedPreferences preferences = context.getSharedPreferences("supertokens", Context.MODE_PRIVATE);
        Map<String, ?> before = new HashMap<>(preferences.getAll());
        assertTrue(SuperTokens.doesSessionExist(context));
        assertTrue(before.containsValue("old-access"));
        assertTrue(before.containsValue("old-refresh"));
        assertTrue(before.containsValue("old-csrf"));
        assertTrue(before.containsValue(java.util.Base64.getEncoder().encodeToString(
                "{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{}}".getBytes(StandardCharsets.UTF_8))));
        events.clear();
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(401));

        boolean threwIOException = false;
        try {
            request("/ordinary", false);
        } catch (java.io.IOException expected) {
            threwIOException = true;
        }

        next("/ordinary");
        next("/auth/session/refresh");
        assertTrue("refresh 401 without front-token must propagate IOException", threwIOException);
        assertTrue("credentials must remain unchanged", before.equals(preferences.getAll()));
        assertTrue(SuperTokens.doesSessionExist(context));
        assertEquals(Collections.singletonList(EventHandler.EventType.UNAUTHORISED), events);
    }

    @Test public void refresh401WithFrontTokenRemoveEndsSession() throws Exception {
        server.enqueue(tokens("old"));
        assertEquals(200, request("/login", false));
        next("/login");
        events.clear();
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(401).setHeader("front-token", "remove"));
        assertEquals(401, request("/ordinary", false));
        next("/ordinary");
        next("/auth/session/refresh");
        assertFalse(SuperTokens.doesSessionExist(context));
        assertEquals(Collections.singletonList(EventHandler.EventType.UNAUTHORISED), events);
    }

    @Test public void expiryWithValidFrontTokenStillClearsSession() throws Exception {
        refreshMatrix(401, 401, "valid", false, 401, Collections.emptyList());
    }

    @Test public void expiryWithChangedPayloadEmitsUpdateAndClearsSession() throws Exception {
        refreshMatrix(401, 401, "changed", false, 401,
                Collections.singletonList(EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED));
    }

    @Test public void successfulRefreshWithoutFrontTokenEmitsLegacySignOut() throws Exception {
        refreshMatrix(401, 200, "absent", true, 200,
                Arrays.asList(EventHandler.EventType.SIGN_OUT, EventHandler.EventType.REFRESH_SESSION));
    }

    @Test public void successfulRefreshWithRotationEmitsRefresh() throws Exception {
        refreshMatrix(401, 200, "rotation", true, 200,
                Collections.singletonList(EventHandler.EventType.REFRESH_SESSION));
    }

    @Test public void successfulRefreshWithRemoveEndsSession() throws Exception {
        refreshMatrix(401, 200, "remove", false, 401, Collections.singletonList(EventHandler.EventType.SIGN_OUT));
    }

    @Test public void forbiddenRefreshWithoutFrontTokenPreservesSession() throws Exception {
        refreshMatrix(401, 403, "absent", true, -1, Collections.singletonList(EventHandler.EventType.SIGN_OUT));
    }

    @Test public void forbiddenRefreshWithRemoveEndsSession() throws Exception {
        refreshMatrix(401, 403, "remove", false, 401, Collections.singletonList(EventHandler.EventType.SIGN_OUT));
    }

    @Test public void configuredExpiryWithoutFrontTokenPreservesSession() throws Exception {
        refreshMatrix(419, 419, "absent", true, -1, Collections.singletonList(EventHandler.EventType.UNAUTHORISED));
    }

    private void refreshMatrix(int expiry, int refreshStatus, String front, boolean exists, int result,
                               List<EventHandler.EventType> expectedEvents) throws Exception {
        if (expiry != 401) {
            SuperTokens.resetForTests();
            new SuperTokens.Builder(context, server.url("/").toString())
                    .sessionExpiredStatusCode(expiry).maxRetryAttemptsForSessionRefresh(1)
                    .eventHandler(events::add).build();
        }
        server.enqueue(tokens("old"));
        assertEquals(200, request("/login", false));
        next("/login");
        SharedPreferences preferences = context.getSharedPreferences("supertokens", Context.MODE_PRIVATE);
        Map<String, ?> before = new HashMap<>(preferences.getAll());
        events.clear();
        server.enqueue(new MockResponse().setResponseCode(expiry));
        MockResponse refresh = front.equals("rotation") ? tokens("new") : new MockResponse();
        refresh.setResponseCode(refreshStatus);
        if (front.equals("remove")) refresh.setHeader("front-token", "remove");
        if (front.equals("valid")) refresh.setHeader("front-token", java.util.Base64.getEncoder().encodeToString(
                "{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{}}".getBytes(StandardCharsets.UTF_8)));
        if (front.equals("changed")) refresh.setHeader("front-token", java.util.Base64.getEncoder().encodeToString(
                "{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{\"role\":\"updated\"}}".getBytes(StandardCharsets.UTF_8)));
        server.enqueue(refresh);
        if (result == 200) server.enqueue(new MockResponse());
        int actual = -1;
        try { actual = request("/ordinary", false); } catch (java.io.IOException expected) { }
        next("/ordinary");
        next("/auth/session/refresh");
        if (result == 200) next("/ordinary");
        assertEquals(result, actual);
        assertEquals(exists, SuperTokens.doesSessionExist(context));
        if (exists && front.equals("absent")) assertTrue(before.equals(preferences.getAll()));
        if (!exists) {
            assertFalse(preferences.getAll().containsValue("old-access"));
            assertFalse(preferences.getAll().containsValue("old-refresh"));
            assertFalse(preferences.getAll().containsValue("old-csrf"));
            assertFalse(preferences.getAll().containsValue(java.util.Base64.getEncoder().encodeToString(
                    "{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{}}".getBytes(StandardCharsets.UTF_8))));
        }
        if (front.equals("rotation")) {
            assertTrue(preferences.getAll().containsValue("new-access"));
            assertTrue(preferences.getAll().containsValue("new-refresh"));
            assertTrue(preferences.getAll().containsValue("new-csrf"));
            assertTrue(preferences.getAll().containsValue(java.util.Base64.getEncoder().encodeToString(
                    "{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{}}".getBytes(StandardCharsets.UTF_8))));
        }
        assertEquals(expectedEvents, events);
    }
}
