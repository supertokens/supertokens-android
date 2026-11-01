package com.supertokens.upgrade;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensHttpURLConnection;
import com.supertokens.session.SuperTokensPersistentCookieStore;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** One explicitly selected stage per instrumentation process, using only 0.5.4 public SDK APIs. */
public class UpgradeTest {
    private static final String PROCESS = UUID.randomUUID().toString();
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String SESSION_PREFS = "supertokens-android-shared-preferences";
    private Context context;
    private SharedPreferences receipt;
    private SuperTokensPersistentCookieStore cookies;
    private String backend;
    private String scenario;
    private String run;
    private boolean cookieMode;
    private boolean exposedCookie;
    private boolean publishedSignOut;
    private java.util.Map<String, java.util.List<String>> refreshHeaders;
    private int refreshResponses;
    private boolean sdkConfigured;

    @Test public void runStage() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        receipt = context.getSharedPreferences("upgrade-receipt", Context.MODE_PRIVATE);
        backend = required(arguments, "backend");
        scenario = required(arguments, "scenario");
        run = required(arguments, "runId");
        String stage = required(arguments, "stage");
        assertEquals("Wrong installed SDK flavor", required(arguments, "expectedFlavor"), BuildConfig.SDK_NAME);
        assertTrue("Unknown scenario", scenario.equals("login-b") || scenario.equals("refresh")
                || scenario.equals("logout") || scenario.equals("cookie")
                || scenario.equals("cookie-refresh") || scenario.equals("cookie-signout")
                || scenario.equals("exposed-cookie-refresh") || scenario.equals("exposed-cookie-signout"));
        exposedCookie = scenario.startsWith("exposed-cookie-");
        publishedSignOut = scenario.equals("cookie-signout") || scenario.equals("exposed-cookie-signout");
        cookieMode = exposedCookie || scenario.equals("cookie") || scenario.startsWith("cookie-");
        CookieHandler previous = CookieHandler.getDefault();
        cookies = new SuperTokensPersistentCookieStore(context);
        CookieHandler.setDefault(new CookieManager(cookies, CookiePolicy.ACCEPT_ALL) {
            @Override public void put(java.net.URI uri, java.util.Map<String, java.util.List<String>> headers) throws java.io.IOException {
                // Android omits the HTTP status line here. Exclude the SDK's explicit single-Set-Cookie replay map.
                if (uri.getPath().equals("/auth/session/refresh") && !ExposureEvidence.isCookieReplay(headers)) {
                    refreshHeaders = new java.util.HashMap<>(headers);
                    refreshResponses++;
                }
                super.put(uri, headers);
            }
        });
        boolean ownsReceipt = false;
        boolean recovering = false;
        try {
            if (!stage.equals("seed")) {
                assertEquals("Wrong run receipt", run, receipt.getString("runId", null));
                assertEquals("Wrong scenario receipt", scenario, receipt.getString("scenario", null));
                assertEquals("Wrong backend receipt", backend, receipt.getString("backend", null));
                assertFalse("Stage must run in a new process", PROCESS.equals(receipt.getString("process", null)));
                ownsReceipt = true;
            }
            if (stage.equals("cleanup")) {
                recovering = true;
                recover(null);
                return;
            }
            JSONObject capability = raw("/test/capabilities", null);
            assertTrue("Mapped-owner fixture unavailable", capability.getBoolean("mappedGoogleSession"));
            assertTrue("Core incompatible or unreachable", capability.getBoolean("compatible"));
            if (cookieMode) assertEquals("Wrong backend cookie exposure configuration", exposedCookie,
                    raw("/featureFlags", null).getBoolean("sessionJwt"));
            if (exposedCookie) assertTrue("Exposure requires Node 14+",
                    Integer.parseInt(capability.getString("nodeVersion").split("\\.")[0]) >= 14);
            configureSdk();
            if (stage.equals("seed")) {
                assertEquals("sdk054", BuildConfig.SDK_NAME);
                assertTrue("Unfinished prior run: perform its scoped cleanup first",
                        !receipt.contains("runId") || receipt.getBoolean("cleanupDone", false));
                assertFalse("Seed requires logged-out fixture app", SuperTokens.doesSessionExist(context));
                assertTrue(receipt.edit().clear().putString("runId", run).putString("scenario", scenario)
                        .putString("backend", backend).putString("owners", "[]").putBoolean("cleanupDone", false).commit());
                ownsReceipt = true;
                login("A");
                if (scenario.equals("cookie-refresh") || scenario.equals("exposed-cookie-refresh")) refresh();
                if (publishedSignOut) {
                    SuperTokens.signOut(context);
                    assertLogoutDurableAndRevoked();
                }
                save("seeded", !publishedSignOut);
            } else {
                if (stage.equals("check-initial")) {
                    assertEquals("candidate", BuildConfig.SDK_NAME);
                    assertEquals("seeded", receipt.getString("phase", null));
                    if (publishedSignOut) {
                        assertFalse("Published handoff must be absent", receipt.getBoolean("available", true));
                        assertLogoutDurableAndRevoked();
                        login("B");
                    } else checkAvailable();
                    refresh();
                    save("candidate-checked", true);
                } else if (stage.equals("mutate")) {
                    assertEquals("sdk054", BuildConfig.SDK_NAME);
                    assertEquals("candidate-checked", receipt.getString("phase", null));
                    checkAvailable();
                    if (scenario.equals("login-b") || cookieMode) login("B");
                    else if (scenario.equals("refresh")) refresh();
                    else {
                        SuperTokens.signOut(context);
                        assertLogoutDurableAndRevoked();
                    }
                    save("mutated", !scenario.equals("logout"));
                } else if (stage.equals("check-final")) {
                    assertEquals("candidate", BuildConfig.SDK_NAME);
                    assertEquals("mutated", receipt.getString("phase", null));
                    if (receipt.getBoolean("available", false)) {
                        checkAvailable();
                        refresh();
                    } else assertLogoutDurableAndRevoked();
                    save("final-checked", receipt.getBoolean("available", false));
                } else fail("Unknown stage");
            }
        } catch (Throwable failure) {
            if (ownsReceipt && !recovering) recover(failure);
            if (failure instanceof Error) throw (Error) failure;
            throw (Exception) failure;
        } finally {
            CookieHandler.setDefault(previous);
        }
    }

    private void configureSdk() throws Exception {
        if (!sdkConfigured) {
            new SuperTokens.Builder(context, backend).tokenTransferMethod(cookieMode ? "cookie" : "header").build();
            sdkConfigured = true;
        }
    }

    private void recover(Throwable primary) throws Exception {
        CleanupRecovery.run(primary, new CleanupRecovery.Actions() {
            @Override public void signOut() throws Exception {
                configureSdk();
                if (SuperTokens.doesSessionExist(context)) SuperTokens.signOut(context);
            }

            @Override public void assertAbsent() throws Exception { UpgradeTest.this.assertAbsent(); }

            @Override public java.util.List<String> owners() throws Exception {
                JSONArray stored = new JSONArray(receipt.getString("owners", "[]"));
                java.util.List<String> result = new java.util.ArrayList<String>();
                for (int i = 0; i < stored.length(); i++) result.add(stored.getString(i));
                return result;
            }

            @Override public void cleanupOwner(String id) throws Exception {
                assertTrue("Scoped backend cleanup failed", raw("/test/cleanup-owner",
                        new JSONObject().put("fixtureId", id)).getBoolean("cleaned"));
            }

            @Override public void acknowledge(String id) throws Exception {
                JSONArray stored = new JSONArray(receipt.getString("owners", "[]"));
                JSONArray remaining = new JSONArray();
                for (int i = 0; i < stored.length(); i++) {
                    if (!id.equals(stored.getString(i))) remaining.put(stored.getString(i));
                }
                assertTrue("Owner acknowledgment was not persisted",
                        receipt.edit().putString("owners", remaining.toString()).commit());
            }

            @Override public void assertNoPendingCreation() {
                assertFalse("Creation outcome unknown: preserve pendingRequestId; reconcile exact request; do not retry creation",
                        receipt.contains("pendingRequestId"));
            }

            @Override public void complete() {
                flush();
                assertTrue(receipt.edit().putBoolean("cleanupDone", true).putString("process", PROCESS)
                        .putString("phase", "cleaned").commit());
            }
        });
    }

    private void login(String label) throws Exception {
        String prior = receipt.getString("userId", null);
        String requestId = run + "-" + scenario + "-" + label;
        String email = requestId + "@upgrade.example";
        assertFalse("Unresolved creation request must not be retried", receipt.contains("pendingRequestId"));
        assertTrue(receipt.edit().putString("pendingRequestId", requestId).putString("pendingRequestEmail", email).commit());
        JSONObject owner = managed("/test/email-change-owner", new JSONObject().put("recipe", "google")
                .put("email", email).put("requestId", requestId));
        String fixture = owner.getString("fixtureId");
        JSONArray owners = new JSONArray(receipt.getString("owners", "[]"));
        owners.put(fixture);
        assertTrue(receipt.edit().putString("owners", owners.toString()).putString("userId", owner.getString("userId"))
                .putString("fixtureId", fixture).putString("handle", owner.getString("handle"))
                .putString("lastRequestId", requestId).remove("pendingRequestId").remove("pendingRequestEmail").commit());
        assertTrue("Backend must create a real mapped Google owner", owner.getBoolean("google"));
        if (label.equals("B")) assertFalse("Rollback login must replace A with B", owner.getString("userId").equals(prior));
        assertTrue("SDK failed to adopt login", SuperTokens.doesSessionExist(context));
        assertEquals(owner.getString("userId"), SuperTokens.getUserId(context));
        protectedRequest();
    }

    private void checkAvailable() throws Exception {
        assertTrue("Expected persisted session", SuperTokens.doesSessionExist(context));
        assertEquals(receipt.getString("userId", null), SuperTokens.getUserId(context));
        assertTrue("Access credential changed during reopen", receipt.getString("accessDigest", "").equals(accessDigest()));
        assertTrue("Refresh credential changed during reopen", receipt.getString("refreshDigest", "").equals(refreshDigest()));
        if (cookieMode) assertEquals("Cookie access exposure changed across process handoff",
                exposedCookie, SuperTokens.getAccessToken(context) != null);
        if (exposedCookie) ExposureEvidence.handoff(receipt.getString("exposedAccessDigest", null),
                digest(SuperTokens.getAccessToken(context)));
        protectedRequest();
    }

    private void protectedRequest() throws Exception {
        JSONObject actual = managed("/test/protected", null);
        assertEquals(receipt.getString("userId", null), actual.getString("userId"));
        assertEquals(receipt.getString("handle", null), actual.getString("handle"));
    }

    private void refresh() throws Exception {
        String before = refreshDigest();
        assertFalse("Missing refresh credential", before.isEmpty());
        refreshHeaders = null;
        refreshResponses = 0;
        assertTrue("SDK refresh failed", SuperTokens.attemptRefreshingSession(context));
        if (exposedCookie) {
            assertEquals("Expected one actual refresh response through installed CookieManager", 1, refreshResponses);
            ExposureEvidence.refresh(refreshHeaders, SuperTokens.getAccessToken(context));
            System.out.println("EXPOSED_COOKIE_REFRESH sdk=" + BuildConfig.SDK_NAME
                    + " actualResponses=1 accessHeaderMatchesSdk=true refreshHeaderAbsent=true");
        }
        assertFalse("Refresh did not rotate credentials", before.equals(refreshDigest()));
        protectedRequest();
    }

    private void assertAbsent() throws Exception {
        flush();
        SharedPreferences persisted = context.getSharedPreferences(SESSION_PREFS, 0);
        assertTrue("Persisted header access survived logout", persisted.getString("st-storage-item-st-access-token", null) == null);
        assertTrue("Persisted header refresh survived logout", persisted.getString("st-storage-item-st-refresh-token", null) == null);
        // Reopening filters expired entries; this checks usability, not literal XML absence.
        SuperTokensPersistentCookieStore reopened = new SuperTokensPersistentCookieStore(context);
        for (HttpCookie cookie : reopened.getCookies()) {
            assertFalse("Usable credential cookie survived logout", cookie.getName().equals("sRefreshToken")
                    || cookie.getName().equals("sAccessToken"));
        }
        assertFalse("Unexpected surviving session", SuperTokens.doesSessionExist(context));
        assertTrue("Unexpected access after logout", SuperTokens.getAccessToken(context) == null);
        try {
            SuperTokens.getUserId(context);
            fail("Logged-out getter returned a user");
        } catch (IllegalStateException expected) { }
    }

    private void assertLogoutDurableAndRevoked() throws Exception {
        assertAbsent();
        JSONObject state = raw("/test/owner-session-state", new JSONObject()
                .put("fixtureId", receipt.getString("fixtureId", null))
                .put("handle", receipt.getString("handle", null)));
        assertTrue("Backend must recognize this fixture's session", state.getBoolean("owned"));
        assertFalse("Backend session survived SDK logout", state.getBoolean("active"));
        assertFalse("SDK refresh restored a logged-out session", SuperTokens.attemptRefreshingSession(context));
        assertAbsent();
    }

    private void save(String phase, boolean available) throws Exception {
        flush();
        if (available) {
            assertFalse("Missing persisted access credential", accessDigest().isEmpty());
            assertFalse("Missing persisted refresh credential", refreshDigest().isEmpty());
            if (exposedCookie) assertNotNull("Missing exposed access before handoff", SuperTokens.getAccessToken(context));
        } else assertAbsent();
        assertTrue(receipt.edit().putString("phase", phase).putString("process", PROCESS)
                .putBoolean("available", available).putString("accessDigest", accessDigest())
                .putString("exposedAccessDigest", digest(SuperTokens.getAccessToken(context)))
                .putString("refreshDigest", refreshDigest()).commit());
    }

    private void flush() {
        // Rank 2 tests completed writes, not dropped apply/crash consistency.
        assertTrue(context.getSharedPreferences(SESSION_PREFS, 0).edit().commit());
        assertTrue(context.getSharedPreferences("supertokens-cookie-shared-prefs", 0).edit().commit());
    }

    private String accessDigest() throws Exception {
        return cookieMode ? cookieDigest("sAccessToken") : digest(SuperTokens.getAccessToken(context));
    }

    private String refreshDigest() throws Exception {
        if (cookieMode) return cookieDigest("sRefreshToken");
        return digest(context.getSharedPreferences(SESSION_PREFS, 0).getString("st-storage-item-st-refresh-token", null));
    }

    private String cookieDigest(String name) throws Exception {
        for (HttpCookie cookie : cookies.getCookies()) {
            if (cookie.getName().equals(name)) return digest(cookie.getValue());
        }
        return "";
    }

    private String digest(String value) throws Exception {
        if (value == null) return "";
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF8));
        StringBuilder result = new StringBuilder();
        for (byte item : bytes) result.append(String.format("%02x", item & 255));
        return result.toString();
    }

    private JSONObject raw(String path, JSONObject body) throws Exception { return request(path, body, false); }
    private JSONObject managed(String path, JSONObject body) throws Exception { return request(path, body, true); }

    private JSONObject request(String path, final JSONObject body, boolean managed) throws Exception {
        URL url = new URL(backend + path);
        SuperTokensHttpURLConnection.PreConnectCallback prepare = new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override public void doAction(HttpURLConnection connection) throws java.io.IOException {
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(false);
                if (body != null) {
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    OutputStream output = connection.getOutputStream();
                    try { output.write(body.toString().getBytes(UTF8)); } finally { output.close(); }
                }
            }
        };
        HttpURLConnection connection;
        if (managed) connection = SuperTokensHttpURLConnection.newRequest(url, prepare);
        else {
            connection = (HttpURLConnection) url.openConnection();
            prepare.doAction(connection);
        }
        try {
            assertEquals("Unexpected HTTP status for " + path, 200, connection.getResponseCode());
            if (managed && cookieMode && path.equals("/test/email-change-owner")) {
                assertEquals("Actual login access header exposure", exposedCookie,
                        connection.getHeaderField("st-access-token") != null);
                assertNull("Cookie refresh token must not be exposed", connection.getHeaderField("st-refresh-token"));
                if (exposedCookie) assertEquals(connection.getHeaderField("st-access-token"), SuperTokens.getAccessToken(context));
            }
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    assertTrue("Unexpected oversized fixture response", bytes.size() + count <= 65536);
                    bytes.write(buffer, 0, count);
                }
            } finally { input.close(); }
            return new JSONObject(new String(bytes.toByteArray(), UTF8));
        } finally { connection.disconnect(); }
    }

    private String required(Bundle args, String key) {
        String value = args.getString(key);
        assertTrue("Missing instrumentation argument: " + key, value != null && !value.isEmpty());
        return value;
    }
}
