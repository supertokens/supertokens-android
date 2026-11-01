package com.supertokens.session;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyImportStorageTest extends SessionTestFixture {
    private static final String ACCESS = "st-storage-item-st-access-token";
    private static final String REFRESH = "st-storage-item-st-refresh-token";
    private static final String MARKER = "st-storage-item-st-last-access-token-update";
    @Override protected boolean initializeOnSetup() { return false; }

    @Test public void compatibleTuplesSurviveTwoDurableRestartsAndLaterLogin() throws Exception {
        SessionTokens a = tokens("a", "user-a", 4102444800000L, 4102444800000L, 5);
        SessionTokens b = tokens("b", "user-b", 4102444800000L, 4102444800000L, 5);
        SessionTokens[] cases = {
                a, new SessionTokens(null, null, a.getFrontToken(), "csrf"),
                new SessionTokens(a.getAccessToken(), null, a.getFrontToken(), "csrf"),
                new SessionTokens(null, a.getRefreshToken(), a.getFrontToken(), "csrf"),
                new SessionTokens("opaque", a.getRefreshToken(), a.getFrontToken(), "csrf"),
                new SessionTokens("bad.jwt", a.getRefreshToken(), a.getFrontToken(), "csrf"),
                new SessionTokens(b.getAccessToken(), b.getRefreshToken(), a.getFrontToken(), "csrf"),
                tokens("expired", "user-a", 1000, 1000, 5)
        };
        for (int row = 0; row < cases.length; row++) {
            SuperTokens.resetForTests();
            seedLegacy(cases[row]);
            initializeSession();
            for (int restart = 0; restart < 3; restart++) {
                SessionSnapshot snapshot = SuperTokens.getSessionSnapshot(context);
                assertEquals("row " + row, SessionSnapshot.Status.AVAILABLE, snapshot.getStatus());
                assertEquals("user-a", SuperTokens.getUserId(context));
                assertTuple(cases[row], snapshot.getInternalTokens());
                if (cases[row].getAccessToken() == null || cases[row].getRefreshToken() == null) {
                    assertNull("row " + row + " must not export an incomplete tuple", snapshot.getTokens());
                }
                if (restart < 2) restart(true);
            }
            server.enqueue(rotation(b));
            assertEquals(200, request(false, null, "/login"));
            assertEquals("user-b", SuperTokens.getUserId(context));
        }
    }

    @Test public void missingMarkerIsSynthesizedAndPersistedOnce() throws Exception {
        seedLegacy(tokens("a", "before"));
        assertTrue(preferenceStore.preferences.edit().remove(MARKER).commit());
        initializeSession();
        assertEquals(SessionSnapshot.Status.AVAILABLE, SuperTokens.getSessionSnapshot(context).getStatus());
        String marker = preferenceStore.preferences.getString(MARKER, null);
        assertNotNull(marker);
        int commits = preferenceStore.commits;
        restart(true);
        restart(true);
        assertEquals(marker, preferenceStore.preferences.getString(MARKER, null));
        assertEquals(commits, preferenceStore.commits);
    }

    @Test public void unreadableLegacyFrontIsAbsentAndLaterLoginWorks() throws Exception {
        for (Object front : new Object[]{"A", "bm90LWpzb24=", 42, ""}) {
            SuperTokens.resetForTests();
            Map<String, Object> raw = new HashMap<>();
            raw.put(Constants.FRONT_TOKEN_PREFS_KEY, front);
            raw.put(ACCESS, "partial-access");
            raw.put(MARKER, "1");
            seedLegacy(raw);
            initializeSession();
            assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
            restart(true);
            assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
            server.enqueue(rotation(tokens("login", "after")));
            assertEquals(200, request(false, null, "/login"));
            assertTrue(SuperTokens.doesSessionExist(context));
        }
    }

    @Test public void publicSequentialSettersPreserveEarlierPartsAndReplaceIdentity() throws Exception {
        initializeSession();
        for (String user : new String[]{"user-a", "user-b"}) {
            SessionTokens tuple = tokens(user, user, 4102444800000L, 4102444800000L, 5);
            SessionOwner previous = SuperTokens.getSessionSnapshot(context).getOwner();
            Utils.storeInStorage("st-access-token", tuple.getAccessToken(), context);
            assertFalse(previous.isCurrent());
            if (user.equals("user-a")) assertEquals(SessionSnapshot.Status.UNAVAILABLE, SuperTokens.getSessionSnapshot(context).getStatus());
            assertTrue(tuple.getAccessToken().equals(preferenceStore.preferences.getString(ACCESS, null)));
            Utils.storeInStorage("st-refresh-token", tuple.getRefreshToken(), context);
            FrontToken.setItem(context, tuple.getFrontToken());
            AntiCSRF.setToken(context, Utils.getLocalSessionState(context).lastAccessTokenUpdate, tuple.getAntiCSRF());
            assertEquals(user, SuperTokens.getUserId(context));
            assertTuple(tuple, SuperTokens.getSessionSnapshot(context).getInternalTokens());
            restart(true);
            restart(true);
            assertEquals(user, SuperTokens.getUserId(context));
        }
    }

    @Test public void strictInstallRemainsStrictAfterCompatibleImport() throws Exception {
        SessionTokens valid = tokens("a", "before");
        SessionTokens opaque = new SessionTokens("opaque", "refresh", valid.getFrontToken(), null);
        seedLegacy(opaque);
        initializeSession();
        SessionSnapshot imported = SuperTokens.getSessionSnapshot(context);
        assertEquals(SessionSnapshot.Status.AVAILABLE, imported.getStatus());
        assertEquals(SessionCommitResult.Status.INVALID_SESSION,
                SuperTokens.installSession(context, imported.getOwner(), opaque).getStatus());
        assertSame(imported, SuperTokens.getSessionSnapshot(context));
        assertEquals(SessionCommitResult.Status.SUCCESS,
                SuperTokens.installSession(context, imported.getOwner(), valid).getStatus());
        assertEquals("strict", preferenceStore.preferences.getString(SharedPreferencesSessionStorage.VALIDATION, null));
        restart(true);
        assertTuple(valid, SuperTokens.getSessionSnapshot(context).getInternalTokens());
    }

    @Test public void frontFirstRawReplacementAndUnreadableIntermediateDoNotLoseEarlierWrites() throws Exception {
        initializeSession();
        for (String user : new String[]{"user-a", "user-b"}) {
            SessionTokens tuple = tokens(user, user, 4102444800000L, 4102444800000L, 5);
            FrontToken.setItem(context, "remove");
            assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
            FrontToken.setToken(context, tuple.getFrontToken());
            Utils.storeInStorage("st-access-token", tuple.getAccessToken(), context);
            FrontToken.setToken(context, "A");
            assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
            assertTrue(tuple.getAccessToken().equals(preferenceStore.preferences.getString(ACCESS, null)));
            Utils.storeInStorage("st-refresh-token", tuple.getRefreshToken(), context);
            FrontToken.setToken(context, tuple.getFrontToken());
            AntiCSRF.setToken(context, Utils.getLocalSessionState(context).lastAccessTokenUpdate, tuple.getAntiCSRF());
            assertTuple(tuple, SuperTokens.getSessionSnapshot(context).getInternalTokens());
            restart(true);
            restart(true);
            assertEquals(user, SuperTokens.getUserId(context));
        }
    }

    @Test public void coherentOldSchemaIsUpgradedOnceWithoutChangingAdoptionOrRevisions() throws Exception {
        initializeSession();
        SessionSnapshot before = install("a", "before");
        removeEvidence();
        restart(true);
        SessionSnapshot after = SuperTokens.getSessionSnapshot(context);
        assertEquals(before.getAdoptionId(), after.getAdoptionId());
        assertEquals(before.getRevision(), after.getRevision());
        assertEquals("strict", preferenceStore.preferences.getString(SharedPreferencesSessionStorage.VALIDATION, null));
        int commits = preferenceStore.commits;
        restart(true);
        assertEquals(commits, preferenceStore.commits);
    }

    @Test public void unchangedEvidenceDoesNotExcuseDamagedNewMetadata() throws Exception {
        initializeSession();
        for (String key : new String[]{SharedPreferencesSessionStorage.USER, SharedPreferencesSessionStorage.STATUS,
                SharedPreferencesSessionStorage.LEGACY_MARKER, SharedPreferencesSessionStorage.VALIDATION}) {
            install("a", "before");
            preferenceStore.preferences.edit().putString(key, "damaged").commit();
            restart(true);
            assertEquals(key, SessionSnapshot.Status.RECOVERY_REQUIRED, SuperTokens.getSessionSnapshot(context).getStatus());
            assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
        }
        install("a", "before");
        preferenceStore.preferences.edit().remove(SharedPreferencesSessionStorage.FINGERPRINT).commit();
        restart(true);
        assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SuperTokens.getSessionSnapshot(context).getStatus());
    }

    @Test public void metadataOnlyExpiryAndIdentityCorruptionWithUnchangedEvidenceRequiresRecovery() throws Exception {
        initializeSession();
        for (String key : new String[]{SharedPreferencesSessionStorage.EXPIRY, SharedPreferencesSessionStorage.USER,
                SharedPreferencesSessionStorage.HANDLE, SharedPreferencesSessionStorage.TENANT}) {
            SessionSnapshot before = install("a", "before");
            String fingerprint = preferenceStore.preferences.getString(SharedPreferencesSessionStorage.FINGERPRINT, null);
            String value = key.equals(SharedPreferencesSessionStorage.EXPIRY)
                    ? Long.toString(before.getAccessTokenExpiry() + 1000) : "contradictory-identity";
            assertTrue(preferenceStore.preferences.edit().putString(key, value).commit());
            assertEquals(fingerprint, preferenceStore.preferences.getString(SharedPreferencesSessionStorage.FINGERPRINT, null));
            SessionStorage.Record damaged = new SharedPreferencesSessionStorage().read(context);
            assertTuple(before.getInternalTokens(), damaged.tokens);
            assertFalse("This is not an evidence-less legacy record", damaged.evidenceMissing);
            for (int restart = 0; restart < 3; restart++) {
                restart(true);
                SessionSnapshot snapshot = SuperTokens.getSessionSnapshot(context);
                assertEquals(key, SessionSnapshot.Status.RECOVERY_REQUIRED, snapshot.getStatus());
                assertNull(snapshot.getTokens());
                assertNull(snapshot.getInternalTokens());
                assertNull(SuperTokens.getAccessToken(context));
            }
        }
    }

    @Test public void heldRefreshCannotOverwriteSessionReconstructedByPublicRawSetters() throws Exception {
        initializeSession();
        SessionSnapshot old = install("a", "before");
        SessionTokens lateRotation = tokens("a", "late-refresh");
        SessionTokens replacement = tokens("b", "user-b", 4102444800000L, 4102444800000L, 5);
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                arrived.countDown();
                return release.await(8, TimeUnit.SECONDS) ? rotation(lateRotation) : new MockResponse().setResponseCode(504);
            }
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<SessionResolution> held = worker.submit(() -> SuperTokens.resolveAccessToken(context, old.getOwner(), true, null));
            assertTrue(arrived.await(5, TimeUnit.SECONDS));
            RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(sent);
            assertEquals("/auth/session/refresh", sent.getPath());
            assertEquals("Bearer " + old.getTokens().getRefreshToken(), sent.getHeader("Authorization"));
            Utils.storeInStorage("st-access-token", replacement.getAccessToken(), context);
            assertFalse(old.isCurrent());
            Utils.storeInStorage("st-refresh-token", replacement.getRefreshToken(), context);
            FrontToken.setItem(context, replacement.getFrontToken());
            AntiCSRF.setToken(context, Utils.getLocalSessionState(context).lastAccessTokenUpdate, replacement.getAntiCSRF());
            SessionSnapshot reconstructed = SuperTokens.getSessionSnapshot(context);
            assertEquals("user-b", reconstructed.getUserId());
            assertNotSame(old.getOwner(), reconstructed.getOwner());
            assertTuple(replacement, reconstructed.getInternalTokens());
            Map<String, Object> durable = preferenceStore.durableValues();
            int commits = preferenceStore.commits;
            release.countDown();
            SessionResolution stale = held.get(5, TimeUnit.SECONDS);
            assertEquals(SessionResolution.Status.STALE_OWNER, stale.getStatus());
            assertNull(stale.getAccessToken());
            assertSame(reconstructed, SuperTokens.getSessionSnapshot(context));
            assertEquals(commits, preferenceStore.commits);
            assertTrue("Late refresh must not change durable data", durable.equals(preferenceStore.durableValues()));
            assertEquals(1, server.getRequestCount());
            restart(true);
            restart(true);
            assertEquals("user-b", SuperTokens.getUserId(context));
            assertTuple(replacement, SuperTokens.getSessionSnapshot(context).getInternalTokens());
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void evidenceCoversEveryLegacyFieldAndIgnoresUnrelatedPreferences() throws Exception {
        initializeSession();
        SessionTokens next = tokens("b", "user-b", 4102444800000L, 4102444800000L, 5);
        Map<String, String> changes = new HashMap<>();
        changes.put(ACCESS, "opaque-b");
        changes.put(REFRESH, "refresh-b");
        changes.put(Constants.FRONT_TOKEN_PREFS_KEY, next.getFrontToken());
        changes.put(Constants.CSRF_TOKEN_PREFS_KEY, "csrf-b");
        changes.put(MARKER, "same-clock-different-writer");
        for (Map.Entry<String, String> change : changes.entrySet()) {
            SessionSnapshot before = install("a", "before");
            String marker = preferenceStore.preferences.getString(MARKER, null);
            preferenceStore.preferences.edit().putString(change.getKey(), change.getValue()).commit();
            restart(true);
            SessionSnapshot after = SuperTokens.getSessionSnapshot(context);
            assertEquals(change.getKey(), SessionSnapshot.Status.AVAILABLE, after.getStatus());
            assertNotEquals(before.getAdoptionId(), after.getAdoptionId());
            assertTrue(change.getValue().equals(preferenceStore.preferences.getString(change.getKey(), null)));
            if (!change.getKey().equals(MARKER)) assertEquals(marker, preferenceStore.preferences.getString(MARKER, null));
            restart(true);
            restart(true);
            assertEquals(after.getAdoptionId(), SuperTokens.getSessionSnapshot(context).getAdoptionId());
        }
        SessionSnapshot before = install("a", "before");
        preferenceStore.preferences.edit().putInt("unrelated", 7).commit();
        int commits = preferenceStore.commits;
        restart(true);
        assertEquals(before.getAdoptionId(), SuperTokens.getSessionSnapshot(context).getAdoptionId());
        assertEquals(commits, preferenceStore.commits);
    }

    @Test public void oldSchemaContradictionsRecoverBeforeIdentityAndMetadataValidation() throws Exception {
        initializeSession();
        for (String contradiction : new String[]{"identity", "absent", "recovery", "missing-adoption", "invalid-revision"}) {
            install("a", "before");
            removeEvidence();
            SharedPreferences.Editor editor = preferenceStore.preferences.edit();
            if (contradiction.equals("identity")) editor.putString(SharedPreferencesSessionStorage.USER, "stale-user");
            if (contradiction.equals("absent") || contradiction.equals("recovery")) editor.putString(SharedPreferencesSessionStorage.STATUS, contradiction);
            if (contradiction.equals("missing-adoption")) editor.remove(SharedPreferencesSessionStorage.ADOPTION);
            if (contradiction.equals("invalid-revision")) editor.putString(SharedPreferencesSessionStorage.REVISION, "broken");
            editor.commit();
            restart(true);
            assertEquals(contradiction, SessionSnapshot.Status.AVAILABLE, SuperTokens.getSessionSnapshot(context).getStatus());
            assertEquals("primary-user", SuperTokens.getUserId(context));
            assertEquals("legacy", preferenceStore.preferences.getString(SharedPreferencesSessionStorage.VALIDATION, null));
            restart(true);
            restart(true);
            assertEquals("primary-user", SuperTokens.getUserId(context));
        }
    }

    @Test public void failedImportDoesNotExportAndLaterLoginStillWorks() throws Exception {
        for (TestPreferences.CommitMode mode : new TestPreferences.CommitMode[]{TestPreferences.CommitMode.RETURN_FALSE,
                TestPreferences.CommitMode.THROW, TestPreferences.CommitMode.RETURN_FALSE_AFTER_MEMORY,
                TestPreferences.CommitMode.THROW_AFTER_MEMORY}) {
            SuperTokens.resetForTests();
            SessionTokens valid = tokens("a", "before");
            seedLegacy(new SessionTokens("opaque", "refresh", valid.getFrontToken(), null));
            failCommits(mode);
            SessionCommitResult result = new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header").reconfigure();
            assertEquals(SessionCommitResult.Status.PERSISTENCE_FAILED, result.getStatus());
            assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
            assertNull(SuperTokens.getAccessToken(context));
            failCommits(TestPreferences.CommitMode.ACKNOWLEDGED);
            server.enqueue(rotation(valid));
            assertEquals(200, request(false, null, "/login"));
            restart(true);
            restart(true);
            assertTrue(SuperTokens.doesSessionExist(context));
        }
    }

    private void removeEvidence() {
        assertTrue(preferenceStore.preferences.edit().remove(SharedPreferencesSessionStorage.FINGERPRINT)
                .remove(SharedPreferencesSessionStorage.VALIDATION).remove(SharedPreferencesSessionStorage.LEGACY_MARKER).commit());
    }

    @Test public void unchangedMarkerDoesNotHideRollbackAndOldMetadataCanBeRecovered() throws Exception {
        initializeSession();
        SessionTokens next = tokens("b", "user-b", 4102444800000L, 4102444800000L, 5);
        for (boolean oldSchema : new boolean[]{false, true}) {
            install("a", "before");
            SharedPreferences.Editor editor = preferenceStore.preferences.edit();
            if (oldSchema) {
                for (String key : preferenceStore.preferences.getAll().keySet()) {
                    if (key.endsWith("-validation") || key.endsWith("-fingerprint") || key.endsWith("-legacy-marker")) editor.remove(key);
                }
                editor.putString(SharedPreferencesSessionStorage.REVISION, "not-a-number");
            }
            editor.putString(ACCESS, next.getAccessToken()).putString(REFRESH, next.getRefreshToken())
                    .putString(Constants.FRONT_TOKEN_PREFS_KEY, next.getFrontToken()).commit();
            restart(true);
            assertEquals(SessionSnapshot.Status.AVAILABLE, SuperTokens.getSessionSnapshot(context).getStatus());
            assertEquals("user-b", SuperTokens.getUserId(context));
            restart(true);
            restart(true);
            assertEquals("user-b", SuperTokens.getUserId(context));
        }
    }

    private static void assertTuple(SessionTokens expected, SessionTokens actual) {
        assertNotNull(actual);
        assertTrue("access preserved", java.util.Objects.equals(expected.getAccessToken(), actual.getAccessToken()));
        assertTrue("refresh preserved", java.util.Objects.equals(expected.getRefreshToken(), actual.getRefreshToken()));
        assertTrue("front preserved", java.util.Objects.equals(expected.getFrontToken(), actual.getFrontToken()));
        assertTrue("csrf preserved", java.util.Objects.equals(expected.getAntiCSRF(), actual.getAntiCSRF()));
    }
}
