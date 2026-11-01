package com.supertokens.upgrade;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import com.supertokens.session.SessionSnapshot;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensInterceptor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Candidate-only process-death boundary; never returns the held network response to the SDK. */
public class ProcessDeathTest {
    private static final String PROCESS = UUID.randomUUID().toString();
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private Context context;
    private SharedPreferences receipt;
    private SharedPreferences session;
    private String run, scenario, backend;
    private OkHttpClient raw, managed;

    @Test public void runStage() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        run = required(args, "runId");
        scenario = required(args, "scenario");
        backend = required(args, "backend");
        String stage = required(args, "stage");
        assertEquals("candidate", BuildConfig.SDK_NAME);
        assertTrue(scenario.equals("login") || scenario.equals("refresh"));
        receipt = context.getSharedPreferences("death-receipt", 0);
        session = context.getSharedPreferences("supertokens-android-shared-preferences", 0);
        raw = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS).followRedirects(false).retryOnConnectionFailure(false).build();
        new SuperTokens.Builder(context, backend).tokenTransferMethod("header").build();
        managed = raw.newBuilder().addInterceptor(new SuperTokensInterceptor()).build();
        try {
            if (stage.equals("seed")) seed();
            else {
                assertEquals(run, receipt.getString("runId", null));
                assertEquals(scenario, receipt.getString("scenario", null));
                assertEquals(backend, receipt.getString("backend", null));
                assertFalse(PROCESS.equals(receipt.getString("process", null)));
                if (stage.equals("hold")) hold();
                else if (stage.equals("check")) check();
                else if (stage.equals("cleanup")) cleanup();
                else fail("Unknown stage");
            }
        } finally {
            raw.connectionPool().evictAll();
            raw.dispatcher().executorService().shutdownNow();
            managed.connectionPool().evictAll();
            managed.dispatcher().executorService().shutdownNow();
        }
    }

    private void seed() throws Exception {
        assertTrue("Prior owner must be cleaned first", !receipt.contains("runId") || receipt.getBoolean("cleanupDone", false));
        assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        JSONObject capabilities = json(raw, "/test/capabilities", null);
        assertTrue(capabilities.getBoolean("compatible"));
        assertTrue(capabilities.getBoolean("mappedGoogleSession"));
        File checkpoint = new File(context.getFilesDir(), "death-checkpoint.json");
        assertTrue(!checkpoint.exists() || checkpoint.delete());
        String requestId = run + "-" + scenario;
        assertTrue(receipt.edit().clear().putString("runId", run).putString("scenario", scenario)
                .putString("backend", backend).putString("pendingRequestId", requestId)
                .putBoolean("cleanupDone", false).commit());
        JSONObject owner = json(raw, "/test/email-change-owner", new JSONObject().put("recipe", "google")
                .put("email", requestId + "@death.example").put("requestId", requestId));
        assertTrue(receipt.edit().putString("fixtureId", owner.getString("fixtureId"))
                .putString("userId", owner.getString("userId")).remove("pendingRequestId").commit());
        // The only owner is durable before any deliberately killed stage starts. /login creates sessions, not owners.
        if (scenario.equals("refresh")) {
            login(managed);
            JSONObject protectedBody = json(managed, "/test/protected", null);
            assertEquals(receipt.getString("userId", null), protectedBody.getString("userId"));
            assertTrue(receipt.edit().putString("handle", protectedBody.getString("handle")).commit());
        }
        assertTrue(session.edit().commit());
        assertTrue(receipt.edit().putString("sessionDigest", digest(session.getAll()))
                .putString("phase", "seeded").putString("process", PROCESS).commit());
    }

    private void hold() throws Exception {
        assertEquals("seeded", receipt.getString("phase", null));
        assertFalse(receipt.contains("pendingRequestId"));
        assertTrue(receipt.contains("fixtureId"));
        assertEquals(receipt.getString("sessionDigest", null), digest(session.getAll()));
        final Map<String, ?> before = new LinkedHashMap<String, Object>(session.getAll());
        final SessionSnapshot snapshot = SuperTokens.getSessionSnapshot(context);
        final String oldRefresh = session.getString("st-storage-item-st-refresh-token", null);
        OkHttpClient holding = managed.newBuilder().addNetworkInterceptor(new Interceptor() {
            @Override public Response intercept(Chain chain) throws IOException {
                Response response = chain.proceed(chain.request());
                String path = chain.request().url().encodedPath();
                String target = scenario.equals("login") ? "/login" : "/auth/session/refresh";
                if (!path.equals(target)) return response;
                try {
                    assertEquals(200, response.code());
                    assertNotNull(response.header("st-access-token"));
                    assertNotNull(response.header("front-token"));
                    String child = response.header("st-refresh-token");
                    assertNotNull(child);
                    if (scenario.equals("refresh")) {
                        assertNotNull(oldRefresh);
                        assertFalse("Core must rotate", child.equals(oldRefresh));
                        assertTrue("SDK must send retained parent", ("Bearer " + oldRefresh).equals(chain.request().header("Authorization")));
                    }
                    assertSame(snapshot, SuperTokens.getSessionSnapshot(context));
                    assertTrue("Network response reached storage prematurely", before.equals(session.getAll()));
                    assertTrue(receipt.edit().putString("phase", "held").putString("process", PROCESS).commit());
                    checkpoint();
                    // Only the host may end this phase successfully, by killing this exact target process.
                    new CountDownLatch(1).await(180, TimeUnit.SECONDS);
                    throw new IOException("Hold watchdog expired; this is not a passing kill scenario");
                } catch (Exception error) {
                    response.close();
                    throw new IOException("Hold failed", error);
                }
            }
        }).build();
        try {
            if (scenario.equals("login")) login(holding);
            else json(holding, "/throw-401", null);
            fail("Held call unexpectedly returned");
        } finally {
            holding.connectionPool().evictAll();
            holding.dispatcher().executorService().shutdownNow();
        }
    }

    private void checkpoint() throws Exception {
        JSONObject value = new JSONObject().put("runId", run).put("scenario", scenario).put("stage", "hold")
                .put("phase", "network-response-before-adoption").put("process", PROCESS)
                .put("pid", android.os.Process.myPid());
        File temporary = new File(context.getFilesDir(), "death-checkpoint.tmp");
        FileOutputStream output = new FileOutputStream(temporary);
        try { output.write(value.toString().getBytes(UTF8)); output.getFD().sync(); }
        finally { output.close(); }
        assertTrue(temporary.renameTo(new File(context.getFilesDir(), "death-checkpoint.json")));
    }

    private void check() throws Exception {
        assertEquals("held", receipt.getString("phase", null));
        assertEquals("Lost response must not change persisted tuple", receipt.getString("sessionDigest", null), digest(session.getAll()));
        if (scenario.equals("login")) {
            assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
            login(managed);
        } else {
            assertEquals(SessionSnapshot.Status.AVAILABLE, SuperTokens.getSessionSnapshot(context).getStatus());
            assertTrue("Real Core retained-parent recovery failed", SuperTokens.attemptRefreshingSession(context));
        }
        assertEquals(SessionSnapshot.Status.AVAILABLE, SuperTokens.getSessionSnapshot(context).getStatus());
        JSONObject actual = json(managed, "/test/protected", null);
        assertEquals(receipt.getString("userId", null), actual.getString("userId"));
        if (scenario.equals("refresh")) assertEquals(receipt.getString("handle", null), actual.getString("handle"));
        assertTrue(SuperTokens.attemptRefreshingSession(context));
        assertEquals(actual.getString("handle"), json(managed, "/test/protected", null).getString("handle"));
        assertTrue(session.edit().commit());
        assertTrue(receipt.edit().putString("phase", "checked").putString("process", PROCESS).commit());
    }

    private void cleanup() throws Exception {
        assertFalse("Unknown creation outcome: resolve pending request with backend owner", receipt.contains("pendingRequestId"));
        // Backend deletion remains possible even if the failed scenario left local recovery broken.
        assertTrue(json(raw, "/test/cleanup-owner", new JSONObject().put("fixtureId", receipt.getString("fixtureId", null))).getBoolean("cleaned"));
        if (SuperTokens.doesSessionExist(context)) SuperTokens.signOut(context);
        assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        assertTrue(session.edit().commit());
        assertTrue(receipt.edit().putBoolean("cleanupDone", true).putString("phase", "cleaned").putString("process", PROCESS).commit());
    }

    private void login(OkHttpClient client) throws Exception {
        Response response = client.newCall(request("/login", new JSONObject().put("userId", receipt.getString("userId", null)))).execute();
        try { assertEquals(200, response.code()); } finally { response.close(); }
    }

    private Request request(String path, JSONObject body) {
        Request.Builder builder = new Request.Builder().url(backend + path).header("st-auth-mode", "header");
        if (body != null) builder.post(RequestBody.create(MediaType.parse("application/json"), body.toString()));
        return builder.build();
    }

    private JSONObject json(OkHttpClient client, String path, JSONObject body) throws Exception {
        Response response = client.newCall(request(path, body)).execute();
        try { assertEquals("HTTP failure: " + path, 200, response.code()); return new JSONObject(response.body().string()); }
        finally { response.close(); }
    }

    private String digest(Map<String, ?> values) throws Exception {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, ?> entry : new TreeMap<String, Object>(values).entrySet()) {
            canonical.append(JSONObject.quote(entry.getKey())).append(':')
                    .append(entry.getValue().getClass().getName()).append(':')
                    .append(JSONObject.quote(String.valueOf(entry.getValue()))).append('\n');
        }
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(UTF8));
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private String required(Bundle args, String key) {
        String value = args.getString(key);
        assertTrue("Missing " + key, value != null && !value.isEmpty());
        return value;
    }
}
