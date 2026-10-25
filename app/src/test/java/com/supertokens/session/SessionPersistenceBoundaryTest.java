package com.supertokens.session;

import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SessionPersistenceBoundaryTest extends SessionTestFixture {
    @Test public void persistedFutureClockMarkerCannotRepeatOrRollBack() throws Exception {
        install("clock", "before");
        SharedPreferences disk = context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE);
        long future = System.currentTimeMillis() + 86400000L;
        assertTrue(disk.edit().putString("st-storage-item-" + Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY,
                Long.toString(future)).commit());
        SuperTokens.resetForTests();
        new SuperTokens.Builder(context, server.url("/").toString()).build();
        long priorRevision = SuperTokens.getSessionSnapshot(context).getRevision();
        for (int i = 1; i <= 20; i++) {
            SessionSnapshot next = install("clock", "same-credentials");
            assertEquals(Long.toString(future + i), SessionState.lastAccessTokenUpdate(next.getOwner()));
            assertTrue(next.getRevision() > priorRevision);
            priorRevision = next.getRevision();
        }
    }

    @Test public void configurationCommitExceptionRetiresOwnerAndReportsUncertainty() throws Exception {
        SessionSnapshot original = install("before", "config");
        AtomicInteger commits = new AtomicInteger();
        failCommits(commits);
        SessionCommitResult result = new SuperTokens.Builder(context, server.url("/changed").toString()).reconfigure();
        assertEquals(SessionCommitResult.Status.PERSISTENCE_FAILED, result.getStatus());
        assertNotNull(result.getPersistenceError());
        assertFalse(original.isCurrent());
        assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SuperTokens.getSessionSnapshot(context).getStatus());
        assertNull(SuperTokens.getAccessToken(context));
        assertEquals("Failed config commit and failed recovery tombstone", 2, commits.get());
    }

    @Test public void legacyMigrationCommitExceptionCannotExportCredentials() throws Exception {
        SuperTokens.resetForTests();
        SharedPreferences disk = preferences();
        SessionTokens legacy = tokens("legacy", "migration");
        assertTrue(disk.edit().putString("st-storage-item-" + Constants.ACCESS_TOKEN_PREFS_KEY, legacy.getAccessToken())
                .putString("st-storage-item-" + Constants.REFRESH_TOKEN_PREFS_KEY, legacy.getRefreshToken())
                .putString(Constants.FRONT_TOKEN_PREFS_KEY, legacy.getFrontToken())
                .putString("st-storage-item-" + Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY, "123").commit());
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(disk);
        AtomicInteger commits = new AtomicInteger();
        failCommits(commits);
        SessionCommitResult result = new SuperTokens.Builder(context, server.url("/").toString()).reconfigure();
        assertEquals(SessionCommitResult.Status.PERSISTENCE_FAILED, result.getStatus());
        assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SuperTokens.getSessionSnapshot(context).getStatus());
        assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
        assertTrue("Original disk tuple remains uncertain", disk.contains("st-storage-item-" + Constants.ACCESS_TOKEN_PREFS_KEY));
        assertEquals(2, commits.get());
    }

    @Test public void tokenlessResolutionAndSnapshotsNeverReadOrWriteDiskAfterInitialization() {
        reset(context);
        for (int i = 0; i < 100; i++) {
            SessionSnapshot snapshot = SuperTokens.getSessionSnapshot(context);
            assertEquals(SessionSnapshot.Status.ABSENT, snapshot.getStatus());
            assertNull(SuperTokens.getAccessToken(context));
            assertEquals(SessionResolution.Status.NO_SESSION,
                    SuperTokens.resolveAccessToken(context, snapshot.getOwner(), true, null).getStatus());
        }
        verifyZeroInteractions(context);
        assertEquals(0, server.getRequestCount());
    }

    @Test public void headerFreeResponsesDoNotReadOrWritePreferences() throws Exception {
        install("no-disk", "before");
        reset(context);
        for (boolean url : new boolean[]{false, true}) {
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("unchanged"));
            assertEquals(200, request(url, null, "/profile"));
        }
        verifyZeroInteractions(context);
    }

    @Test public void snapshotPayloadCopiesRemainIsolatedAcrossReads() throws Exception {
        SessionSnapshot original = install("clone", "before");
        JSONObject one = original.getAccessTokenPayload();
        one.put("sub", "changed").put("nested", new JSONObject().put("key", "changed"));
        assertEquals("primary-user", original.getAccessTokenPayload().getString("sub"));
        assertFalse(SuperTokens.getSessionSnapshot(context).getAccessTokenPayload().has("nested"));
        assertSame(original, SuperTokens.getSessionSnapshot(context));
    }

    private void failCommits(AtomicInteger commits) {
        SharedPreferences delegate = context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE);
        SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("edit")) {
                        return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                new Class<?>[]{SharedPreferences.Editor.class}, (editor, action, values) -> {
                                    if (action.getName().equals("commit")) {
                                        commits.incrementAndGet();
                                        throw new IllegalStateException("injected preferences commit failure");
                                    }
                                    if (action.getName().equals("apply")) throw new AssertionError("unacknowledged write");
                                    return editor;
                                });
                    }
                    try { return method.invoke(delegate, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(failing);
    }
}
