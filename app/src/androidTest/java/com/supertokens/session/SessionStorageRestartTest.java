package com.supertokens.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.Charset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Run one seed method, force-stop the target, then run its check method without clearing data. */
public class SessionStorageRestartTest {
    private static final String PROCESS = UUID.randomUUID().toString();
    private static final String RECEIPT = "session-restart-test-receipt";
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private void configure() throws Exception {
        configure("header");
    }

    private void configure(String transfer) throws Exception {
        new SuperTokens.Builder(context, "https://restart-fixture.invalid").tokenTransferMethod(transfer).build();
    }

    private SharedPreferences receipt() { return context.getSharedPreferences(RECEIPT, Context.MODE_PRIVATE); }

    private SessionSnapshot seed() throws Exception {
        SuperTokens.resetForTests();
        assertTrue(context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE).edit().clear().commit());
        configure();
        SessionCommitResult result = SuperTokens.installSession(context,
                SuperTokens.getSessionSnapshot(context).getOwner(), tokens(), "restart-correlation");
        assertEquals(SessionCommitResult.Status.SUCCESS, result.getStatus());
        return result.getSnapshot();
    }

    private void saveReceipt(SessionSnapshot snapshot) {
        assertTrue(receipt().edit().clear().putString("process", PROCESS)
                .putString("adoption", snapshot.getAdoptionId()).putString("configuration", snapshot.getConfigurationId())
                .putString("status", snapshot.getStatus().name()).putLong("revision", snapshot.getRevision())
                .putLong("accessRevision", snapshot.getAccessTokenRevision())
                .putLong("refreshRevision", snapshot.getRefreshTokenRevision()).commit());
    }

    @Test public void testSeedAvailable() throws Exception { saveReceipt(seed()); }

    @Test public void testSeedExpiredV2() throws Exception { seedLegacy(false); }

    @Test public void testCheckExpiredV2AfterProcessRestart() throws Exception {
        SessionSnapshot actual = checkReceipt(SessionSnapshot.Status.AVAILABLE);
        assertEquals("legacy-handle", actual.getSessionHandle());
        assertEquals("legacy-user", actual.getUserId());
        assertEquals("public", actual.getTenantId());
        assertEquals(1000L, actual.getAccessTokenExpiry());
        assertEquals(legacyTokens(false).getAccessToken(), actual.getTokens().getAccessToken());
        assertEquals("legacy-refresh", actual.getTokens().getRefreshToken());
        assertEquals(0, actual.getAccessTokenPayload().length());
        assertFalse("Expired persisted tuple must not become a valid strict import",
                SuperTokens.installSession(context, actual.getOwner(), legacyTokens(false)).isSuccess());
    }

    @Test public void testSeedCookieFrontOnly() throws Exception { seedLegacy(true); }

    @Test public void testCheckCookieFrontOnlyAfterProcessRestart() throws Exception {
        SessionSnapshot actual = checkReceipt(SessionSnapshot.Status.AVAILABLE, "cookie");
        assertNull("Cookie state must not export header credentials", actual.getTokens());
        assertEquals("legacy-user", actual.getUserId());
        assertEquals("legacy-user", FrontToken.getToken(context).getString("uid"));
        assertEquals(4102444800000L, FrontToken.getToken(context).getLong("ate"));
        assertEquals(0, FrontToken.getToken(context).getJSONObject("up").length());
        assertNull(SuperTokens.getAccessToken(context));
        assertEquals("legacy-csrf", AntiCSRF.getToken(context,
                SessionState.lastAccessTokenUpdate(actual.getOwner())));
    }

    private void seedLegacy(boolean cookie) throws Exception {
        SuperTokens.resetForTests();
        SessionTokens tokens = legacyTokens(cookie);
        SharedPreferences.Editor editor = context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY,
                Context.MODE_PRIVATE).edit().clear()
                .putString(Constants.FRONT_TOKEN_PREFS_KEY, tokens.getFrontToken())
                .putString(Constants.CSRF_TOKEN_PREFS_KEY, "legacy-csrf")
                .putString("st-storage-item-" + Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY, "legacy-marker");
        if (!cookie) {
            editor.putString("st-storage-item-" + Constants.ACCESS_TOKEN_PREFS_KEY, tokens.getAccessToken())
                    .putString("st-storage-item-" + Constants.REFRESH_TOKEN_PREFS_KEY, tokens.getRefreshToken());
        }
        assertTrue(editor.commit());
        configure(cookie ? "cookie" : "header");
        SessionSnapshot hydrated = SuperTokens.getSessionSnapshot(context);
        assertEquals(SessionSnapshot.Status.AVAILABLE, hydrated.getStatus());
        saveReceipt(hydrated);
    }

    private SessionTokens legacyTokens(boolean cookie) throws Exception {
        long expiry = cookie ? 4102444800000L : 1000L;
        JSONObject front = new JSONObject().put("uid", "legacy-user").put("ate", expiry)
                .put("up", new JSONObject());
        JSONObject payload = new JSONObject().put("sessionHandle", "legacy-handle")
                .put("userId", "legacy-user").put("expiryTime", expiry).put("userData", new JSONObject());
        String access = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"version\":\"2\"}", true)
                + "." + encode(payload.toString(), true) + ".c2ln";
        return new SessionTokens(cookie ? null : access, cookie ? null : "legacy-refresh",
                encode(front.toString(), false), "legacy-csrf");
    }

    @Test public void testRealPreferencesWarmedCacheAndConcurrentSnapshots() throws Exception {
        SessionSnapshot original = seed();
        assertNotNull(FrontToken.getToken(context));
        assertEquals("restart-csrf", AntiCSRF.getToken(context,
                SessionState.lastAccessTokenUpdate(original.getOwner())));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            started.countDown();
            try {
                while (finish.getCount() != 0) {
                    SessionSnapshot snapshot = SuperTokens.getSessionSnapshot(context);
                    assertEquals(SessionSnapshot.Status.AVAILABLE, snapshot.getStatus());
                    assertEquals(snapshot.getTokens().getFrontToken(),
                            tokens().getFrontToken());
                    assertEquals("restart-handle", snapshot.getSessionHandle());
                    assertEquals("primary-user", snapshot.getUserId());
                    assertEquals(tokens().getAccessToken(), snapshot.getTokens().getAccessToken());
                }
            } catch (Throwable error) { failure.set(error); }
        });
        reader.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        long startedAt = System.nanoTime();
        try {
            for (int i = 0; i < 20; i++) {
                SessionTokens base = tokens();
                SessionTokens next = new SessionTokens(base.getAccessToken(), base.getRefreshToken(),
                        base.getFrontToken(), (i & 1) == 0 ? null : "restart-csrf");
                SessionCommitResult result = SuperTokens.installSession(context,
                        SuperTokens.getSessionSnapshot(context).getOwner(), next);
                assertTrue(result.isSuccess());
                assertEquals(next.getAntiCSRF(), AntiCSRF.getToken(context,
                        SessionState.lastAccessTokenUpdate(result.getSnapshot().getOwner())));
            }
            JSONObject clone = SuperTokens.getSessionSnapshot(context).getAccessTokenPayload();
            clone.put("sub", "mutated-copy");
            assertEquals("primary-user", SuperTokens.getSessionSnapshot(context)
                    .getAccessTokenPayload().getString("sub"));
        } finally {
            finish.countDown();
            reader.join(5000);
            assertFalse(reader.isAlive());
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        System.out.println("SESSION_STORAGE_20_COMMITS_NS=" + (System.nanoTime() - startedAt));
    }

    @Test public void testCheckAvailableAfterProcessRestart() throws Exception {
        SessionSnapshot actual = checkReceipt(SessionSnapshot.Status.AVAILABLE);
        assertEquals("restart-correlation", actual.getAdoptionCorrelation());
        assertEquals("restart-handle", actual.getSessionHandle());
        assertEquals("primary-user", actual.getUserId());
        assertEquals("public", actual.getTenantId());
        assertEquals(tokens().getAccessToken(), actual.getTokens().getAccessToken());
        assertEquals(tokens().getRefreshToken(), actual.getTokens().getRefreshToken());
        assertEquals(tokens().getFrontToken(), actual.getTokens().getFrontToken());
        assertEquals("restart-csrf", actual.getTokens().getAntiCSRF());
        assertEquals(tokens().getAccessToken(), SuperTokens.getAccessToken(context));
    }

    @Test public void testSeedCleared() throws Exception {
        SessionSnapshot installed = seed();
        SessionCommitResult cleared = SuperTokens.clearSession(context, installed.getOwner());
        assertTrue(cleared.isSuccess());
        assertFalse(installed.getAdoptionId().equals(cleared.getSnapshot().getAdoptionId()));
        saveReceipt(cleared.getSnapshot());
    }

    @Test public void testCheckClearedAfterProcessRestart() throws Exception {
        SessionSnapshot actual = checkReceipt(SessionSnapshot.Status.ABSENT);
        assertNull(actual.getTokens());
        assertNull(actual.getAdoptionCorrelation());
        assertFalse(SuperTokens.doesSessionExist(context));
        assertNull(SuperTokens.getAccessToken(context));
        SharedPreferences disk = context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE);
        assertNull(disk.getString("st-storage-item-" + Constants.ACCESS_TOKEN_PREFS_KEY, null));
        assertNull(disk.getString("st-storage-item-" + Constants.REFRESH_TOKEN_PREFS_KEY, null));
        assertNull(disk.getString(Constants.CSRF_TOKEN_PREFS_KEY, null));
    }

    private SessionSnapshot checkReceipt(SessionSnapshot.Status expected) throws Exception {
        return checkReceipt(expected, "header");
    }

    private SessionSnapshot checkReceipt(SessionSnapshot.Status expected, String transfer) throws Exception {
        assertTrue("run matching seed in an earlier process", receipt().contains("process"));
        assertFalse("requires a real process restart", PROCESS.equals(receipt().getString("process", null)));
        assertEquals(expected.name(), receipt().getString("status", null));
        configure(transfer);
        SessionSnapshot actual = SuperTokens.getSessionSnapshot(context);
        assertEquals(expected, actual.getStatus());
        assertEquals(receipt().getString("adoption", null), actual.getAdoptionId());
        assertEquals(receipt().getString("configuration", null), actual.getConfigurationId());
        assertEquals(receipt().getLong("revision", -1), actual.getRevision());
        assertEquals(receipt().getLong("accessRevision", -1), actual.getAccessTokenRevision());
        assertEquals(receipt().getLong("refreshRevision", -1), actual.getRefreshTokenRevision());
        return actual;
    }

    private SessionTokens tokens() throws Exception {
        JSONObject payload = new JSONObject().put("sessionHandle", "restart-handle").put("sub", "primary-user")
                .put("rsub", "recipe-user").put("tId", "public").put("exp", 4102444800L).put("iat", 1700000000L);
        String access = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"version\":\"5\"}", true)
                + "." + encode(payload.toString(), true) + ".c2lnbmF0dXJl";
        String front = encode(new JSONObject().put("uid", "primary-user").put("ate", 4102444800000L)
                .put("up", payload).toString(), false);
        return new SessionTokens(access, "restart-refresh", front, "restart-csrf");
    }

    private String encode(String value, boolean url) {
        return Base64.encodeToString(value.getBytes(Charset.forName("UTF-8")),
                url ? Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING : Base64.NO_WRAP);
    }
}
