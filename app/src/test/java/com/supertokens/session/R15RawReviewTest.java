package com.supertokens.session;

import android.content.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class R15RawReviewTest extends SessionTestFixture {
    private SessionSnapshot correlated() throws Exception {
        return SuperTokens.installSession(context, SuperTokens.getSessionSnapshot(context).getOwner(),
                tokens("strict", "before"), "retained-correlation").getSnapshot();
    }

    @Test public void preinitUnrelatedEditPreservesAllStrictMetadataAndCoherentStartupDoesNotWrite() throws Exception {
        SessionSnapshot before = correlated();
        Map<String, Object> durable = preferenceStore.durableValues();
        SuperTokens.resetForTests();
        Utils.storeInStorage("custom", "value", context);
        Map<String, Object> expected = new HashMap<>(durable);
        expected.put("st-storage-item-custom", "value");
        assertEquals(expected, preferenceStore.durableValues());
        int commits = preferenceStore.commits;
        initializeSession();
        assertEquals(commits, preferenceStore.commits);
        SessionSnapshot loaded = SuperTokens.getSessionSnapshot(context);
        assertEquals(before.getAdoptionCorrelation(), loaded.getAdoptionCorrelation());
        assertEquals(before.getAdoptionId(), loaded.getAdoptionId());
        assertEquals(before.getRevision(), loaded.getRevision());
        assertEquals(SessionStorage.Record.Validation.STRICT, new SharedPreferencesSessionStorage().read(context).validation);
        assertEquals(expected, preferenceStore.durableValues());
    }

    @Test public void unrelatedEditCannotEraseMalformedProvenanceBeforeInit() throws Exception {
        correlated();
        SuperTokens.resetForTests();
        assertTrue(preferenceStore.preferences.edit().putString(SharedPreferencesSessionStorage.VALIDATION, "invalid").commit());
        Map<String, Object> before = preferenceStore.durableValues();
        Utils.storeInStorage("custom", "value", context);
        assertEquals("invalid", preferenceStore.durableValues().get(SharedPreferencesSessionStorage.VALIDATION));
        assertEquals(before.get(SharedPreferencesSessionStorage.FINGERPRINT),
                preferenceStore.durableValues().get(SharedPreferencesSessionStorage.FINGERPRINT));
        initializeSession();
        assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SuperTokens.getSessionSnapshot(context).getStatus());
        assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
    }

    @Test public void preinitKnownEditPreservesMetadataUntilFingerprintClassification() throws Exception {
        correlated();
        Map<String, Object> before = preferenceStore.durableValues();
        SuperTokens.resetForTests();
        Utils.storeInStorage(Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY, "1", context);
        Map<String, Object> expected = new HashMap<>(before);
        expected.put("st-storage-item-" + Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY, "1");
        assertEquals(expected, preferenceStore.durableValues());
        initializeSession();
        assertEquals(SessionStorage.Record.Validation.LEGACY, new SharedPreferencesSessionStorage().read(context).validation);
        assertEquals("1", Utils.getFromStorage(Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY, context));
    }

    @Test public void markerProjectionPreservesLiteralTextAndAntiCsrfAcrossRestart() throws Exception {
        for (String marker : new String[]{"1", "0001", "+1", "not-a-number", "-01"}) {
            SessionSnapshot original = install("marker", "before");
            Utils.storeInStorage(Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY, marker, context);
            assertMarker(marker, original.getTokens().getAntiCSRF());
            restart(true);
            assertMarker(marker, original.getTokens().getAntiCSRF());
        }
    }

    private void assertMarker(String marker, String anti) {
        assertEquals(marker, preferenceStore.durableValues().get("st-storage-item-" + Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY));
        assertEquals(marker, Utils.getFromStorage(Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY, context));
        assertEquals(marker, SessionState.lastAccessTokenUpdate(SuperTokens.getSessionSnapshot(context).getOwner()));
        assertEquals(anti, AntiCSRF.getToken(context, marker));
        assertNull(AntiCSRF.getToken(context, marker + "0"));
    }

    @Test public void literalLowMarkerDoesNotLowerGenerationHighWater() throws Exception {
        install("marker", "before");
        long future = System.currentTimeMillis() + 86400000L;
        Utils.storeInStorage(Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY, Long.toString(future), context);
        Utils.storeInStorage(Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY, "0001", context);
        assertEquals("0001", Utils.getFromStorage(Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY, context));
        install("marker", "after");
        assertEquals(Long.toString(future + 1), Utils.getFromStorage(Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY, context));
    }

    @Test public void bothHeaderHelpersDeliverPayloadBeforeApplyingNewAntiCsrf() throws Exception {
        for (boolean urlConnection : new boolean[]{false, true}) {
            List<EventHandler.EventType> events = new ArrayList<>();
            List<String> csrfAtCallback = new ArrayList<>();
            new SuperTokens.Builder(context, server.url("/").toString()).eventHandler(event -> {
                events.add(event);
                String marker = Utils.getFromStorage(Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY, context);
                csrfAtCallback.add(AntiCSRF.getToken(context, marker));
            }).reconfigure();
            SessionSnapshot before = install("same", "before");
            events.clear();
            csrfAtCallback.clear();
            SessionTokens next = tokens("same", "after");
            if (urlConnection) {
                SuperTokensCustomHttpURLConnection response = mock(SuperTokensCustomHttpURLConnection.class);
                when(response.getHeaderField("st-access-token")).thenReturn(next.getAccessToken());
                when(response.getHeaderField("st-refresh-token")).thenReturn(next.getRefreshToken());
                when(response.getHeaderField("front-token")).thenReturn(next.getFrontToken());
                when(response.getHeaderField("anti-csrf")).thenReturn(next.getAntiCSRF());
                Utils.saveTokenFromHeaders(response, context);
            } else {
                okhttp3.Response response = new okhttp3.Response.Builder()
                        .request(new okhttp3.Request.Builder().url(server.url("/")).build())
                        .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                        .header("st-access-token", next.getAccessToken()).header("st-refresh-token", next.getRefreshToken())
                        .header("front-token", next.getFrontToken()).header("anti-csrf", next.getAntiCSRF()).build();
                Utils.saveTokenFromHeaders(response, context);
            }
            assertEquals(java.util.Collections.singletonList(EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED), events);
            assertEquals(java.util.Collections.singletonList(before.getTokens().getAntiCSRF()), csrfAtCallback);
            assertEquals(next.getAntiCSRF(), AntiCSRF.getToken(context,
                    Utils.getFromStorage(Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY, context)));
        }
    }

    @Test public void failedRawMemoryWriteThenAcknowledgedRecoveryIsTokenlessOnRestart() throws Exception {
        install("old", "one");
        SessionStorage delegate = new SharedPreferencesSessionStorage();
        SessionState.setStorageForTests(new SessionStorage() {
            @Override public Record read(Context ctx) throws IOException { return delegate.read(ctx); }
            @Override public void write(Context ctx, Record record) throws IOException { delegate.write(ctx, record); }
            @Override public void editLegacy(Context ctx, LegacyEdit edit) throws IOException {
                failCommits(TestPreferences.CommitMode.RETURN_FALSE_AFTER_MEMORY);
                try { delegate.editLegacy(ctx, edit); }
                finally { failCommits(TestPreferences.CommitMode.ACKNOWLEDGED); }
            }
        });
        Utils.setToken(Utils.TokenType.ACCESS, "unacknowledged", context);
        assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SuperTokens.getSessionSnapshot(context).getStatus());
        assertNull(Utils.getFromStorage(Constants.ACCESS_TOKEN_PREFS_KEY, context));
        assertFalse(preferenceStore.durableValues().containsKey("st-storage-item-" + Constants.ACCESS_TOKEN_PREFS_KEY));
        assertEquals("recovery", preferenceStore.durableValues().get(SharedPreferencesSessionStorage.STATUS));
        restart(true);
        assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SuperTokens.getSessionSnapshot(context).getStatus());
        assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
    }

    @Test public void failedPreinitRawWriteCannotBeReadmittedBySameProcessInit() throws Exception {
        for (boolean initiallyEmpty : new boolean[]{false, true}) {
            correlated();
            SuperTokens.resetForTests();
            if (initiallyEmpty) preferenceStore.seed(java.util.Collections.emptyMap());
            failCommits(TestPreferences.CommitMode.RETURN_FALSE_AFTER_MEMORY);
            Utils.storeInStorage(Constants.ACCESS_TOKEN_PREFS_KEY, tokens("unacknowledged", "new").getAccessToken(), context);
            failCommits(TestPreferences.CommitMode.ACKNOWLEDGED);
            // An unrelated successful write must not clear session-write uncertainty.
            Utils.storeInStorage("custom", "value", context);
            initializeSession();
            assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SuperTokens.getSessionSnapshot(context).getStatus());
            assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
            assertEquals("recovery", preferenceStore.durableValues().get(SharedPreferencesSessionStorage.STATUS));
        }
    }

    @Test public void sevenAEmptyAndCoherentInitHaveNoCommitButMigrationRemainsDurable() throws Exception {
        SuperTokens.resetForTests();
        preferenceStore.seed(java.util.Collections.emptyMap());
        int before = preferenceStore.commits;
        initializeSession();
        assertEquals(before, preferenceStore.commits);
        correlated();
        SuperTokens.resetForTests();
        before = preferenceStore.commits;
        initializeSession();
        assertEquals(before, preferenceStore.commits);
        SuperTokens.resetForTests();
        seedLegacy(tokens("legacy", "one"));
        before = preferenceStore.commits;
        initializeSession();
        assertEquals(before + 1, preferenceStore.commits);
    }
}
