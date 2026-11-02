package com.supertokens.session;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionStressOracleTest extends SessionTestFixture {
    @Test public void taggedPendingReplacementWireFailuresAreRecognized() throws Exception {
        SessionOwner owner = install("initial", "before").getOwner();
        SessionTokens replacement = tokens("pending-replacement", "after");
        BlockingStorage storage = new BlockingStorage(new SharedPreferencesSessionStorage());
        SessionState.setStorageForTests(storage);
        ExecutorService writer = Executors.newSingleThreadExecutor();
        List<Integer> unrecognized = new ArrayList<>();
        List<SessionRequestTag> captured = new ArrayList<>();
        try {
            Future<SessionCommitResult> pending = writer.submit(() -> SuperTokens.installSession(context, owner, replacement));
            assertTrue(storage.entered.await(5, TimeUnit.SECONDS));
            for (int kind = 0; kind <= 2; kind++) {
                SessionRequestTag tag = SuperTokens.captureRequestTag(context);
                assertEquals(SessionSnapshot.Status.UNAVAILABLE, tag.getSnapshot().getStatus());
                assertTrue(tag.capturedDuringPendingReplacement());
                captured.add(tag);
                assertNull(tag.getSnapshot().getTokens());
                server.enqueue(new MockResponse().setResponseCode(401));
                try {
                    SessionStressTest.wire(client, server, kind == 1, tag,
                            kind == 2 ? "/reject" : "/protected", "pending-" + kind);
                    fail("Expected tagged pending request to fail closed");
                } catch (IOException expected) {
                    assertFalse(expected instanceof StaleSessionException);
                    assertEquals("Session storage recovery is required", expected.getMessage());
                    if (!SessionStressTest.expectedStressIOException(kind, tag, expected)) unrecognized.add(kind);
                }
                RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
                assertNotNull(request);
                assertNull(request.getHeader("Authorization"));
                assertNull(request.getHeader("anti-csrf"));
            }
            storage.release.countDown();
            assertTrue(pending.get(5, TimeUnit.SECONDS).isSuccess());
            assertEquals(SessionSnapshot.Status.AVAILABLE, SuperTokens.getSessionSnapshot(context).getStatus());
            IOException pendingError = new IOException("Session storage recovery is required");
            for (SessionRequestTag tag : captured) {
                assertTrue(tag.capturedDuringPendingReplacement());
                assertTrue(SessionStressTest.expectedStressIOException(0, tag, pendingError));
                assertTrue(SessionStressTest.expectedStressIOException(7, tag, pendingError));
                assertFalse(SessionStressTest.expectedStressIOException(3, tag, pendingError));
                assertFalse(SessionStressTest.expectedStressIOException(0, tag, new IOException("another failure")));
                assertFalse(SessionStressTest.expectedStressIOException(0, tag,
                        new IllegalAccessException("Session storage recovery is required")));
                assertFalse(new SessionRequestTag(tag.getSnapshot(), tag.configuration).capturedDuringPendingReplacement());
            }
            assertTrue("Legitimate pending failures misclassified for wire kinds " + unrecognized, unrecognized.isEmpty());
        } finally {
            storage.release.countDown();
            writer.shutdownNow();
            SessionState.setStorageForTests(new SharedPreferencesSessionStorage());
        }
    }

    @Test public void recoveryMessageIsNotBlanketAllowedByStressOracle() throws Exception {
        install("initial", "before");
        SessionRequestTag available = SuperTokens.captureRequestTag(context);
        assertEquals(SessionSnapshot.Status.AVAILABLE, available.getSnapshot().getStatus());
        assertFalse(available.capturedDuringPendingReplacement());
        FrontToken.setToken(context, null);
        SessionRequestTag terminalUnavailable = SuperTokens.captureRequestTag(context);
        assertEquals(SessionSnapshot.Status.UNAVAILABLE, terminalUnavailable.getSnapshot().getStatus());
        assertFalse(terminalUnavailable.capturedDuringPendingReplacement());
        failCommits(TestPreferences.CommitMode.RETURN_FALSE);
        try {
            SessionCommitResult result = SuperTokens.installSession(context, terminalUnavailable.getOwner(),
                    tokens("failed-persistence", "after"));
            assertEquals(SessionCommitResult.Status.PERSISTENCE_FAILED, result.getStatus());
        } finally {
            failCommits(TestPreferences.CommitMode.ACKNOWLEDGED);
        }
        SessionRequestTag recovery = SuperTokens.captureRequestTag(context);
        assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, recovery.getSnapshot().getStatus());
        assertFalse(recovery.capturedDuringPendingReplacement());
        IOException error = new IOException("Session storage recovery is required");
        for (int kind : new int[] {0, 1, 2, 7}) {
            assertFalse(SessionStressTest.expectedStressIOException(kind, null, error));
            assertFalse(SessionStressTest.expectedStressIOException(kind, available, error));
            assertFalse(SessionStressTest.expectedStressIOException(kind, terminalUnavailable, error));
            assertFalse(SessionStressTest.expectedStressIOException(kind, recovery, error));
        }
    }
}
