package com.supertokens.session;

import android.content.Context;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class R15MutationProtocolTest extends SessionTestFixture {
    @Test public void admittedPreStartTimeoutsReclaimActiveCapacity() throws Exception {
        SessionSnapshot owner = install("same", "one");
        ExecutorService workers = Executors.newSingleThreadExecutor();
        AtomicInteger transports = new AtomicInteger();
        SessionResponse refreshed = response(tokens("same", "refreshed"), true);
        RefreshCoordinator.Transport transport = (ctx, config, snapshot) -> {
            transports.incrementAndGet();
            return refreshed;
        };
        try {
            // More admissions than the eight-slot limit prove removal, not merely terminal publication.
            for (int i = 0; i < 9; i++) {
                RefreshCoordinator.Acquisition admitted = SessionState.acquireRefresh(owner.getOwner(), true,
                        null, transport, owner.getRefreshTokenRevision());
                assertTrue(admitted.leader);
                assertNotNull(admitted.flight);
                GateStorage storage = new GateStorage();
                storage.blockRaw = true;
                SessionState.setStorageForTests(storage);
                Future<?> writer = workers.submit(() -> Utils.storeInStorage("custom", "value", context));
                try {
                    await(storage.entered);
                    java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
                    RefreshCoordinator.WaitBudget budget = new RefreshCoordinator.WaitBudget(clock::get, 1);
                    clock.incrementAndGet();
                    assertFalse(SessionState.startRefresh(admitted.flight, budget));
                    assertEquals(SessionResolution.Status.REFRESH_FAILED, admitted.flight.await().getStatus());
                    assertFalse(writer.isDone());
                    assertEquals(0, transports.get());
                } finally { storage.release.countDown(); }
                writer.get(5, TimeUnit.SECONDS);
            }
            SessionState.setStorageForTests(new SharedPreferencesSessionStorage());
            assertTrue(RefreshCoordinator.resolve(context, owner.getOwner(), true, null, transport).isSuccess());
            assertEquals(1, transports.get());
        } finally { workers.shutdownNow(); }
    }

    private static void await(CountDownLatch latch) throws Exception {
        assertTrue("Watchdog: expected phase was not reached", latch.await(5, TimeUnit.SECONDS));
    }

    private static SessionResponse response(SessionTokens tokens, boolean refresh) {
        Map<String, java.util.List<String>> headers = new HashMap<>();
        headers.put("st-access-token", Collections.singletonList(tokens.getAccessToken()));
        headers.put("front-token", Collections.singletonList(tokens.getFrontToken()));
        if (refresh) headers.put("st-refresh-token", Collections.singletonList(tokens.getRefreshToken()));
        return new SessionResponse(200, "OK", headers);
    }

    private static final class GateStorage implements SessionStorage {
        final SessionStorage delegate = new SharedPreferencesSessionStorage();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch recoveryEntered = new CountDownLatch(1);
        final CountDownLatch recoveryRelease = new CountDownLatch(1);
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger rawEdits = new AtomicInteger();
        final CopyOnWriteArrayList<Record> journal = new CopyOnWriteArrayList<>();
        boolean failFirst;
        boolean blockRecovery;
        boolean writeMemoryBeforeGate;
        boolean blockRaw;

        private void gate(CountDownLatch entered, CountDownLatch release) throws IOException {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Writer watchdog expired");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

        @Override public Record read(Context context) throws IOException { return delegate.read(context); }
        @Override public void editLegacy(Context context, LegacyEdit edit) throws IOException {
            rawEdits.incrementAndGet();
            if (blockRaw) gate(entered, release);
            delegate.editLegacy(context, edit);
        }
        @Override public void write(Context context, Record record) throws IOException {
            journal.add(record);
            int call = writes.incrementAndGet();
            if (call == 1) {
                if (writeMemoryBeforeGate) {
                    Utils.getSharedPreferences(context).edit()
                            .putString("st-storage-item-" + Constants.ACCESS_TOKEN_PREFS_KEY,
                                    record.tokens.getAccessToken()).apply();
                }
                gate(entered, release);
                if (failFirst) throw new IOException("Injected primary failure");
            }
            if (call == 2 && blockRecovery) gate(recoveryEntered, recoveryRelease);
            delegate.write(context, record);
        }
    }

    @Test public void replacementReadersFinishBeforeCommitAndTransientObserverGetsFinalState() throws Exception {
        SessionSnapshot old = install("old", "one");
        SessionTokens next = tokens("new", "two");
        GateStorage storage = new GateStorage();
        SessionState.setStorageForTests(storage);
        ExecutorService workers = Executors.newCachedThreadPool();
        try {
            Future<SessionCommitResult> writer = workers.submit(() -> SuperTokens.installSession(context, old.getOwner(), next));
            await(storage.entered);
            SessionSnapshot transientState = workers.submit(() -> {
                SessionSnapshot snapshot = SuperTokens.getSessionSnapshot(context);
                assertEquals(SessionSnapshot.Status.UNAVAILABLE, snapshot.getStatus());
                assertNull(snapshot.getTokens());
                assertNull(SuperTokens.getAccessToken(context));
                assertNull(Utils.getFromStorage(Constants.ACCESS_TOKEN_PREFS_KEY, context));
                assertFalse(old.isCurrent());
                return snapshot;
            }).get(5, TimeUnit.SECONDS);
            assertEquals(1, storage.release.getCount());
            AtomicInteger observed = new AtomicInteger();
            SessionSubscription subscription = SuperTokens.observeSessions(context, snapshot -> {
                assertEquals(SessionSnapshot.Status.AVAILABLE, snapshot.getStatus());
                observed.incrementAndGet();
            });
            subscription.activate();
            storage.release.countDown();
            assertTrue(writer.get(5, TimeUnit.SECONDS).isSuccess());
            assertFalse(transientState.isCurrent());
            assertEquals(1, observed.get());
            subscription.close();
            restart(true);
            assertEquals("new", SuperTokens.getSessionSnapshot(context).getSessionHandle());
        } finally { storage.release.countDown(); workers.shutdownNow(); }
    }

    @Test public void promotionRetainsCommittedTokensAndMarkerEvenIfPreferencesMemoryChangesEarly() throws Exception {
        SessionSnapshot old = install("same", "one");
        String marker = SessionState.lastAccessTokenUpdate(old.getOwner());
        SessionRequestTag tag = SuperTokens.captureRequestTag(context);
        SessionResponse update = response(tokens("same", "two"), false);
        GateStorage storage = new GateStorage();
        storage.writeMemoryBeforeGate = true;
        SessionState.setStorageForTests(storage);
        ExecutorService workers = Executors.newCachedThreadPool();
        try {
            Future<SessionCommitResult> writer = workers.submit(() -> SessionState.applyOrdinaryResponse(context, tag, update));
            await(storage.entered);
            workers.submit(() -> {
                assertSame(old, SuperTokens.getSessionSnapshot(context));
                assertEquals(old.getTokens().getAccessToken(), Utils.getFromStorage(Constants.ACCESS_TOKEN_PREFS_KEY, context));
                assertEquals(marker, Utils.getFromStorage(Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY, context));
                assertNull(tag.getAcceptedSnapshot());
            }).get(5, TimeUnit.SECONDS);
            assertEquals(1, storage.release.getCount());
            storage.release.countDown();
            assertTrue(writer.get(5, TimeUnit.SECONDS).isSuccess());
            assertNotNull(tag.getAcceptedSnapshot());
            assertTrue(Long.parseLong(SessionState.lastAccessTokenUpdate(old.getOwner())) > Long.parseLong(marker));
            SessionTokens committed = SuperTokens.getSessionSnapshot(context).getTokens();
            restart(true);
            assertEquals(committed.getAccessToken(), SuperTokens.getSessionSnapshot(context).getTokens().getAccessToken());
        } finally { storage.release.countDown(); workers.shutdownNow(); }
    }

    @Test public void queuedOldResponseIsRejectedBeforeAnyDiskWrite() throws Exception {
        SessionSnapshot old = install("old", "one");
        SessionRequestTag tag = SuperTokens.captureRequestTag(context);
        SessionTokens next = tokens("new", "two");
        SessionResponse stale = response(tokens("old", "stale"), true);
        GateStorage storage = new GateStorage();
        SessionState.setStorageForTests(storage);
        ExecutorService workers = Executors.newCachedThreadPool();
        try {
            Future<SessionCommitResult> first = workers.submit(() -> SuperTokens.installSession(context, old.getOwner(), next));
            await(storage.entered);
            CountDownLatch attempted = new CountDownLatch(1);
            Future<SessionCommitResult> second = workers.submit(() -> {
                attempted.countDown();
                return SessionState.applyOrdinaryResponse(context, tag, stale);
            });
            await(attempted);
            assertEquals(1, storage.writes.get());
            storage.release.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS).isSuccess());
            assertEquals(SessionCommitResult.Status.STALE_OWNER, second.get(5, TimeUnit.SECONDS).getStatus());
            assertEquals(1, storage.writes.get());
            restart(true);
            assertEquals("new", SuperTokens.getSessionSnapshot(context).getSessionHandle());
        } finally { storage.release.countDown(); workers.shutdownNow(); }
    }

    @Test public void failedPrimaryAndRecoveryStaySerializedAheadOfLaterLogin() throws Exception {
        SessionSnapshot old = install("old", "one");
        GateStorage storage = new GateStorage();
        storage.failFirst = true;
        storage.blockRecovery = true;
        SessionTokens rejected = tokens("failed", "two");
        SessionTokens accepted = tokens("later", "three");
        SessionState.setStorageForTests(storage);
        ExecutorService workers = Executors.newCachedThreadPool();
        try {
            Future<SessionCommitResult> first = workers.submit(() -> SuperTokens.installSession(context, old.getOwner(), rejected));
            await(storage.entered);
            storage.release.countDown();
            await(storage.recoveryEntered);
            SessionSnapshot recovery = workers.submit(() -> SuperTokens.getSessionSnapshot(context)).get(5, TimeUnit.SECONDS);
            assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, recovery.getStatus());
            assertNull(recovery.getTokens());
            Future<SessionCommitResult> later = workers.submit(() -> SuperTokens.installSession(context, recovery.getOwner(), accepted));
            assertEquals(2, storage.writes.get());
            storage.recoveryRelease.countDown();
            assertEquals(SessionCommitResult.Status.PERSISTENCE_FAILED, first.get(5, TimeUnit.SECONDS).getStatus());
            assertTrue(later.get(5, TimeUnit.SECONDS).isSuccess());
            assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, storage.journal.get(1).status);
            assertEquals("later", storage.journal.get(2).sessionHandle);
            restart(true);
            assertEquals("later", SuperTokens.getSessionSnapshot(context).getSessionHandle());
        } finally { storage.release.countDown(); storage.recoveryRelease.countDown(); workers.shutdownNow(); }
    }

    @Test public void refreshFollowersJoinWhileDurableCompletionIsBlocked() throws Exception {
        SessionSnapshot old = install("same", "one");
        GateStorage storage = new GateStorage();
        SessionState.setStorageForTests(storage);
        AtomicInteger transports = new AtomicInteger();
        SessionResponse update = response(tokens("same", "two"), true);
        RefreshCoordinator.Transport transport = (ctx, config, snapshot) -> { transports.incrementAndGet(); return update; };
        ExecutorService workers = Executors.newCachedThreadPool();
        try {
            Future<SessionResolution> leader = workers.submit(() -> RefreshCoordinator.resolve(context, old.getOwner(), true, null, transport));
            await(storage.entered);
            RefreshCoordinator.Acquisition joined = SessionState.acquireRefresh(old.getOwner(), true, null, transport, old.getRefreshTokenRevision());
            assertFalse(joined.leader);
            assertNotNull(joined.flight);
            assertFalse(joined.flight.isDone());
            Future<SessionResolution> follower = workers.submit(() -> joined.flight.await());
            assertEquals(1, transports.get());
            assertSame(old, SuperTokens.getSessionSnapshot(context));
            storage.release.countDown();
            assertTrue(leader.get(5, TimeUnit.SECONDS).isSuccess());
            assertTrue(follower.get(5, TimeUnit.SECONDS).isSuccess());
            assertEquals(1, transports.get());
        } finally { storage.release.countDown(); workers.shutdownNow(); }
    }

    @Test public void ordinaryRotationReservesRefreshAdmissionUntilPublication() throws Exception {
        SessionSnapshot old = install("same", "one");
        SessionRequestTag tag = SuperTokens.captureRequestTag(context);
        SessionResponse update = response(tokens("same", "two"), true);
        GateStorage storage = new GateStorage();
        SessionState.setStorageForTests(storage);
        ExecutorService workers = Executors.newCachedThreadPool();
        try {
            Future<SessionCommitResult> writer = workers.submit(() -> SessionState.applyOrdinaryResponse(context, tag, update));
            await(storage.entered);
            AtomicInteger transports = new AtomicInteger();
            RefreshCoordinator.Transport transport = (ctx, config, snapshot) -> { transports.incrementAndGet(); return update; };
            RefreshCoordinator.Acquisition waiting = SessionState.acquireRefresh(old.getOwner(), true, null, transport, old.getRefreshTokenRevision());
            assertNotNull(waiting.pending);
            assertNull(waiting.flight);
            storage.release.countDown();
            assertTrue(writer.get(5, TimeUnit.SECONDS).isSuccess());
            waiting.pending.await();
            SessionResolution resolved = RefreshCoordinator.resolve(context, old.getOwner(), true, null, transport, old.getRefreshTokenRevision());
            assertTrue(resolved.isSuccess());
            assertEquals(0, transports.get());
        } finally { storage.release.countDown(); workers.shutdownNow(); }
    }

    @Test public void allRawEntryPointsWaitBeforePreferencesEditsAndUseAcknowledgedWrites() throws Exception {
        SessionTokens token = tokens("raw", "one");
        okhttp3.Response response = new okhttp3.Response.Builder()
                .request(new okhttp3.Request.Builder().url(server.url("/")).build())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .header("st-access-token", token.getAccessToken()).header("st-refresh-token", token.getRefreshToken())
                .header("front-token", token.getFrontToken()).header("anti-csrf", token.getAntiCSRF()).build();
        SuperTokensCustomHttpURLConnection connection = org.mockito.Mockito.mock(SuperTokensCustomHttpURLConnection.class);
        org.mockito.Mockito.when(connection.getHeaderField("st-access-token")).thenReturn(token.getAccessToken());
        org.mockito.Mockito.when(connection.getHeaderField("st-refresh-token")).thenReturn(token.getRefreshToken());
        org.mockito.Mockito.when(connection.getHeaderField("front-token")).thenReturn(token.getFrontToken());
        org.mockito.Mockito.when(connection.getHeaderField("anti-csrf")).thenReturn(token.getAntiCSRF());
        java.util.List<Runnable> edits = java.util.Arrays.asList(
                () -> Utils.storeInStorage(Constants.ACCESS_TOKEN_PREFS_KEY, token.getAccessToken(), context),
                () -> Utils.setToken(Utils.TokenType.REFRESH, token.getRefreshToken(), context),
                () -> Utils.saveLastAccessTokenUpdate(context),
                () -> FrontToken.setToken(context, token.getFrontToken()),
                () -> FrontToken.setItem(context, token.getFrontToken()),
                () -> FrontToken.removeToken(context),
                () -> AntiCSRF.setToken(context, "marker", "csrf"),
                () -> AntiCSRF.removeToken(context),
                () -> Utils.saveTokenFromHeaders(response, context),
                () -> Utils.saveTokenFromHeaders(connection, context));
        ExecutorService workers = Executors.newCachedThreadPool();
        try {
            for (Runnable edit : edits) {
                SessionState.setStorageForTests(null);
                SessionSnapshot old = install("raw", "one");
                GateStorage storage = new GateStorage();
                SessionState.setStorageForTests(storage);
                int applies = preferenceStore.applies;
                Future<SessionCommitResult> writer = workers.submit(() -> SuperTokens.installSession(context, old.getOwner(), token));
                await(storage.entered);
                CountDownLatch attempted = new CountDownLatch(1);
                Future<?> raw = workers.submit(() -> { attempted.countDown(); edit.run(); });
                try {
                    await(attempted);
                    assertEquals(0, storage.rawEdits.get());
                    assertEquals(applies, preferenceStore.applies);
                    storage.release.countDown();
                    assertTrue(writer.get(5, TimeUnit.SECONDS).isSuccess());
                    raw.get(5, TimeUnit.SECONDS);
                    assertTrue(storage.rawEdits.get() > 0);
                    assertEquals(applies, preferenceStore.applies);
                    SessionSnapshot committed = SuperTokens.getSessionSnapshot(context);
                    restart(true);
                    assertEquals(committed.getStatus(), SuperTokens.getSessionSnapshot(context).getStatus());
                } finally { storage.release.countDown(); }
            }
        } finally { workers.shutdownNow(); }
    }

    @Test public void clearAndReconfigureDoNotBlockReadersOrConfigurationMonitor() throws Exception {
        ExecutorService workers = Executors.newCachedThreadPool();
        try {
            for (boolean reconfigure : new boolean[]{false, true}) {
                SessionState.setStorageForTests(null);
                SessionSnapshot old = install("old", "one");
                GateStorage storage = new GateStorage();
                SessionState.setStorageForTests(storage);
                Future<SessionCommitResult> writer = workers.submit(() -> reconfigure
                        ? new SuperTokens.Builder(context, server.url("/").toString()).apiBasePath("/changed").reconfigure()
                        : SuperTokens.clearSession(context, old.getOwner()));
                try {
                    await(storage.entered);
                    workers.submit(() -> {
                        synchronized (SuperTokens.class) {
                            assertEquals(SessionSnapshot.Status.UNAVAILABLE, SuperTokens.getSessionSnapshot(context).getStatus());
                            assertNull(Utils.getTokenForHeaderAuth(Utils.TokenType.ACCESS, context));
                        }
                    }).get(5, TimeUnit.SECONDS);
                    assertEquals(1, storage.release.getCount());
                    storage.release.countDown();
                    assertTrue(writer.get(5, TimeUnit.SECONDS).isSuccess());
                    restart(true);
                    assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
                } finally { storage.release.countDown(); }
            }
        } finally { workers.shutdownNow(); }
    }

    @Test public void rawPersistenceIsTokenlessAndPartialTupleDoesNotRetainTransientAuthority() throws Exception {
        SessionSnapshot old = install("raw", "one");
        GateStorage storage = new GateStorage();
        storage.blockRaw = true;
        SessionState.setStorageForTests(storage);
        ExecutorService workers = Executors.newCachedThreadPool();
        try {
            Future<?> raw = workers.submit(() -> FrontToken.setToken(context, null));
            await(storage.entered);
            SessionSnapshot transientState = workers.submit(() -> {
                assertFalse(old.isCurrent());
                assertNull(Utils.getFromStorage(Constants.ACCESS_TOKEN_PREFS_KEY, context));
                assertNull(FrontToken.getToken(context));
                return SuperTokens.getSessionSnapshot(context);
            }).get(5, TimeUnit.SECONDS);
            storage.release.countDown();
            raw.get(5, TimeUnit.SECONDS);
            assertFalse(transientState.isCurrent());
            assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
            assertEquals("Partial raw assembly is retained durably", old.getTokens().getAccessToken(),
                    preferenceStore.durableValues().get("st-storage-item-" + Constants.ACCESS_TOKEN_PREFS_KEY));
        } finally { storage.release.countDown(); workers.shutdownNow(); }
    }

    @Test public void failedRawWriteAndFailedRecoveryNeverExportPreferencesMemory() throws Exception {
        SessionSnapshot old = install("raw", "one");
        failCommits(TestPreferences.CommitMode.RETURN_FALSE_AFTER_MEMORY);
        Utils.setToken(Utils.TokenType.ACCESS, "unacknowledged", context);
        assertFalse(old.isCurrent());
        assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SuperTokens.getSessionSnapshot(context).getStatus());
        assertNull(Utils.getFromStorage(Constants.ACCESS_TOKEN_PREFS_KEY, context));
        assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
        assertEquals("Both writes failed: durable prior tuple remains uncertain", old.getTokens().getAccessToken(),
                preferenceStore.durableValues().get("st-storage-item-" + Constants.ACCESS_TOKEN_PREFS_KEY));
        failCommits(TestPreferences.CommitMode.ACKNOWLEDGED);
        restart(true);
        assertEquals(old.getTokens().getAccessToken(), SuperTokens.getSessionSnapshot(context).getTokens().getAccessToken());
    }

    @Test public void callbackMayStartAndJoinAnotherThreadMutationAfterBothLocksRelease() throws Exception {
        ExecutorService workers = Executors.newCachedThreadPool();
        AtomicInteger callbacks = new AtomicInteger();
        try {
            new SuperTokens.Builder(context, server.url("/").toString()).eventHandler(event -> {
                if (event != EventHandler.EventType.SESSION_CREATED) return;
                try {
                    Future<SessionCommitResult> cleared = workers.submit(() -> {
                        synchronized (SuperTokens.class) {
                            return SuperTokens.clearSession(context, SuperTokens.getSessionSnapshot(context).getOwner());
                        }
                    });
                    assertTrue(cleared.get(5, TimeUnit.SECONDS).isSuccess());
                    callbacks.incrementAndGet();
                } catch (Exception e) { throw new AssertionError(e); }
            }).reconfigure();
            install("callback", "one");
            assertEquals(1, callbacks.get());
            assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        } finally { workers.shutdownNow(); }
    }

    @Test public void interruptedPendingWaiterDoesNotReleaseWriterOrStartRefresh() throws Exception {
        SessionSnapshot old = install("same", "one");
        SessionRequestTag tag = SuperTokens.captureRequestTag(context);
        SessionResponse update = response(tokens("same", "two"), true);
        GateStorage storage = new GateStorage();
        SessionState.setStorageForTests(storage);
        ExecutorService workers = Executors.newCachedThreadPool();
        try {
            Future<SessionCommitResult> writer = workers.submit(() -> SessionState.applyOrdinaryResponse(context, tag, update));
            await(storage.entered);
            AtomicInteger transports = new AtomicInteger();
            Future<Boolean> interrupted = workers.submit(() -> {
                Thread.currentThread().interrupt();
                SessionResolution result = RefreshCoordinator.resolve(context, old.getOwner(), true, null,
                        (ctx, config, snapshot) -> { transports.incrementAndGet(); return update; });
                assertEquals(SessionResolution.Status.REFRESH_FAILED, result.getStatus());
                return Thread.interrupted();
            });
            assertTrue(interrupted.get(5, TimeUnit.SECONDS));
            assertEquals(1, storage.release.getCount());
            assertEquals(0, transports.get());
            storage.release.countDown();
            assertTrue(writer.get(5, TimeUnit.SECONDS).isSuccess());
        } finally { storage.release.countDown(); workers.shutdownNow(); }
    }

    @Test public void storageCannotReenterMutationAndOverwriteItsOwnReservation() throws Exception {
        SessionSnapshot old = install("old", "one");
        AtomicInteger rejected = new AtomicInteger();
        SessionStorage delegate = new SharedPreferencesSessionStorage();
        SessionState.setStorageForTests(new SessionStorage() {
            @Override public Record read(Context ctx) throws IOException { return delegate.read(ctx); }
            @Override public void editLegacy(Context ctx, LegacyEdit edit) throws IOException { delegate.editLegacy(ctx, edit); }
            @Override public void write(Context ctx, Record record) throws IOException {
                try {
                    SuperTokens.clearSession(ctx, SuperTokens.getSessionSnapshot(ctx).getOwner());
                    fail("Nested storage mutation must be rejected before any write");
                } catch (IllegalStateException expected) {
                    assertTrue(expected.getMessage().contains("reentered"));
                    rejected.incrementAndGet();
                }
                delegate.write(ctx, record);
            }
        });
        assertTrue(SuperTokens.installSession(context, old.getOwner(), tokens("new", "two")).isSuccess());
        assertEquals(1, rejected.get());
        restart(true);
        assertEquals("new", SuperTokens.getSessionSnapshot(context).getSessionHandle());
    }

    @Test public void startupImportBlocksOnlyWriterAndDoesNotExposeUnacknowledgedRawKeys() throws Exception {
        SuperTokens.resetForTests();
        seedLegacy(tokens("startup", "one"));
        GateStorage storage = new GateStorage();
        SessionState.setStorageForTests(storage);
        ExecutorService workers = Executors.newCachedThreadPool();
        try {
            Future<?> init = workers.submit(() -> {
                new SuperTokens.Builder(context, server.url("/").toString()).build();
                return null;
            });
            await(storage.entered);
            workers.submit(() -> {
                assertNull(Utils.getFromStorage(Constants.ACCESS_TOKEN_PREFS_KEY, context));
                assertFalse(SuperTokens.isInitCalled);
                synchronized (SuperTokens.class) { assertNull(FrontToken.getToken(context)); }
                return null;
            }).get(5, TimeUnit.SECONDS);
            assertEquals(1, storage.release.getCount());
            storage.release.countDown();
            init.get(5, TimeUnit.SECONDS);
            assertEquals("startup", SuperTokens.getSessionSnapshot(context).getSessionHandle());
        } finally { storage.release.countDown(); workers.shutdownNow(); }
    }

    @Test public void failedPreinitRawWriteCannotExportUnacknowledgedPreferencesMemory() throws Exception {
        SuperTokens.resetForTests();
        SessionTokens prior = tokens("prior", "one");
        seedLegacy(prior);
        assertEquals(prior.getAccessToken(), Utils.getFromStorage(Constants.ACCESS_TOKEN_PREFS_KEY, context));
        failCommits(TestPreferences.CommitMode.RETURN_FALSE_AFTER_MEMORY);
        Utils.setToken(Utils.TokenType.ACCESS, "unacknowledged", context);
        assertFalse(SuperTokens.isInitCalled);
        assertNull(Utils.getFromStorage(Constants.ACCESS_TOKEN_PREFS_KEY, context));
        failCommits(TestPreferences.CommitMode.ACKNOWLEDGED);
        restart(true);
        assertEquals(prior.getAccessToken(), SuperTokens.getSessionSnapshot(context).getTokens().getAccessToken());
    }

    @Test public void terminalPartialRawSnapshotReachesOldAndTransientBornObserversWithoutClearingDisk() throws Exception {
        SessionSnapshot old = install("raw", "one");
        java.util.List<SessionSnapshot> oldEvents = new CopyOnWriteArrayList<>();
        java.util.List<SessionSnapshot> bornEvents = new CopyOnWriteArrayList<>();
        SessionSubscription oldSubscription = SuperTokens.observeSessions(context, oldEvents::add);
        oldSubscription.activate();
        GateStorage storage = new GateStorage();
        storage.blockRaw = true;
        SessionState.setStorageForTests(storage);
        ExecutorService workers = Executors.newCachedThreadPool();
        SessionSubscription born = null;
        try {
            Future<?> raw = workers.submit(() -> FrontToken.setToken(context, null));
            await(storage.entered);
            born = SuperTokens.observeSessions(context, bornEvents::add);
            SessionSnapshot transientView = born.getInitialSnapshot();
            born.activate();
            assertTrue(oldEvents.isEmpty());
            assertTrue(bornEvents.isEmpty());
            storage.release.countDown();
            raw.get(5, TimeUnit.SECONDS);
            SessionSnapshot terminal = SuperTokens.getSessionSnapshot(context);
            assertEquals(SessionSnapshot.Status.UNAVAILABLE, terminal.getStatus());
            assertTrue(terminal.isCurrent());
            assertFalse(transientView.isCurrent());
            assertEquals(java.util.Collections.singletonList(terminal), oldEvents);
            assertEquals(java.util.Collections.singletonList(terminal), bornEvents);
            assertTrue(terminal.getRevision() > old.getRevision());
            assertEquals(SessionResolution.Status.RECOVERY_REQUIRED,
                    SuperTokens.resolveAccessToken(context, terminal.getOwner(), false, null).getStatus());
            assertEquals(0, storage.writes.get());
            assertEquals(1, storage.rawEdits.get());
            assertEquals(old.getTokens().getAccessToken(), preferenceStore.durableValues().get("st-storage-item-" + Constants.ACCESS_TOKEN_PREFS_KEY));
            assertEquals(old.getTokens().getRefreshToken(), preferenceStore.durableValues().get("st-storage-item-" + Constants.REFRESH_TOKEN_PREFS_KEY));
        } finally {
            storage.release.countDown();
            oldSubscription.close();
            if (born != null) born.close();
            workers.shutdownNow();
        }
    }

    @Test public void twoPendingReservationsAndThenFlightJoinShareOneMonotonicBudget() throws Exception {
        SessionSnapshot old = install("same", "one");
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE - 10);
        RefreshCoordinator.WaitBudget budget = new RefreshCoordinator.WaitBudget(clock::get, TimeUnit.SECONDS.toNanos(30));
        ExecutorService workers = Executors.newCachedThreadPool();
        AtomicInteger transports = new AtomicInteger();
        CountDownLatch networkEntered = new CountDownLatch(1);
        CountDownLatch networkRelease = new CountDownLatch(1);
        SessionResponse finalResponse = response(tokens("same", "final"), true);
        RefreshCoordinator.Transport transport = (ctx, config, snapshot) -> {
            transports.incrementAndGet();
            networkEntered.countDown();
            try {
                if (!networkRelease.await(5, TimeUnit.SECONDS)) throw new IOException("Network watchdog");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            return finalResponse;
        };
        try {
            for (int i = 0; i < 2; i++) {
                GateStorage storage = new GateStorage();
                SessionState.setStorageForTests(storage);
                SessionRequestTag tag = SuperTokens.captureRequestTag(context);
                SessionResponse promotion = response(tokens("same", "pending-" + i), false);
                Future<SessionCommitResult> writer = workers.submit(() -> SessionState.applyOrdinaryResponse(context, tag, promotion));
                try {
                    await(storage.entered);
                    RefreshCoordinator.Acquisition pending = SessionState.acquireRefresh(old.getOwner(), true, null, transport,
                            old.getRefreshTokenRevision());
                    assertNotNull(pending.pending);
                    assertNull(pending.flight);
                    clock.addAndGet(TimeUnit.SECONDS.toNanos(i == 0 ? 10 : 15));
                    assertEquals(TimeUnit.SECONDS.toNanos(i == 0 ? 20 : 5), budget.remainingNanos());
                    storage.release.countDown();
                    assertTrue(writer.get(5, TimeUnit.SECONDS).isSuccess());
                    pending.pending.await(budget);
                } finally { storage.release.countDown(); }
            }
            Future<SessionResolution> leader = workers.submit(() -> RefreshCoordinator.resolve(context, old.getOwner(), true, null, transport));
            await(networkEntered);
            RefreshCoordinator.Acquisition joined = SessionState.acquireRefresh(old.getOwner(), true, null, transport, old.getRefreshTokenRevision());
            assertNotNull(joined.flight);
            assertFalse(joined.flight.isDone());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(6));
            long before = System.nanoTime();
            SessionResolution expiredFollower = RefreshCoordinator.resolve(context, old.getOwner(), true, null, transport,
                    old.getRefreshTokenRevision(), false, budget);
            assertEquals(SessionResolution.Status.REFRESH_FAILED, expiredFollower.getStatus());
            assertTrue(expiredFollower.getError().getMessage().contains("Timed out"));
            assertTrue("Expired budget must not become another 30-second flight wait", System.nanoTime() - before < TimeUnit.SECONDS.toNanos(2));
            assertFalse(joined.flight.isDone());
            assertEquals(1, networkRelease.getCount());
            assertEquals(1, transports.get());
            networkRelease.countDown();
            assertTrue(leader.get(5, TimeUnit.SECONDS).isSuccess());
        } finally { networkRelease.countDown(); workers.shutdownNow(); }
    }

    @Test public void expiredAdmissionBudgetDoesNotReleasePendingWriterAndStartWaitReleasesUnusedFlightSlot() throws Exception {
        SessionSnapshot old = install("same", "one");
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(50);
        RefreshCoordinator.WaitBudget budget = new RefreshCoordinator.WaitBudget(clock::get, 1);
        GateStorage storage = new GateStorage();
        SessionState.setStorageForTests(storage);
        ExecutorService workers = Executors.newCachedThreadPool();
        try {
            SessionRequestTag tag = SuperTokens.captureRequestTag(context);
            SessionResponse update = response(tokens("same", "pending"), false);
            Future<SessionCommitResult> writer = workers.submit(() -> SessionState.applyOrdinaryResponse(context, tag, update));
            await(storage.entered);
            clock.incrementAndGet();
            AtomicInteger transports = new AtomicInteger();
            RefreshCoordinator.Transport transport = (ctx, config, snapshot) -> { transports.incrementAndGet(); return update; };
            SessionResolution timedOut = RefreshCoordinator.resolve(context, old.getOwner(), true, null, transport,
                    old.getRefreshTokenRevision(), false, budget);
            assertEquals(SessionResolution.Status.REFRESH_FAILED, timedOut.getStatus());
            assertEquals(1, storage.release.getCount());
            assertEquals(0, transports.get());
            RefreshCoordinator.Flight notStarted = new RefreshCoordinator.Flight(old, tag.configuration, transport);
            assertFalse(SessionState.startRefresh(notStarted, budget));
            assertTrue(notStarted.isDone());
            assertEquals(SessionResolution.Status.REFRESH_FAILED, notStarted.await().getStatus());
            storage.release.countDown();
            assertTrue(writer.get(5, TimeUnit.SECONDS).isSuccess());
        } finally { storage.release.countDown(); workers.shutdownNow(); }
    }

    @Test public void resolverCarriesBudgetThroughTwoActualAdmissionRechecksAndThenFollowerAwait() throws Exception {
        SessionSnapshot old = install("same", "one");
        CountDownLatch firstAwait = new CountDownLatch(1);
        CountDownLatch advanceFirst = new CountDownLatch(1);
        CountDownLatch secondAwait = new CountDownLatch(1);
        CountDownLatch advanceSecond = new CountDownLatch(1);
        CountDownLatch networkEntered = new CountDownLatch(1);
        CountDownLatch networkRelease = new CountDownLatch(1);
        AtomicInteger clockReads = new AtomicInteger();
        long origin = Long.MAX_VALUE - 10;
        RefreshCoordinator.NanoClock clock = () -> {
            int read = clockReads.getAndIncrement();
            if (read == 0) return origin;
            try {
                if (read == 1) {
                    firstAwait.countDown();
                    await(advanceFirst);
                    return origin + TimeUnit.SECONDS.toNanos(10);
                }
                if (read == 2) {
                    secondAwait.countDown();
                    await(advanceSecond);
                    return origin + TimeUnit.SECONDS.toNanos(25);
                }
                return origin + TimeUnit.SECONDS.toNanos(31);
            } catch (Exception e) { throw new AssertionError(e); }
        };
        RefreshCoordinator.WaitBudget budget = new RefreshCoordinator.WaitBudget(clock, TimeUnit.SECONDS.toNanos(30));
        AtomicInteger transports = new AtomicInteger();
        SessionResponse refreshed = response(tokens("same", "refreshed"), true);
        RefreshCoordinator.Transport transport = (ctx, config, snapshot) -> {
            transports.incrementAndGet();
            networkEntered.countDown();
            try {
                if (!networkRelease.await(5, TimeUnit.SECONDS)) throw new IOException("Network watchdog");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            return refreshed;
        };
        GateStorage first = new GateStorage();
        GateStorage second = new GateStorage();
        ExecutorService workers = Executors.newCachedThreadPool();
        try {
            SessionState.setStorageForTests(first);
            SessionRequestTag firstTag = SuperTokens.captureRequestTag(context);
            SessionResponse firstResponse = response(tokens("same", "p1"), false);
            Future<SessionCommitResult> firstWriter = workers.submit(() -> SessionState.applyOrdinaryResponse(context, firstTag, firstResponse));
            await(first.entered);
            Future<SessionResolution> follower = workers.submit(() -> RefreshCoordinator.resolve(context, old.getOwner(), true, null,
                    transport, old.getRefreshTokenRevision(), false, budget));
            await(firstAwait);
            first.release.countDown();
            assertTrue(firstWriter.get(5, TimeUnit.SECONDS).isSuccess());
            SessionState.setStorageForTests(second);
            SessionRequestTag secondTag = SuperTokens.captureRequestTag(context);
            SessionResponse secondResponse = response(tokens("same", "p2"), false);
            Future<SessionCommitResult> secondWriter = workers.submit(() -> SessionState.applyOrdinaryResponse(context, secondTag, secondResponse));
            await(second.entered);
            advanceFirst.countDown();
            await(secondAwait);
            assertEquals("Follower has not started a transport during either pending write", 0, transports.get());
            second.release.countDown();
            assertTrue(secondWriter.get(5, TimeUnit.SECONDS).isSuccess());
            Future<SessionResolution> leader = workers.submit(() -> RefreshCoordinator.resolve(context, old.getOwner(), true, null, transport));
            await(networkEntered);
            advanceSecond.countDown();
            SessionResolution result = follower.get(5, TimeUnit.SECONDS);
            assertEquals(SessionResolution.Status.REFRESH_FAILED, result.getStatus());
            assertTrue(result.getError().getMessage().contains("Timed out waiting for shared session refresh"));
            assertEquals(4, clockReads.get());
            assertFalse(leader.isDone());
            assertEquals(1, transports.get());
            networkRelease.countDown();
            assertTrue(leader.get(5, TimeUnit.SECONDS).isSuccess());
        } finally {
            first.release.countDown(); second.release.countDown();
            advanceFirst.countDown(); advanceSecond.countDown(); networkRelease.countDown();
            workers.shutdownNow();
        }
    }
}
