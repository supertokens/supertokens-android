package com.supertokens.upgrade;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import androidx.test.platform.app.InstrumentationRegistry;
import com.supertokens.session.CustomHeaderProvider;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensHttpURLConnection;
import com.supertokens.session.SuperTokensPersistentCookieStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Separate matrix flow: provisioning is never the SDK login under qualification. */
public abstract class MatrixNativeStage {
    private static final String SESSION = "supertokens-android-shared-preferences";
    private static final String COOKIE = "supertokens-cookie-shared-prefs";
    protected Context context;
    protected SharedPreferences receipt;
    protected JSONObject config;
    protected JSONObject observed;
    protected String backend;
    protected String fdi;
    protected boolean exposed;
    private SuperTokensPersistentCookieStore cookies;
    private final String process = UUID.randomUUID().toString();
    private final JSONArray operations = new JSONArray();
    private final JSONArray headerRequests = new JSONArray();
    private final JSONArray revocations = new JSONArray();
    private boolean configured;
    private File evidenceFile;
    private String stageName;

    protected void customize(SuperTokens.Builder builder) { }
    protected abstract void continueSession() throws Exception;

    @Test public final void runStage() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        receipt = context.getSharedPreferences("matrix-native-receipt", 0);
        String encoded = required(args, "matrixConfig");
        config = new JSONObject(new String(Base64.decode(encoded, Base64.NO_WRAP), "UTF-8"));
        backend = required(args, "backend");
        fdi = config.getJSONObject("context").getString("fdi");
        String stage = required(args, "stage");
        stageName = stage;
        String run = required(args, "runId");
        assertEquals(config.getString("runId"), run);
        assertTrue(config.getString("nativeNonce").matches("[a-f0-9]{48}"));
        assertTrue(stage.equals("seed") || stage.equals("check-initial") || stage.equals("cleanup"));
        evidenceFile = new File(context.getFilesDir(), "matrix-native-" + config.getString("nativeNonce") + "-" + stage + ".json");
        assertFalse("Existing stage output; do not repeat operations", evidenceFile.exists());
        assertEquals(config.getString("deviceBackend"), backend);
        assertEquals(required(args, "expectedFlavor"), BuildConfig.SDK_NAME);
        assertEquals("/login", config.getString("route"));
        assertTrue(config.getString("mode").equals("COOKIE") || config.getString("mode").equals("EXPOSED_COOKIE"));
        assertTrue(config.getString("flow").equals("REFRESH") || config.getString("flow").equals("SIGN_OUT"));
        exposed = config.getString("mode").equals("EXPOSED_COOKIE");
        CookieHandler previous = CookieHandler.getDefault();
        cookies = new SuperTokensPersistentCookieStore(context);
        CookieHandler.setDefault(new CookieManager(cookies, CookiePolicy.ACCEPT_ALL));
        boolean owns = false;
        try {
            if (stage.equals("seed")) {
                assertEquals("sdk054", BuildConfig.SDK_NAME);
                assertTrue("Unfinished matrix owner", !receipt.contains("binding") || receipt.getBoolean("cleanupDone", false));
                SharedPreferences legacy = context.getSharedPreferences("upgrade-receipt", 0);
                assertTrue("Unfinished continuation owner", !legacy.contains("runId") || legacy.getBoolean("cleanupDone", false));
                configure();
                absent();
                assertTrue("Retained cookies require owner reconciliation", cookies.getCookies().isEmpty());
                assertTrue(receipt.edit().clear().putString("binding", encoded).putString("runId", run)
                        .putBoolean("cleanupDone", false).commit());
                owns = true;
                observed = new JSONObject().put("schemaVersion", 2);
            } else {
                assertEquals("Wrong matrix owner", encoded, receipt.getString("binding", null));
                assertFalse("Stage must use a new process", process.equals(receipt.getString("process", null)));
                assertFalse("Already cleaned", receipt.getBoolean("cleanupDone", false));
                owns = true;
                observed = new JSONObject(receipt.getString("observed", "{\"schemaVersion\":2}"));
            }
            if (stage.equals("cleanup")) {
                assertEquals("candidate", BuildConfig.SDK_NAME);
                assertEquals("continued", receipt.getString("phase", null));
                cleanup(null);
            } else {
                if (stage.equals("seed")) challenge();
                JSONObject capabilities = new JSONObject(request("/test/capabilities", null, false));
                assertTrue(capabilities.getBoolean("compatible"));
                assertTrue(capabilities.getBoolean("coreReachable"));
                assertTrue(capabilities.getBoolean("mappedGoogleSession"));
                assertFalse(capabilities.getBoolean("qualificationProxy"));
                assertEquals(config.getJSONObject("plan").getString("nodeVersion"), capabilities.getString("nodeVersion"));
                assertEquals(exposed, new JSONObject(request("/featureFlags", null, false)).getBoolean("sessionJwt"));
                configure();
                if (stage.equals("seed")) seed();
                else {
                    assertEquals("check-initial", stage);
                    assertEquals("candidate", BuildConfig.SDK_NAME);
                    assertEquals("seeded", receipt.getString("phase", null));
                    continueSession();
                    persist("continued");
                }
            }
            writeEvidence(stage);
        } catch (Throwable failure) {
            if (owns && !stage.equals("cleanup")) cleanup(failure);
            if (failure instanceof Error) throw (Error) failure;
            throw (Exception) failure;
        } finally {
            CookieHandler.setDefault(previous);
        }
    }

    private void configure() throws Exception {
        if (configured) return;
        SuperTokens.Builder builder = new SuperTokens.Builder(context, backend).tokenTransferMethod("cookie")
                .customHeaderProvider(new CustomHeaderProvider() {
                    @Override public Map<String, String> getRequestHeaders(RequestType type) {
                        headerRequests.put(type.name());
                        Map<String, String> headers = new HashMap<String, String>();
                        headers.put("fdi-version", fdi);
                        headers.put("x-st-test-run-id", config.optString("runId"));
                        headers.put("x-st-native-nonce", config.optString("nativeNonce"));
                        headers.put("x-st-native-stage", stageName);
                        return headers;
                    }
                });
        customize(builder);
        builder.build();
        configured = true;
    }

    private void seed() throws Exception {
        String requestId = config.getString("nativeNonce") + "-owner";
        assertTrue(receipt.edit().putString("pendingRequestId", requestId).commit());
        JSONObject owner = new JSONObject(request("/test/email-change-owner", new JSONObject().put("recipe", "google")
                .put("email", requestId + "@upgrade.example").put("requestId", requestId), false));
        String fixture = owner.getString("fixtureId");
        assertFalse(fixture.isEmpty());
        assertTrue(receipt.edit().putString("fixtureId", fixture).commit());
        String user = owner.getString("userId");
        assertFalse(user.isEmpty());
        assertTrue(owner.getBoolean("google"));
        assertTrue(receipt.edit().putString("userId", user).remove("pendingRequestId").commit());
        absent();
        assertTrue("Provisioning leaked cookies", cookies.getCookies().isEmpty());
        login();
        observe("publishedLogin", false);
        if (config.getString("flow").equals("REFRESH")) refresh();
        else signOut();
        boolean available = config.getString("flow").equals("REFRESH");
        if (available) {
            assertTrue(SuperTokens.doesSessionExist(context));
            assertEquals(user, SuperTokens.getUserId(context));
            assertEquals(exposed, SuperTokens.getAccessToken(context) != null);
            assertTrue(receipt.edit().putString("payload", SuperTokens.getAccessTokenPayloadSecurely(context).toString())
                    .putString("access", digest(SuperTokens.getAccessToken(context)))
                    .putString("cookieAccess", cookieDigest("sAccessToken"))
                    .putString("cookieRefresh", cookieDigest("sRefreshToken")).commit());
        } else absent();
        observe("publishedHandoff", !available);
        persist("seeded");
    }

    protected final void checkHandoff() throws Exception {
        if (config.getString("flow").equals("SIGN_OUT")) {
            absent();
            login();
        } else {
            assertTrue(SuperTokens.doesSessionExist(context));
            assertEquals(receipt.getString("userId", null), SuperTokens.getUserId(context));
            assertEquals(receipt.getString("access", null), digest(SuperTokens.getAccessToken(context)));
            assertEquals(receipt.getString("cookieAccess", null), cookieDigest("sAccessToken"));
            assertEquals(receipt.getString("cookieRefresh", null), cookieDigest("sRefreshToken"));
            assertEquals(new JSONObject(receipt.getString("payload", null)).toString(),
                    SuperTokens.getAccessTokenPayloadSecurely(context).toString());
        }
    }

    protected final void login() throws Exception {
        managed("/login", new JSONObject().put("userId", receipt.getString("userId", null)));
        assertTrue(SuperTokens.doesSessionExist(context));
        assertEquals(receipt.getString("userId", null), SuperTokens.getUserId(context));
        assertEquals(exposed, SuperTokens.getAccessToken(context) != null);
    }

    protected final void refresh() throws Exception {
        operations.put("sdk.refresh");
        assertTrue(SuperTokens.attemptRefreshingSession(context));
    }

    protected final void signOut() throws Exception {
        operations.put("sdk.signOut");
        SuperTokens.signOut(context);
        absent();
        JSONObject body = new JSONObject().put("fixtureId", receipt.getString("fixtureId", null))
                .put("handle", receipt.getString("loginHandle", null));
        JSONObject state = new JSONObject(request("/test/owner-session-state", body, false));
        assertTrue("Server did not recognize actual login session", state.getBoolean("owned"));
        assertFalse("Actual login session remains active after signout", state.getBoolean("active"));
        revocations.put(body.put("active", state.getBoolean("active")).put("owned", state.getBoolean("owned")));
    }

    private void challenge() throws Exception {
        JSONObject actual = new JSONObject(request("/test/native/challenge", new JSONObject()
                .put("challenge", config.getString("challenge")), false));
        assertEquals(1, actual.getInt("schema"));
        assertEquals("native-backend-challenge", actual.getString("kind"));
        for (String key : new String[]{"runId", "instance", "nativeNonce", "challenge", "qualifiedIdentitySha256",
                "dependenciesSha256", "coreManifestSha256"}) assertEquals(config.getString(key), actual.getString(key));
        JSONObject identity = actual.getJSONObject("identity");
        assertEquals(config.getJSONObject("plan").getString("nodeVersion"), identity.getString("nodeSdkVersion"));
        assertEquals(config.getJSONObject("plan").getString("coreVersion"), identity.getString("coreVersion"));
        assertEquals(config.getString("nodeSdkSha256"), identity.getString("sdkImplementationSha256"));
        assertEquals(config.getString("coreSha256"), identity.getString("coreSha256"));
        assertTrue(receipt.edit().putString("challengeResponse", actual.toString()).commit());
    }

    protected final void observe(String phase, boolean isAbsent) throws Exception {
        String version = null;
        if (!isAbsent) {
            String token = SuperTokens.getAccessToken(context);
            if (token == null) for (HttpCookie cookie : cookies.getCookies()) {
                if (cookie.getName().equals("sAccessToken")) token = cookie.getValue();
            }
            assertNotNull("No observed access token", token);
            String[] parts = token.split("\\.");
            assertEquals(3, parts.length);
            JSONObject header = new JSONObject(new String(Base64.decode(parts[0], Base64.URL_SAFE), "UTF-8"));
            if (header.has("version")) version = header.getString("version");
            else {
                assertTrue(header.has("kid"));
                assertEquals("JWT", header.getString("typ"));
                assertEquals("RS256", header.getString("alg"));
                version = "5";
            }
            assertTrue("Unrecognized token version", version.matches("[2-5]"));
        }
        String expected = phase.equals("publishedHandoff") && config.getString("flow").equals("SIGN_OUT")
                ? "ABSENT" : config.getJSONObject("plan").getJSONObject("phases").getString(phase);
        assertEquals(expected, isAbsent ? "ABSENT" : version);
        observed.put(phase, new JSONObject().put("status", isAbsent ? "ABSENT" : "PRESENT")
                .put("tokenVersion", isAbsent ? JSONObject.NULL : version));
    }

    protected final void absent() throws Exception {
        flush();
        assertFalse(SuperTokens.doesSessionExist(context));
        assertNull(SuperTokens.getAccessToken(context));
        SharedPreferences stored = context.getSharedPreferences(SESSION, 0);
        assertNull(stored.getString("st-storage-item-st-access-token", null));
        assertNull(stored.getString("st-storage-item-st-refresh-token", null));
        for (HttpCookie cookie : new SuperTokensPersistentCookieStore(context).getCookies()) {
            assertFalse("Usable session cookie remains", cookie.getName().equals("sAccessToken") || cookie.getName().equals("sRefreshToken"));
        }
    }

    private void cleanup(Throwable primary) throws Exception {
        CleanupRecovery.run(primary, new CleanupRecovery.Actions() {
            @Override public void signOut() throws Exception {
                configure();
                if (SuperTokens.doesSessionExist(context)) MatrixNativeStage.this.signOut();
            }
            @Override public void assertAbsent() throws Exception { absent(); }
            @Override public List<String> owners() {
                List<String> owners = new ArrayList<String>();
                if (receipt.contains("fixtureId")) owners.add(receipt.getString("fixtureId", null));
                return owners;
            }
            @Override public void cleanupOwner(String id) throws Exception {
                assertTrue(new JSONObject(request("/test/cleanup-owner", new JSONObject().put("fixtureId", id), false)).getBoolean("cleaned"));
            }
            @Override public void acknowledge(String id) {
                assertEquals(id, receipt.getString("fixtureId", null));
                assertTrue(receipt.edit().remove("fixtureId").commit());
            }
            @Override public void assertNoPendingCreation() {
                assertFalse("Unresolved creation; reconcile exact pendingRequestId, never retry", receipt.contains("pendingRequestId"));
            }
            @Override public void complete() throws Exception {
                persist("cleaned");
                assertTrue(receipt.edit().putBoolean("cleanupDone", true).commit());
            }
        });
    }

    private void persist(String phase) throws Exception {
        flush();
        assertTrue(receipt.edit().putString("phase", phase).putString("process", process)
                .putString("observed", observed.toString()).commit());
    }

    private void flush() {
        assertTrue(context.getSharedPreferences(SESSION, 0).edit().commit());
        assertTrue(context.getSharedPreferences(COOKIE, 0).edit().commit());
    }

    private String cookieDigest(String name) throws Exception {
        for (HttpCookie cookie : cookies.getCookies()) if (name.equals(cookie.getName())) return digest(cookie.getValue());
        fail("Missing " + name);
        return null;
    }

    private String digest(String value) throws Exception {
        if (value == null) return "";
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
        StringBuilder result = new StringBuilder();
        for (byte item : hash) result.append(String.format("%02x", item & 255));
        return result.toString();
    }

    protected final String managed(String path, JSONObject body) throws Exception { return request(path, body, true); }

    private String request(final String path, final JSONObject body, boolean managed) throws Exception {
        operations.put((managed ? "managed:" : "isolated:") + path);
        CookieHandler previous = CookieHandler.getDefault();
        // Fixture responses contain credentials. No SDK wrapper and no session cookie store may see them.
        if (!managed) CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_NONE));
        HttpURLConnection connection = null;
        try {
            SuperTokensHttpURLConnection.PreConnectCallback prepare = new SuperTokensHttpURLConnection.PreConnectCallback() {
                @Override public void doAction(HttpURLConnection c) throws java.io.IOException {
                    c.setConnectTimeout(10000); c.setReadTimeout(10000); c.setInstanceFollowRedirects(false);
                    c.setRequestProperty("fdi-version", fdi);
                    c.setRequestProperty("x-st-test-run-id", config.optString("runId"));
                    c.setRequestProperty("x-st-native-nonce", config.optString("nativeNonce"));
                    c.setRequestProperty("x-st-native-stage", stageName);
                    if (path.equals("/login")) c.setRequestProperty("x-st-native-fixture-id", receipt.getString("fixtureId", null));
                    if (body != null) {
                        c.setRequestMethod("POST"); c.setDoOutput(true);
                        c.setRequestProperty("Content-Type", "application/json");
                        OutputStream output = c.getOutputStream();
                        try { output.write(body.toString().getBytes("UTF-8")); } finally { output.close(); }
                    }
                }
            };
            URL url = new URL(backend + path);
            if (managed) connection = SuperTokensHttpURLConnection.newRequest(url, prepare);
            else { connection = (HttpURLConnection) url.openConnection(); prepare.doAction(connection); }
            assertEquals("Unexpected status " + path, 200, connection.getResponseCode());
            if (managed && path.equals("/login")) {
                String handle = connection.getHeaderField("x-st-native-session-handle");
                assertTrue("Missing actual owned login handle", handle != null && !handle.isEmpty());
                assertTrue(receipt.edit().putString("loginHandle", handle).commit());
                assertEquals(exposed, connection.getHeaderField("st-access-token") != null);
                assertNull(connection.getHeaderField("st-refresh-token"));
                if (exposed) assertEquals(connection.getHeaderField("st-access-token"), SuperTokens.getAccessToken(context));
            }
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    assertTrue("Oversized fixture response", output.size() + count <= 65536);
                    output.write(buffer, 0, count);
                }
            } finally { input.close(); }
            return new String(output.toByteArray(), "UTF-8");
        } finally {
            if (connection != null) connection.disconnect();
            CookieHandler.setDefault(previous);
        }
    }

    private void writeEvidence(String stage) throws Exception {
        JSONObject evidence = new JSONObject().put("schema", 1).put("kind", "matrix-native-stage")
                .put("stage", stage).put("flavor", BuildConfig.SDK_NAME).put("process", process)
                .put("binding", config).put("observed", observed).put("assertionsPassed", true)
                .put("installedApplicationSha256", fileDigest(context.getPackageCodePath()))
                .put("installedInstrumentationSha256", fileDigest(InstrumentationRegistry.getInstrumentation().getContext().getPackageCodePath()))
                .put("cleanupDone", receipt.getBoolean("cleanupDone", false))
                .put("clientOperations", operations).put("customHeaderRequests", headerRequests)
                .put("challengeResponse", new JSONObject(receipt.getString("challengeResponse", null)))
                .put("revocationResponses", revocations)
                .put("wireVerified", false).put("backendIdentityVerified", false).put("loginRevocationVerified", false);
        assertFalse("Existing stage output", evidenceFile.exists());
        FileOutputStream output = new FileOutputStream(evidenceFile);
        try { output.write(evidence.toString().getBytes("UTF-8")); output.getFD().sync(); }
        finally { output.close(); }
    }

    private String fileDigest(String path) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        InputStream input = new FileInputStream(path);
        try {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
        } finally { input.close(); }
        StringBuilder value = new StringBuilder();
        for (byte item : hash.digest()) value.append(String.format("%02x", item & 255));
        return value.toString();
    }

    private static String required(Bundle args, String name) {
        String value = args.getString(name);
        assertTrue("Missing " + name, value != null && !value.isEmpty());
        return value;
    }
}
