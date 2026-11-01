package com.supertokens.upgrade;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.test.platform.app.InstrumentationRegistry;
import com.supertokens.session.SessionSnapshot;
import com.supertokens.session.SessionTokens;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.Utils;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic local controls only. Each seed method requires a fresh isolated app process/data. */
public class OwnedRecoveryDeviceControlsTest {
    private Context context;
    private SharedPreferences storage;
    private SharedPreferences cookies;
    private SharedPreferences receipt;
    private int clearCalls;

    @Before public void requireIsolatedNamespace() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Fail before opening preferences or configuring the SDK in the retained upgrade app.
        assertEquals("com.supertokens.recoverycontrols", context.getPackageName());
        assertEquals("candidate", BuildConfig.SDK_NAME);
        assertEquals("synthetic-exclusive", InstrumentationRegistry.getArguments().getString("recoveryControls"));
        storage = context.getSharedPreferences("supertokens-android-shared-preferences", 0);
        cookies = context.getSharedPreferences("supertokens-cookie-shared-prefs", 0);
        receipt = context.getSharedPreferences("recovery-controls", 0);
    }

    private void startFresh() throws java.net.MalformedURLException {
        assertTrue("Use fresh isolated app data, never reset retained upgrade data", storage.getAll().isEmpty());
        assertTrue(cookies.getAll().isEmpty());
        assertTrue(receipt.getAll().isEmpty());
        configure();
        assertEquals(SessionSnapshot.Status.ABSENT, snapshot().getStatus());
    }

    private void configure() throws java.net.MalformedURLException {
        // No server exists or is needed: only local SDK transitions are exercised.
        new SuperTokens.Builder(context, "http://127.0.0.1:1").tokenTransferMethod("header").build();
    }

    private SessionSnapshot snapshot() { return SuperTokens.getSessionSnapshot(context); }

    private SessionSnapshot install(String handle) throws Exception {
        assertTrue(SuperTokens.installSession(context, snapshot().getOwner(), tokens(handle)).isSuccess());
        SessionSnapshot installed = snapshot();
        assertEquals(SessionSnapshot.Status.AVAILABLE, installed.getStatus());
        assertEquals(handle, installed.getSessionHandle());
        return installed;
    }

    private OwnedRecoveryGuard.Captured capture(final SessionSnapshot captured, final SessionTokens replacement) {
        return new OwnedRecoveryGuard.Captured() {
            public String status() { return captured.getStatus().name(); }
            public String userId() { return captured.getUserId(); }
            public String handle() { return captured.getSessionHandle(); }
            public boolean clearCapturedOwner() {
                clearCalls++;
                // Deterministic interleaving after guard validation, before the real conditional clear.
                if (replacement != null) {
                    assertTrue(SuperTokens.installSession(context, captured.getOwner(), replacement).isSuccess());
                }
                return SuperTokens.clearSession(context, captured.getOwner()).isSuccess();
            }
        };
    }

    private void refuse(OwnedRecoveryGuard.Captured captured, String user, String handle, String reason) {
        try {
            OwnedRecoveryGuard.recover(captured, user, handle, cookies.getAll());
        } catch (AssertionError expected) {
            assertTrue("Unexpected refusal: " + expected.getMessage(), expected.getMessage().contains(reason));
            return;
        }
        fail("Recovery unexpectedly accepted unsafe state");
    }

    @Test public void capturedOwnerCannotClearInstalledReplacement() throws Exception {
        startFresh();
        SessionSnapshot a = install("synthetic-A");
        SessionTokens b = tokens("synthetic-B");

        refuse(capture(a, b), a.getUserId(), a.getSessionHandle(), "Captured owner changed");

        assertEquals(1, clearCalls);
        assertEquals(SessionSnapshot.Status.AVAILABLE, snapshot().getStatus());
        assertEquals("synthetic-B", snapshot().getSessionHandle());
        assertEquals(b.getAccessToken(), SuperTokens.getAccessToken(context));
        assertEquals(b.getAccessToken(), storage.getString("st-storage-item-st-access-token", null));
        assertEquals(b.getRefreshToken(), storage.getString("st-storage-item-st-refresh-token", null));
    }

    @Test public void persistedCookieResidueRefusesEvenMatchingOwner() throws Exception {
        startFresh();
        SessionSnapshot a = install("synthetic-A");
        // Raw native persistence, deliberately not a cookie-store read (which can expire/delete).
        assertTrue(cookies.edit().putString("synthetic-residue", "opaque-cookie-value").commit());
        Map<String, ?> beforeCookies = new HashMap<String, Object>(cookies.getAll());
        Map<String, ?> beforeStorage = new HashMap<String, Object>(storage.getAll());

        refuse(capture(a, null), a.getUserId(), a.getSessionHandle(), "Cookie residue");

        assertEquals(0, clearCalls);
        assertEquals(beforeCookies, cookies.getAll());
        assertEquals(beforeStorage, storage.getAll());
        assertEquals(a.getSessionHandle(), snapshot().getSessionHandle());
    }

    @Test public void partialLegacyCredentialsRefuseAmbiguousIdentity() throws java.net.MalformedURLException {
        startFresh();
        Utils.storeInStorage("st-access-token", "synthetic-partial-access", context);
        SessionSnapshot ambiguous = snapshot();
        assertEquals(SessionSnapshot.Status.UNAVAILABLE, ambiguous.getStatus());
        Map<String, ?> before = new HashMap<String, Object>(storage.getAll());

        refuse(capture(ambiguous, null), "synthetic-user", "synthetic-A", "Ambiguous local status");

        assertEquals(0, clearCalls);
        assertEquals(before, storage.getAll());
        assertEquals("synthetic-partial-access", storage.getString("st-storage-item-st-access-token", null));
        assertEquals(SessionSnapshot.Status.UNAVAILABLE, snapshot().getStatus());
    }

    @Test public void mismatchedRecordedIdentityPreservesAvailableOwner() throws Exception {
        startFresh();
        SessionSnapshot a = install("synthetic-A");
        Map<String, ?> before = new HashMap<String, Object>(storage.getAll());

        refuse(capture(a, null), "different-user", a.getSessionHandle(), "does not match");
        refuse(capture(a, null), a.getUserId(), "different-handle", "does not match");

        assertEquals(0, clearCalls);
        assertEquals(before, storage.getAll());
        assertEquals(a.getSessionHandle(), snapshot().getSessionHandle());
    }

    @Test public void exactOwnerClearPersistsAbsence() throws Exception {
        startFresh();
        SessionSnapshot a = install("synthetic-A");

        OwnedRecoveryGuard.recover(capture(a, null), a.getUserId(), a.getSessionHandle(), cookies.getAll());

        assertEquals(1, clearCalls);
        assertAbsent();
        assertTrue(receipt.edit().putString("seedPid", Integer.toString(android.os.Process.myPid()))
                .putString("run", requiredRun()).putString("nonce", UUID.randomUUID().toString()).commit());
    }

    @Test public void clearedSessionRemainsAbsentAfterProcessRestart() throws java.net.MalformedURLException {
        assertEquals(requiredRun(), receipt.getString("run", null));
        assertNotNull(receipt.getString("nonce", null));
        assertNotEquals("Must restart target process between stages", receipt.getString("seedPid", null),
                Integer.toString(android.os.Process.myPid()));
        // Check raw durable credentials before initialization can repair or normalize them.
        assertRawAbsent();
        configure();
        assertAbsent();
    }

    private String requiredRun() {
        String run = InstrumentationRegistry.getArguments().getString("recoveryRun");
        assertNotNull(run);
        assertFalse(run.isEmpty());
        return run;
    }

    private void assertAbsent() {
        assertEquals(SessionSnapshot.Status.ABSENT, snapshot().getStatus());
        assertNull(SuperTokens.getAccessToken(context));
        try {
            SuperTokens.getUserId(context);
            fail("Absent session returned a user");
        } catch (IllegalStateException expected) { }
        assertRawAbsent();
    }

    private void assertRawAbsent() {
        assertNull(storage.getString("st-storage-item-st-access-token", null));
        assertNull(storage.getString("st-storage-item-st-refresh-token", null));
        assertNull(storage.getString("supertokens-android-fronttoken-key", null));
        assertNull(storage.getString("supertokens-android-anticsrf-key", null));
        assertTrue(cookies.getAll().isEmpty());
    }

    private static SessionTokens tokens(String handle) throws Exception {
        JSONObject payload = new JSONObject().put("sessionHandle", handle).put("sub", "synthetic-user")
                .put("rsub", "synthetic-recipe-user").put("tId", "public")
                .put("exp", 4102444800L).put("iat", 1700000000L);
        String access = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"version\":\"5\"}", true)
                + "." + encode(payload.toString(), true) + ".c2lnbmF0dXJl";
        String front = encode(new JSONObject().put("uid", "synthetic-user").put("ate", 4102444800000L)
                .put("up", payload).toString(), false);
        return new SessionTokens(access, "synthetic-refresh-" + handle, front, "synthetic-csrf");
    }

    private static String encode(String value, boolean url) throws Exception {
        return Base64.encodeToString(value.getBytes("UTF-8"), url
                ? Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING : Base64.NO_WRAP);
    }
}
