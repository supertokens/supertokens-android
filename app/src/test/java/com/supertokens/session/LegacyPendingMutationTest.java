package com.supertokens.session;

import android.content.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyPendingMutationTest extends SessionTestFixture {
    private void assertLegacyRefreshAndStrictRecovery() throws Exception {
        assertFalse(SuperTokens.attemptRefreshingSession(context));
        SessionSnapshot snapshot = SuperTokens.getSessionSnapshot(context);
        SessionResolution strict = SuperTokens.resolveAccessToken(context, snapshot.getOwner(), true, null);
        assertEquals(SessionResolution.Status.RECOVERY_REQUIRED, strict.getStatus());
        assertNotNull(strict.getError());
    }

    private void blockedLegacyCalls(boolean signOut) throws Exception {
        for (int mutation = 0; mutation < 4; mutation++) {
            List<EventHandler.EventType> events = new ArrayList<>();
            new SuperTokens.Builder(context, server.url("/").toString()).eventHandler(events::add).reconfigure();
            SessionSnapshot old = install("a", "before");
            SessionTokens replacement = tokens("b", "after");
            events.clear();
            BlockingStorage storage = new BlockingStorage(new SharedPreferencesSessionStorage());
            SessionState.setStorageForTests(storage);
            ExecutorService workers = Executors.newCachedThreadPool();
            final int kind = mutation;
            try {
                Future<?> writer = workers.submit(() -> {
                    if (kind == 0) assertTrue(SuperTokens.installSession(context, old.getOwner(), replacement).isSuccess());
                    else if (kind == 1) assertTrue(SuperTokens.clearSession(context, old.getOwner()).isSuccess());
                    else if (kind == 2) assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                            .eventHandler(event -> {}).reconfigure().isSuccess());
                    else FrontToken.setToken(context, null);
                    return null;
                });
                assertTrue(storage.entered.await(5, TimeUnit.SECONDS));
                SessionSnapshot transientState = SuperTokens.getSessionSnapshot(context);
                assertEquals(SessionSnapshot.Status.UNAVAILABLE, transientState.getStatus());
                assertTrue(transientState.isCurrent());
                assertFalse(old.isCurrent());
                assertNull(transientState.getTokens());
                Utils.LocalSessionState projection = Utils.getLocalSessionState(context);
                assertEquals(Utils.LocalSessionStateStatus.NOT_EXISTS, projection.status);
                assertNull(projection.lastAccessTokenUpdate);
                SessionResolution strict = SuperTokens.resolveAccessToken(context, transientState.getOwner(), true, null);
                assertEquals(SessionResolution.Status.RECOVERY_REQUIRED, strict.getStatus());
                assertNotNull(strict.getError());
                assertNull(strict.getAccessToken());
                SessionRequestTag tagged = SuperTokens.captureRequestTag(context);
                try {
                    SessionTransport.apply(context, tagged, tagged,
                            new SessionResponse(200, "OK", java.util.Collections.emptyMap()), true);
                    fail("Tagged response must remain fail-closed during replacement");
                } catch (IOException expected) {
                    assertEquals("Session storage recovery is required", expected.getMessage());
                }
                assertFalse(SuperTokens.doesSessionExist(context));
                assertNull(SuperTokens.getAccessToken(context));
                try {
                    SuperTokens.getAccessTokenPayloadSecurely(context);
                    fail("Pending replacement cannot export a payload");
                } catch (IllegalStateException expected) {
                    assertEquals("No session exists", expected.getMessage());
                }
                Future<?> caller = workers.submit(() -> {
                    if (signOut) SuperTokens.signOut(context);
                    else assertFalse(SuperTokens.attemptRefreshingSession(context));
                    return null;
                });
                caller.get(3, TimeUnit.SECONDS);
                assertFalse(writer.isDone());
                assertTrue(events.isEmpty());
                assertEquals(0, server.getRequestCount());
                storage.release.countDown();
                writer.get(5, TimeUnit.SECONDS);
                if (kind == 0) {
                    assertEquals(replacement.getAccessToken(), SuperTokens.getSessionSnapshot(context).getTokens().getAccessToken());
                } else if (kind == 3) {
                    assertEquals(SessionSnapshot.Status.UNAVAILABLE, SuperTokens.getSessionSnapshot(context).getStatus());
                    assertLegacyRefreshAndStrictRecovery();
                } else assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
                assertEquals(0, server.getRequestCount());
            } finally {
                storage.release.countDown();
                workers.shutdownNow();
            }
            SessionState.setStorageForTests(new SharedPreferencesSessionStorage());
        }
    }

    @Test public void attemptRefreshDuringHealthyReplacementReturnsFalseWithoutBorrowing() throws Exception {
        blockedLegacyCalls(false);
    }

    @Test public void signOutDuringHealthyReplacementIsNoOpWithoutBorrowing() throws Exception {
        blockedLegacyCalls(true);
    }

    @Test public void failedReplacementRemainsRecoveryDuringAndAfterTombstoneCommit() throws Exception {
        SessionSnapshot old = install("a", "before");
        SessionTokens replacement = tokens("b", "after");
        CountDownLatch recoveryEntered = new CountDownLatch(1);
        CountDownLatch recoveryRelease = new CountDownLatch(1);
        SessionState.setStorageForTests(new SessionStorage() {
            final SessionStorage delegate = new SharedPreferencesSessionStorage();
            int writes;
            @Override public Record read(Context ctx) throws IOException { return delegate.read(ctx); }
            @Override public void editLegacy(Context ctx, LegacyEdit edit) throws IOException { delegate.editLegacy(ctx, edit); }
            @Override public void write(Context ctx, Record record) throws IOException {
                if (++writes == 1) throw new IOException("Injected primary failure");
                recoveryEntered.countDown();
                try { assertTrue(recoveryRelease.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                delegate.write(ctx, record);
            }
        });
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<SessionCommitResult> writer = workers.submit(() -> SuperTokens.installSession(context, old.getOwner(), replacement));
            assertTrue(recoveryEntered.await(5, TimeUnit.SECONDS));
            assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SuperTokens.getSessionSnapshot(context).getStatus());
            Utils.LocalSessionState projection = Utils.getLocalSessionState(context);
            assertEquals(Utils.LocalSessionStateStatus.NOT_EXISTS, projection.status);
            assertNull(projection.lastAccessTokenUpdate);
            assertLegacyRefreshAndStrictRecovery();
            recoveryRelease.countDown();
            SessionCommitResult result = writer.get(5, TimeUnit.SECONDS);
            assertEquals(SessionCommitResult.Status.PERSISTENCE_FAILED, result.getStatus());
            assertEquals("Injected primary failure", result.getPersistenceError().getMessage());
            assertLegacyRefreshAndStrictRecovery();
            SuperTokens.signOut(context);
            assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
            assertEquals(0, server.getRequestCount());
        } finally { recoveryRelease.countDown(); workers.shutdownNow(); }
    }

    @Test public void terminalPartialRawStateIsNotMadeBenignByUnrelatedPendingWrite() throws Exception {
        install("a", "before");
        FrontToken.setToken(context, null);
        assertEquals(SessionSnapshot.Status.UNAVAILABLE, SuperTokens.getSessionSnapshot(context).getStatus());
        BlockingStorage storage = new BlockingStorage(new SharedPreferencesSessionStorage());
        SessionState.setStorageForTests(storage);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = workers.submit(() -> Utils.storeInStorage("custom", "value", context));
            assertTrue(storage.entered.await(5, TimeUnit.SECONDS));
            assertLegacyRefreshAndStrictRecovery();
            storage.release.countDown();
            writer.get(5, TimeUnit.SECONDS);
            assertLegacyRefreshAndStrictRecovery();
            SuperTokens.signOut(context);
            assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        } finally { storage.release.countDown(); workers.shutdownNow(); }
    }

    @Test public void producingLegacyCallStillThrowsItsOwnPersistenceFailure() throws Exception {
        for (boolean signOut : new boolean[]{false, true}) {
            SessionSnapshot before = install("own-failure-" + signOut, "before");
            server.setDispatcher(new FakeBackend(before.getTokens()));
            IOException injected = new IOException("Own operation persistence failure");
            SessionState.setStorageForTests(new SessionStorage() {
                final SessionStorage delegate = new SharedPreferencesSessionStorage();
                @Override public Record read(Context ctx) throws IOException { return delegate.read(ctx); }
                @Override public void editLegacy(Context ctx, LegacyEdit edit) throws IOException { delegate.editLegacy(ctx, edit); }
                @Override public void write(Context ctx, Record record) throws IOException { throw injected; }
            });
            try {
                if (signOut) SuperTokens.signOut(context);
                else SuperTokens.attemptRefreshingSession(context);
                fail("Own operation persistence failure was suppressed");
            } catch (IOException expected) {
                assertSame(injected, expected);
            }
            assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SuperTokens.getSessionSnapshot(context).getStatus());
            assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
            SessionState.setStorageForTests(new SharedPreferencesSessionStorage());
        }
    }
}
