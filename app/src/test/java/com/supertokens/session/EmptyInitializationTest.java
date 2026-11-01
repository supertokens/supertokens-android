package com.supertokens.session;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class EmptyInitializationTest extends SessionTestFixture {
    @Override protected boolean initializeOnSetup() { return false; }

    @Test public void emptyInitializationAndRestartNeverWrite() throws Exception {
        for (int i = 0; i < 2; i++) {
            initializeSession();
            assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
            assertFalse(SuperTokens.doesSessionExist(context));
            assertNull(SuperTokens.getAccessToken(context));
            assertEquals(0, preferenceStore.commits);
            assertEquals(0, preferenceStore.applies);
            assertTrue(preferenceStore.durableValues().isEmpty());
            SuperTokens.resetForTests();
        }
        assertEquals(0, server.getRequestCount());
    }

    @Test public void emptyInitializationDoesNotEnterRecoveryWhenWritesWouldFail() throws Exception {
        failCommits(TestPreferences.CommitMode.THROW);
        initializeSession();
        assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        assertEquals(0, preferenceStore.commits);
        assertEquals(0, preferenceStore.applies);
    }

    @Test public void firstMutationAfterEmptyInitializationIsDurable() throws Exception {
        initializeSession();
        SessionSnapshot installed = install("empty-init", "first");
        assertEquals(1, preferenceStore.commits);
        restart(true);
        SessionSnapshot restored = SuperTokens.getSessionSnapshot(context);
        assertEquals(installed.getAdoptionId(), restored.getAdoptionId());
        assertEquals(installed.getRevision(), restored.getRevision());
        assertEquals(installed.getTokens().getAccessToken(), restored.getTokens().getAccessToken());
        assertEquals(1, preferenceStore.commits);
    }

    @Test public void coherentStartupAcrossConfigurationChangeDoesNotWrite() throws Exception {
        initializeSession();
        SessionSnapshot before = install("coherent", "before");
        int writes = preferenceStore.commits;
        SuperTokens.resetForTests();
        new SuperTokens.Builder(context, "https://new.example.com/ignored").tokenTransferMethod("cookie").build();
        assertEquals(before.getAdoptionId(), SuperTokens.getSessionSnapshot(context).getAdoptionId());
        assertEquals(writes, preferenceStore.commits);
        assertEquals(0, preferenceStore.applies);
    }

    @Test public void missingMarkerIsSynthesizedOnlyDuringOneTimeImport() throws Exception {
        SessionTokens tokens = tokens("legacy", "before");
        Map<String, Object> raw = new HashMap<>();
        raw.put("supertokens-android-fronttoken-key", tokens.getFrontToken());
        raw.put("st-storage-item-st-access-token", tokens.getAccessToken());
        raw.put("st-storage-item-st-refresh-token", tokens.getRefreshToken());
        seedLegacy(raw);
        assertFalse(SuperTokens.doesSessionExist(context));
        assertNull(SuperTokens.getAccessToken(context));
        assertNull(FrontToken.getToken(context));
        assertTrue(FrontToken.doesTokenExist(context));
        SessionOwner owner = new SessionOwner();
        assertEquals(SessionCommitResult.Status.NOT_CONFIGURED,
                SuperTokens.installSession(context, owner, tokens).getStatus());
        assertEquals(SessionCommitResult.Status.NOT_CONFIGURED,
                SuperTokens.clearSession(context, owner).getStatus());
        assertEquals(SessionResolution.Status.NOT_CONFIGURED,
                SuperTokens.resolveAccessToken(context, owner, false, null).getStatus());
        assertEquals(raw, preferenceStore.durableValues());
        assertEquals(0, preferenceStore.commits);
        initializeSession();
        assertEquals(SessionSnapshot.Status.AVAILABLE, SuperTokens.getSessionSnapshot(context).getStatus());
        assertEquals(1, preferenceStore.commits);
        assertEquals(0, preferenceStore.applies);
        restart(true);
        assertEquals(1, preferenceStore.commits);
        assertEquals(tokens.getAccessToken(), SuperTokens.getAccessToken(context));
    }
}
