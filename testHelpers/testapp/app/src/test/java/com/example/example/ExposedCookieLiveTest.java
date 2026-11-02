package com.example.example;

import android.content.Context;
import android.content.SharedPreferences;
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder;
import com.supertokens.session.EventHandler;
import com.supertokens.session.SessionSnapshot;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensHttpURLConnection;
import com.supertokens.session.SuperTokensInterceptor;
import com.supertokens.session.SuperTokensPersistentCookieStore;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import retrofit2.Retrofit;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Body;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Owned live fixture only; the deliberately removed access cookie forces a real backend 401. */
@RunWith(Parameterized.class)
public class ExposedCookieLiveTest {
    enum Transport { OKHTTP, RETROFIT, URLCONNECTION }
    @Parameterized.Parameters(name = "{0}") public static Collection<Object[]> rows() {
        List<Object[]> rows = new ArrayList<>();
        for (Transport transport : Transport.values()) rows.add(new Object[]{transport});
        return rows;
    }
    public interface Api {
        @POST("login") retrofit2.Call<ResponseBody> login(@Body RequestBody body);
        @GET("test/protected") retrofit2.Call<ResponseBody> protectedRequest();
    }
    private final Transport transport;
    private String base;
    private Context context;
    private OkHttpClient client;
    private final ExposureEvidence protectedEvidence = new ExposureEvidence();
    private String exposed;
    private Map<String, List<String>> refreshHeaders;
    private Map<String, List<String>> protectedHeaders;
    private boolean retryAccessChecked;
    private SessionSnapshot refreshedSnapshot;
    public ExposedCookieLiveTest(Transport transport) { this.transport = transport; }

    @Test public void exposedCookieLifecycle() throws Exception {
        org.junit.Assume.assumeTrue("Dedicated owned exposed profile", "EXPOSED_COOKIE".equals(System.getenv("ST_UPGRADE_MODE")));
        base = System.getenv("ST_TEST_BACKEND_URL");
        assertNotNull("Owned backend required", base);
        assertEquals("EXPOSED_COOKIE", System.getenv("ST_UPGRADE_MODE"));
        context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        Map<String, SharedPreferences> preferences = new HashMap<>();
        when(context.getSharedPreferences(anyString(), anyInt())).thenAnswer(call ->
                preferences.computeIfAbsent((String) call.getArguments()[0], name -> new SPMockBuilder().createSharedPreferences()));
        CookieHandler previous = CookieHandler.getDefault();
        SuperTokensPersistentCookieStore store = new SuperTokensPersistentCookieStore(context);
        CookieManager cookies = new CookieManager(store, CookiePolicy.ACCEPT_ALL) {
            @Override public Map<String, List<String>> get(java.net.URI uri, Map<String, List<String>> headers) throws java.io.IOException {
                if (transport == Transport.URLCONNECTION && uri.getPath().equals("/test/protected")) {
                    protectedEvidence.protectedRequest(headers);
                }
                return super.get(uri, headers);
            }
            @Override public void put(java.net.URI uri, Map<String, List<String>> headers) throws java.io.IOException {
                if (transport == Transport.URLCONNECTION && uri.getPath().equals("/test/protected")) {
                    assertNotNull("Missing actual protected response status", headers.get(null));
                    assertEquals(1, headers.get(null).size());
                    String status = headers.get(null).get(0);
                    assertTrue("Malformed HTTP status", status.matches("HTTP/\\S+ [0-9]{3}(?: .*)?"));
                    protectedEvidence.protectedResponse(Integer.parseInt(status.split(" ")[1]));
                    if (status.split(" ")[1].equals("200")) protectedHeaders = new HashMap<>(headers);
                }
                if (uri.getPath().equals("/auth/session/refresh") && headers.containsKey(null)) {
                    refreshHeaders = new HashMap<>(headers);
                }
                super.put(uri, headers);
            }
        };
        CookieHandler.setDefault(cookies);
        List<EventHandler.EventType> events = new ArrayList<>();
        try {
            String userId = "exposed-" + UUID.randomUUID();
            SuperTokens.resetForTests();
            new SuperTokens.Builder(context, base).tokenTransferMethod("cookie").eventHandler(events::add).build();
            client = new OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).cookieJar(new CookieJar() {
                @Override public void saveFromResponse(HttpUrl url, List<Cookie> values) {
                    List<String> headers = new ArrayList<>();
                    for (Cookie cookie : values) headers.add(cookie.toString());
                    try { cookies.put(url.uri(), Collections.singletonMap("Set-Cookie", headers)); }
                    catch (java.io.IOException failure) { throw new AssertionError(failure); }
                }
                @Override public List<Cookie> loadForRequest(HttpUrl url) {
                    List<Cookie> values = new ArrayList<>();
                    try {
                        for (List<String> headers : cookies.get(url.uri(), Collections.emptyMap()).values()) {
                            for (String header : headers) for (String part : header.split(";")) {
                                Cookie cookie = Cookie.parse(url, part.trim());
                                if (cookie != null) values.add(cookie);
                            }
                        }
                    } catch (java.io.IOException failure) { throw new AssertionError(failure); }
                    return values;
                }
            }).addInterceptor(new SuperTokensInterceptor()).addNetworkInterceptor(chain -> {
                if (chain.request().url().encodedPath().equals("/test/protected")) {
                    protectedEvidence.protectedRequest(chain.request().headers().toMultimap());
                    checkRetryAccess();
                }
                Response response = chain.proceed(chain.request());
                if (chain.request().url().encodedPath().equals("/auth/session/refresh") && response.code() == 200) {
                    refreshHeaders = response.headers().toMultimap();
                }
                if (chain.request().url().encodedPath().equals("/test/protected")) {
                    protectedEvidence.protectedResponse(response.code());
                    if (response.code() == 200) protectedHeaders = response.headers().toMultimap();
                }
                if (chain.request().url().encodedPath().equals("/login")) {
                    exposed = response.header("st-access-token");
                    assertNotNull("Actual login must expose access header", exposed);
                    assertNull("Refresh must remain cookie-only", response.header("st-refresh-token"));
                }
                return response;
            }).build();
            call(true, userId);
            SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
            assertEquals(SessionSnapshot.Status.AVAILABLE, before.getStatus());
            assertEquals(userId, before.getUserId());
            assertEquals(exposed, SuperTokens.getAccessToken(context));
            assertEquals(Collections.singletonList(EventHandler.EventType.SESSION_CREATED), events);
            assertNull("Cookie session has no complete header token pair", before.getTokens());
            assertNull(context.getSharedPreferences("supertokens-android-shared-preferences", 0)
                    .getString("st-storage-item-st-refresh-token", null));
            assertTrue(store.getCookies().stream().anyMatch(cookie -> cookie.getName().equals("sRefreshToken")));
            int refreshBefore = com.example.TestUtils.getRefreshTokenCounter();
            events.clear();
            for (java.net.URI uri : new ArrayList<>(store.getURIs())) {
                for (HttpCookie cookie : new ArrayList<>(store.getCookies())) {
                    if (cookie.getName().equals("sAccessToken")) store.remove(uri, cookie);
                }
            }
            assertFalse(store.getCookies().stream().anyMatch(cookie -> cookie.getName().equals("sAccessToken")));
            JSONObject actual = new JSONObject(call(false, null));
            assertEquals(before.getUserId(), actual.getString("userId"));
            assertEquals(before.getSessionHandle(), actual.getString("handle"));
            protectedEvidence.assertRetry();
            System.out.println("EXPOSED_COOKIE transport=" + transport + " " + protectedEvidence.summary());
            assertEquals(refreshBefore + 1, com.example.TestUtils.getRefreshTokenCounter());
            SessionSnapshot after = SuperTokens.getSessionSnapshot(context);
            assertSame(before.getOwner(), after.getOwner());
            assertEquals(before.getSessionHandle(), after.getSessionHandle());
            assertEquals(before.getUserId(), after.getUserId());
            assertEquals(before.getTenantId(), after.getTenantId());
            assertNotNull(SuperTokens.getAccessToken(context));
            assertTrue("Refresh header must match the SDK before the protected retry", retryAccessChecked);
            boolean protectedAccessUpdated = protectedHeaders.keySet().stream().anyMatch("st-access-token"::equalsIgnoreCase);
            // Core may replace the refreshed access JWT on first verification; check each adoption at its own boundary.
            ExposureEvidence.refresh(protectedAccessUpdated ? protectedHeaders : refreshHeaders, SuperTokens.getAccessToken(context));
            System.out.println("EXPOSED_COOKIE retryAccessMatchesRefresh=true protectedAccessUpdated=" + protectedAccessUpdated);
            assertNull("Cookie session has no complete header token pair", after.getTokens());
            assertNull(context.getSharedPreferences("supertokens-android-shared-preferences", 0)
                    .getString("st-storage-item-st-refresh-token", null));
            List<EventHandler.EventType> expectedEvents = new ArrayList<>();
            if (!samePayload(before.getAccessTokenPayload(), refreshedSnapshot.getAccessTokenPayload())) {
                expectedEvents.add(EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED);
            }
            expectedEvents.add(EventHandler.EventType.REFRESH_SESSION);
            if (!samePayload(refreshedSnapshot.getAccessTokenPayload(), after.getAccessTokenPayload())) {
                expectedEvents.add(EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED);
            }
            assertEquals("Events must match the two observed payload transitions and exactly one refresh", expectedEvents, events);
            events.clear();
            SuperTokens.signOut(context);
            assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
            assertFalse(SuperTokens.doesSessionExist(context));
            assertNull(SuperTokens.getAccessToken(context));
            assertEquals(Collections.singletonList(EventHandler.EventType.SIGN_OUT), events);
            assertFalse(store.getCookies().stream().anyMatch(cookie -> !cookie.hasExpired() &&
                    (cookie.getName().equals("sAccessToken") || cookie.getName().equals("sRefreshToken"))));
        } finally {
            SuperTokens.resetForTests();
            CookieHandler.setDefault(previous);
            if (client != null) { client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow(); }
        }
    }

    private static boolean samePayload(JSONObject first, JSONObject second) {
        com.google.gson.JsonParser parser = new com.google.gson.JsonParser();
        return parser.parse(first.toString()).equals(parser.parse(second.toString()));
    }

    private void checkRetryAccess() {
        if (refreshHeaders != null) {
            ExposureEvidence.refresh(refreshHeaders, SuperTokens.getAccessToken(context));
            refreshedSnapshot = SuperTokens.getSessionSnapshot(context);
            retryAccessChecked = true;
        }
    }

    private String call(boolean login, String userId) throws Exception {
        String json = login ? new JSONObject().put("userId", userId).toString() : null;
        if (transport == Transport.URLCONNECTION) {
            HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(new URL(base + (login ? "/login" : "/test/protected")), con -> {
                con.setConnectTimeout(15000); con.setReadTimeout(15000);
                if (!login) {
                    assertNull("Configured URLConnection request leaked Authorization", con.getRequestProperty("Authorization"));
                    checkRetryAccess();
                }
                if (login) {
                    con.setRequestMethod("POST"); con.setDoOutput(true);
                    con.setRequestProperty("Content-Type", "application/json");
                    try (java.io.OutputStream out = con.getOutputStream()) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
                }
            });
            try {
                assertEquals(200, connection.getResponseCode());
                if (login) {
                    exposed = connection.getHeaderField("st-access-token");
                    assertNotNull(exposed); assertNull(connection.getHeaderField("st-refresh-token"));
                }
                return com.example.TestUtils.getBodyFromConnection(connection);
            } finally { connection.disconnect(); }
        }
        RequestBody body = login ? RequestBody.create(MediaType.parse("application/json"), json) : null;
        if (transport == Transport.RETROFIT) {
            Api api = new Retrofit.Builder().baseUrl(base + "/").client(client).build().create(Api.class);
            retrofit2.Response<ResponseBody> response = (login ? api.login(body) : api.protectedRequest()).execute();
            assertEquals(200, response.code());
            try (ResponseBody result = response.body()) { return result.string(); }
        }
        try (Response response = client.newCall(new Request.Builder().url(base + (login ? "/login" : "/test/protected"))
                .method(login ? "POST" : "GET", body).build()).execute()) {
            assertEquals(200, response.code()); return response.body().string();
        }
    }
}
