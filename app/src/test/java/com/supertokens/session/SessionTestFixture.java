package com.supertokens.session;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.CookieHandler;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public abstract class SessionTestFixture {
    protected Context context;
    protected MockWebServer server;
    protected OkHttpClient client;
    protected TestPreferences preferenceStore;
    private CookieHandler originalCookieHandler;

    protected boolean initializeOnSetup() { return true; }

    protected void flushAndRestartPreferences() {
        preferenceStore.flush();
        preferenceStore.restart();
        upgradeCookies.flush();
        upgradeCookies.restart();
    }

    private TestPreferences upgradeCookies;
    protected void isolateUpgradeCookies() {
        upgradeCookies = new TestPreferences();
        when(context.getSharedPreferences(eq("CookiePersistence"), anyInt())).thenReturn(upgradeCookies.preferences);
    }

    protected boolean ownedRefreshAvailable() {
        if (Boolean.parseBoolean(System.getProperty("session.matrix.owned", "true"))) return true;
        assertEquals("Only the explicit 3.12 lane lacks owned refresh", "3.12.13", System.getProperty("session.matrix.okhttp"));
        SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
        int requests = server.getRequestCount();
        try {
            new SuperTokensInterceptor(client);
            fail("OkHttp 3.12 must reject owned refresh before dispatch");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("requires OkHttp 3.14"));
        }
        assertSame(before, SuperTokens.getSessionSnapshot(context));
        assertEquals(requests, server.getRequestCount());
        System.out.println("CAPABILITY owned-refresh=unsupported; constructor rejection verified, owned scenario not executed");
        return false;
    }

    protected void initializeSession() throws Exception {
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header").build();
    }

    protected void restart(boolean simulateProcessDeath) throws Exception {
        SuperTokens.resetForTests();
        if (simulateProcessDeath) preferenceStore.restart();
        initializeSession();
    }

    protected void failCommits(TestPreferences.CommitMode mode) { preferenceStore.commitMode = mode; }

    protected void seedLegacy(SessionTokens tokens) {
        Map<String, Object> values = new HashMap<>();
        values.put("st-storage-item-st-access-token", tokens.getAccessToken());
        values.put("st-storage-item-st-refresh-token", tokens.getRefreshToken());
        values.put("st-storage-item-st-last-access-token-update", "1700000000000");
        values.put("supertokens-android-fronttoken-key", tokens.getFrontToken());
        values.put("supertokens-android-anticsrf-key", tokens.getAntiCSRF());
        values.put("st-storage-item-sIRTFrontend", "exists");
        seedLegacy(values);
    }

    protected void seedLegacy(Map<String, ?> rawValues) { preferenceStore.seed(rawValues); }

    @Before public void setUpSession() throws Exception {
        SuperTokens.resetForTests();
        originalCookieHandler = CookieHandler.getDefault();
        context = mock(Context.class);
        preferenceStore = new TestPreferences();
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferenceStore.preferences);
        server = new MockWebServer();
        server.start();
        if (initializeOnSetup()) initializeSession();
        client = new OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS)
                .addInterceptor(new SuperTokensInterceptor()).build();
    }

    @After public void tearDownSession() throws Exception {
        try {
            if (client != null) {
                client.dispatcher().cancelAll();
                client.connectionPool().evictAll();
                client.dispatcher().executorService().shutdownNow();
            }
            if (server != null) server.shutdown();
        } finally {
            SuperTokens.resetForTests();
            CookieHandler.setDefault(originalCookieHandler);
        }
    }

    // Stage edits until commit so readers cannot observe a partially written tuple.
    static SharedPreferences preferences() {
        return new TestPreferences().preferences;
    }

    static SessionTokens tokens(String handle, String uid, long accessExpMs, long frontAteMs, int version) throws Exception {
        if (version < 2 || version > 5) throw new IllegalArgumentException("Expected token version 2–5");
        JSONObject payload = new JSONObject().put("sessionHandle", handle);
        if (version == 2) payload.put("userId", uid).put("expiryTime", accessExpMs).put("timeCreated", 1700000000000L).put("userData", new JSONObject());
        else payload.put("sub", uid).put("exp", accessExpMs / 1000).put("iat", 1700000000L);
        if (version >= 4) payload.put("tId", "public");
        if (version >= 5) payload.put("rsub", "recipe-user");
        String access = encode(new JSONObject().put("alg", "RS256").put("typ", "JWT").put("version", String.valueOf(version)).toString(), true)
                + "." + encode(payload.toString(), true) + ".c2lnbmF0dXJl";
        String front = encode(new JSONObject().put("uid", uid).put("ate", frontAteMs)
                .put("up", version == 2 ? new JSONObject() : payload).toString(), false);
        return new SessionTokens(access, handle + "-refresh", front, "csrf");
    }

    static SessionTokens tokens(String handle, String claim) throws Exception {
        JSONObject payload = new JSONObject().put("sessionHandle", handle).put("sub", "primary-user")
                .put("rsub", "recipe-user").put("tId", "public").put("exp", 4102444800L).put("iat", 1700000000L).put("claim", claim);
        String access = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"version\":\"5\"}", true) + "."
                + encode(payload.toString(), true) + ".c2lnbmF0dXJl";
        String front = encode(new JSONObject().put("uid", "primary-user").put("ate", 4102444800000L)
                .put("up", payload).toString(), false);
        return new SessionTokens(access, handle + "-refresh-" + claim, front, "csrf-" + claim);
    }

    private static String encode(String value, boolean url) {
        return (url ? Base64.getUrlEncoder().withoutPadding() : Base64.getEncoder())
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    protected SessionSnapshot install(String handle, String claim) throws Exception {
        SessionCommitResult result = SuperTokens.installSession(context, SuperTokens.getSessionSnapshot(context).getOwner(), tokens(handle, claim));
        assertEquals(SessionCommitResult.Status.SUCCESS, result.getStatus());
        return result.getSnapshot();
    }

    static MockResponse promotion(SessionTokens tokens) {
        return new MockResponse().setBody("old-body").addHeader("st-access-token", tokens.getAccessToken())
                .addHeader("front-token", tokens.getFrontToken());
    }

    static MockResponse rotation(SessionTokens tokens) {
        return promotion(tokens).addHeader("st-refresh-token", tokens.getRefreshToken());
    }

    protected int request(boolean urlConnection, SessionRequestTag tag, String path) throws Exception {
        if (urlConnection) {
            java.net.HttpURLConnection connection = tag == null
                    ? SuperTokensHttpURLConnection.newRequest(server.url(path).url(), con -> { con.setConnectTimeout(8000); con.setReadTimeout(8000); })
                    : SuperTokensHttpURLConnection.newRequest(server.url(path).url(), con -> { con.setConnectTimeout(8000); con.setReadTimeout(8000); }, tag);
            try { return connection.getResponseCode(); } finally { connection.disconnect(); }
        }
        return request(client, tag, path);
    }

    protected int request(OkHttpClient transport, SessionRequestTag tag, String path) throws Exception {
        Request.Builder request = new Request.Builder().url(server.url(path));
        if (tag != null) request.tag(SessionRequestTag.class, tag);
        try (Response response = transport.newCall(request.build()).execute()) { return response.code(); }
    }
}
