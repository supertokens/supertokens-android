package com.supertokens.session;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

final class SessionState {
    private static final ReentrantLock lock = new ReentrantLock();
    private static final ReentrantLock mutations = new ReentrantLock();
    private static PendingMutation pending;
    private static boolean inStorage;
    private static boolean unconfiguredWriteFailed;
    private static SessionSnapshot publication;
    private static final Set<SessionSubscription> subscriptions = new HashSet<>();
    private static final LinkedHashSet<SessionSubscription> readySubscriptions = new LinkedHashSet<>();
    private static boolean dispatching;
    private static SessionStorage storage = new SharedPreferencesSessionStorage();
    private static Configuration configuration;
    private static SessionSnapshot current;
    // UNAVAILABLE also represents terminal partial raw state; a reservation alone cannot distinguish it.
    private static SessionOwner transientOwner;
    private static RefreshCoordinator.Flight refreshFlight;
    private static final Set<RefreshCoordinator.Flight> activeFlights = new HashSet<>();
    private static final int MAX_ACTIVE_FLIGHTS = 8;
    private static long lastAccessMarker;
    private static String committedMarker;

    private SessionState() {}

    // Writers enter before validation; neither readers nor flight waiters acquire this lock.
    static void beginMutation() {
        if (lock.isHeldByCurrentThread()) throw new IllegalStateException("State-to-writer lock inversion");
        mutations.lock();
        if (inStorage) {
            mutations.unlock();
            throw new IllegalStateException("Storage adapter reentered a session mutation");
        }
    }

    static void endMutation() {
        PendingMutation completed = null;
        SessionSnapshot published = null;
        if (mutations.getHoldCount() == 1) {
            lock.lock();
            try {
                completed = pending;
                pending = null;
                published = publication;
                publication = null;
            } finally { lock.unlock(); }
        }
        mutations.unlock();
        if (completed != null) completed.finished.countDown();
        if (published != null) {
            lock.lock();
            try {
                for (SessionSubscription subscription : subscriptions) {
                    subscription.offer(published);
                    readySubscriptions.add(subscription);
                }
            } finally { lock.unlock(); }
        }
    }

    static final class PendingMutation {
        final Configuration configuration;
        final SessionSnapshot previous;
        final Thread writer = Thread.currentThread();
        final CountDownLatch finished = new CountDownLatch(1);

        PendingMutation(Configuration configuration, SessionSnapshot previous) {
            this.configuration = configuration;
            this.previous = previous;
        }

        void await() throws IOException {
            await(new RefreshCoordinator.WaitBudget());
        }

        void await(RefreshCoordinator.WaitBudget budget) throws IOException {
            if (writer == Thread.currentThread()) throw new IOException("Reentrant resolution during persistence");
            try {
                long remaining = budget.remainingNanos();
                if (remaining <= 0 || !finished.await(remaining, TimeUnit.NANOSECONDS)) {
                    throw new IOException("Timed out waiting for durable session publication");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for durable session publication", e);
            }
        }
    }

    // The storage seam drops exactly one state-lock hold; writer serialization never drops.
    private static PendingMutation reserveStorage() {
        if (!mutations.isHeldByCurrentThread() || lock.getHoldCount() != 1) {
            throw new IllegalStateException("Storage requires writer serialization and one state-lock hold");
        }
        if (pending == null) pending = new PendingMutation(configuration, current);
        inStorage = true;
        return pending;
    }

    private static void finishStorage(PendingMutation reserved) {
        lock.lock();
        inStorage = false;
        if (pending != reserved || configuration != reserved.configuration) {
            throw new IllegalStateException("Durable mutation reservation was superseded");
        }
    }

    private static SessionStorage.Record readStorage(Context context) throws IOException {
        PendingMutation reserved = reserveStorage();
        SessionStorage adapter = storage;
        lock.unlock();
        try { return adapter.read(context); }
        finally { finishStorage(reserved); }
    }

    private static void writeStorage(Context context, SessionStorage.Record record) throws IOException {
        PendingMutation reserved = reserveStorage();
        SessionStorage adapter = storage;
        lock.unlock();
        try { adapter.write(context, record); }
        finally { finishStorage(reserved); }
        lastAccessMarker = Math.max(lastAccessMarker, parseMarker(record.lastAccessTokenUpdate));
        committedMarker = record.lastAccessTokenUpdate;
        unconfiguredWriteFailed = false;
    }

    static LegacyEventBatch editLegacy(Context context, SessionStorage.LegacyEdit edit, boolean notifyPayload) {
        beginMutation();
        try {
            boolean sessionEdit = edit.changesSession();
            lock.lock();
            try {
                SessionSnapshot previous = current;
                PendingMutation reserved = reserveStorage();
                if (previous != null && sessionEdit) {
                    previous.getOwner().invalidate();
                    invalidateFlightLocked();
                    current = unavailableSnapshot(configuration, previous.getRevision(), UUID.randomUUID().toString());
                }
                SessionStorage adapter = storage;
                SessionStorage.LegacyEdit reservedEdit = edit.forConfiguredSession(previous != null);
                lock.unlock();
                try { adapter.editLegacy(context, reservedEdit); }
                finally { finishStorage(reserved); }
                if (sessionEdit) unconfiguredWriteFailed = false;
            } catch (IOException e) {
                if (current != null && sessionEdit) enterRecoveryLocked(context, pending.previous,
                        nextRevision(current.getRevision()), e);
                else if (sessionEdit) unconfiguredWriteFailed = true;
                return LegacyEventBatch.EMPTY;
            } finally { lock.unlock(); }
            if (!sessionEdit) return LegacyEventBatch.EMPTY;
            return legacyStorageChanged(context, notifyPayload);
        } finally { endMutation(); }
    }

    static boolean isProjectedKey(String name) {
        return Constants.ACCESS_TOKEN_PREFS_KEY.equals(name) || Constants.REFRESH_TOKEN_PREFS_KEY.equals(name)
                || Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY.equals(name) || "sIRTFrontend".equals(name);
    }

    static String storedValue(Context context, String name) {
        return storedValue(context, name, "st-storage-item-" + name);
    }

    static String storedFrontToken(Context context) {
        return storedValue(context, Constants.FRONT_TOKEN_PREFS_KEY, Constants.FRONT_TOKEN_PREFS_KEY);
    }

    private static String storedValue(Context context, String name, String key) {
        lock.lock();
        try {
            if (current != null || pending != null || unconfiguredWriteFailed) return projectedValueLocked(name);
        } finally { lock.unlock(); }
        // Preserve the pre-init raw read, but recheck if initialization overlapped it.
        String legacy = Utils.getSharedPreferences(context).getString(key, null);
        lock.lock();
        try { return current != null || pending != null || unconfiguredWriteFailed ? projectedValueLocked(name) : legacy; }
        finally { lock.unlock(); }
    }

    private static String projectedValueLocked(String name) {
        if (current == null || current.getStatus() != SessionSnapshot.Status.AVAILABLE) return null;
        SessionTokens tokens = current.getInternalTokens();
        if (Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY.equals(name)) return committedMarker;
        if (tokens == null) return null;
        if (Constants.FRONT_TOKEN_PREFS_KEY.equals(name)) return tokens.getFrontToken();
        if (Constants.ACCESS_TOKEN_PREFS_KEY.equals(name)) return tokens.getAccessToken();
        if (Constants.REFRESH_TOKEN_PREFS_KEY.equals(name)) return tokens.getRefreshToken();
        return null;
    }

    static String antiCSRF(String marker) {
        lock.lock();
        try {
            if (current == null || current.getStatus() != SessionSnapshot.Status.AVAILABLE
                    || !marker.equals(committedMarker)) return null;
            SessionTokens tokens = current.getInternalTokens();
            return tokens == null ? null : tokens.getAntiCSRF();
        } finally { lock.unlock(); }
    }


    static final class Configuration {
        final Utils.NormalisedInputType values;
        final String id;
        final String refreshTokenUrl;
        final String signOutUrl;

        Configuration(Utils.NormalisedInputType values) {
            this.values = new Utils.NormalisedInputType(values.apiDomain, values.apiBasePath,
                    values.sessionExpiredStatusCode, values.maxRetryAttemptsForSessionRefresh,
                    values.sessionTokenBackendDomain, values.tokenTransferMethod,
                    values.customHeaderMapper, values.eventHandler);
            this.id = configurationId(this.values);
            this.refreshTokenUrl = this.values.apiDomain + this.values.apiBasePath + "/session/refresh";
            this.signOutUrl = this.values.apiDomain + this.values.apiBasePath + "/signout";
        }

        boolean callbacksEqual(Configuration other) {
            return providersEqual(values.customHeaderMapper, other.values.customHeaderMapper)
                    && handlersEqual(values.eventHandler, other.values.eventHandler);
        }

        private static boolean providersEqual(CustomHeaderProvider first,
                                              CustomHeaderProvider second) {
            if (first == second) return true;
            return first != null && second != null
                    && first.getClass() == CustomHeaderProvider.DefaultCustomHeaderMapper.class
                    && second.getClass() == CustomHeaderProvider.DefaultCustomHeaderMapper.class;
        }

        private static boolean handlersEqual(EventHandler first, EventHandler second) {
            if (first == second) return true;
            return first != null && second != null
                    && first.getClass() == EventHandler.DefaultEventHandler.class
                    && second.getClass() == EventHandler.DefaultEventHandler.class;
        }

        private static String configurationId(Utils.NormalisedInputType value) {
            String canonical = part(value.apiDomain) + part(value.apiBasePath)
                    + part(Integer.toString(value.sessionExpiredStatusCode))
                    + part(Integer.toString(value.maxRetryAttemptsForSessionRefresh))
                    + part(value.sessionTokenBackendDomain) + part(value.tokenTransferMethod);
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(canonical.getBytes(Charset.forName("UTF-8")));
                StringBuilder result = new StringBuilder();
                for (byte item : digest) {
                    result.append(String.format("%02x", item & 0xff));
                }
                return result.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        private static String part(String value) {
            return value == null ? "-1:" : value.length() + ":" + value;
        }
    }

    static SessionCommitResult configure(Context context, Utils.NormalisedInputType values,
                                         boolean reconfigure) {
        beginMutation();
        try {
            lock.lock();
            try {
                if (configuration != null && !reconfigure) return success();
                Configuration newConfiguration = new Configuration(values);
                if (configuration != null) {
                    if (configuration.id.equals(newConfiguration.id)) {
                        if (configuration.callbacksEqual(newConfiguration)) {
                            return new SessionCommitResult(SessionCommitResult.Status.SUCCESS,
                                    current, null);
                        }
                        return replaceConfigurationLocked(context, newConfiguration);
                    }
                    return replaceConfigurationLocked(context, newConfiguration);
                }

                configuration = newConfiguration;
                lastAccessMarker = 0;
                committedMarker = null;
                try {
                    if (unconfiguredWriteFailed) {
                        return enterRecoveryLocked(context, null, 1,
                                new IOException("A pre-init raw write was not acknowledged"));
                    }
                    SessionStorage.Record record = readStorage(context);
                    if (record == null) {
                        SessionStorage.Record absence = absentRecord(newConfiguration, 1,
                                UUID.randomUUID().toString());
                        current = snapshotFromRecord(absence, newConfiguration, new SessionOwner());
                        return success();
                    }
                    if (record.schema == 0) {
                        return importLegacyLocked(context, record, false);
                    }
                    SessionSnapshot loaded;
                    try {
                        loaded = snapshotFromRecord(record, newConfiguration, new SessionOwner());
                    } catch (IOException | IllegalArgumentException inconsistent) {
                        if (!record.evidenceMissing || (!hasAnyCredential(record.tokens) && record.lastAccessTokenUpdate == null)) {
                            throw inconsistent;
                        }
                        return importLegacyLocked(context, record, false);
                    }
                    // Startup configuration is metadata; the next durable transition records the current ID.
                    if (record.evidenceMissing) writeStorage(context, record);
                    current = loaded;
                    lastAccessMarker = parseMarker(record.lastAccessTokenUpdate);
                    committedMarker = record.lastAccessTokenUpdate;
                    if (current.getStatus() == SessionSnapshot.Status.RECOVERY_REQUIRED) {
                        return new SessionCommitResult(SessionCommitResult.Status.RECOVERY_REQUIRED,
                                current, null);
                    }
                    return success();
                } catch (IOException | IllegalArgumentException e) {
                    return enterRecoveryLocked(context, null, 1, e);
                }
            } finally { lock.unlock(); }

        } finally { endMutation(); }
    }

    private static SessionCommitResult replaceConfigurationLocked(Context context,
                                                                   Configuration newConfiguration) {
        SessionSnapshot previous = current;
        long revision = nextRevision(previous.getRevision());
        String adoption = UUID.randomUUID().toString();
        previous.getOwner().invalidate();
        invalidateFlightLocked();
        configuration = newConfiguration;
        current = unavailableSnapshot(newConfiguration, revision, adoption);
        SessionStorage.Record absent = absentRecord(newConfiguration, revision, adoption);
        try {
            writeStorage(context, absent);
            current = snapshotFromRecord(absent, newConfiguration, newOwnerAfterReplacement());
            if (previous.getStatus() == SessionSnapshot.Status.AVAILABLE) {
                return success(offerPublishedLocked(current, EventHandler.EventType.SIGN_OUT));
            } else {
                return success(offerPublishedLocked(current));
            }
        } catch (IOException e) {
            return enterRecoveryLocked(context, previous, revision, e);
        }
    }

    static void setTokenTransferMethodForTests(String method) {
        beginMutation();
        try {
            lock.lock();
            try {
                Utils.NormalisedInputType active = configuration.values;
                if (active.tokenTransferMethod.equals(method)) return;
                Configuration replacement = new Configuration(new Utils.NormalisedInputType(active.apiDomain,
                        active.apiBasePath, active.sessionExpiredStatusCode, active.maxRetryAttemptsForSessionRefresh,
                        active.sessionTokenBackendDomain, method, active.customHeaderMapper, active.eventHandler));
                // Preserve the historical session while fencing work captured under the previous preference.
                invalidateFlightLocked();
                configuration = replacement;
                current = new SessionSnapshot(current.getOwner(), current.getRevision(), current.getAccessTokenRevision(),
                        current.getRefreshTokenRevision(), replacement.id, current.getAdoptionId(),
                        current.getAdoptionCorrelation(), current.getStatus(), current.getSessionHandle(),
                        current.getUserId(), current.getTenantId(), current.getAccessTokenExpiry(), current.getInternalTokens());
            } finally { lock.unlock(); }

        } finally { endMutation(); }
    }

    private static SessionCommitResult importLegacyLocked(Context context,
                                                           SessionStorage.Record legacy,
                                                           boolean fromRawWrite) throws IOException {
        long revision = current == null ? 1 : nextRevision(current.getRevision());
        String adoption = UUID.randomUUID().toString();
        SessionIdentity identity;
        try {
            identity = SessionIdentity.parseLegacy(legacy.tokens);
        } catch (IllegalArgumentException unreadable) {
            if (fromRawWrite) {
                // Legacy setters assemble tuples over several calls. Do not erase their earlier writes.
                if (legacy.tokens != null && legacy.tokens.getFrontToken() == null && hasAnyCredential(legacy.tokens)) {
                    current = unavailableSnapshot(configuration, revision, adoption);
                    transientOwner = null;
                    offerPublishedLocked(current);
                    return new SessionCommitResult(SessionCommitResult.Status.RECOVERY_REQUIRED, current, null);
                }
                current = absentSnapshot(configuration, revision, adoption);
                return success();
            }
            SessionStorage.Record absent = absentRecord(configuration, revision, adoption);
            writeStorage(context, absent);
            current = snapshotFromRecord(absent, configuration, new SessionOwner());
            return success();
        }

        String marker = legacy.lastAccessTokenUpdate == null || legacy.lastAccessTokenUpdate.isEmpty()
                ? nextMarker(null) : legacy.lastAccessTokenUpdate;
        SessionStorage.Record imported = new SessionStorage.Record(1,
                SessionSnapshot.Status.AVAILABLE, revision, revision, revision,
                configuration.id, adoption, null, legacy.tokens, null, null, null, 0,
                marker, SessionStorage.Record.Validation.LEGACY, false);
        SessionStorage.Record identified = withIdentity(imported, identity);
        writeStorage(context, identified);
        current = snapshotFromRecord(identified, configuration, new SessionOwner());
        return success();
    }

    private static SessionStorage.Record withIdentity(SessionStorage.Record record,
                                                       SessionIdentity identity) {
        return new SessionStorage.Record(record.schema, record.status, record.revision,
                record.accessTokenRevision, record.refreshTokenRevision, record.configurationId,
                record.adoptionId, record.adoptionCorrelation, record.tokens,
                identity.sessionHandle, identity.userId, identity.tenantId,
                identity.accessTokenExpiry, record.lastAccessTokenUpdate, record.validation, record.evidenceMissing);
    }

    static SessionSnapshot snapshot() {
        lock.lock();
        try {
            if (current == null) {
                throw new IllegalStateException("SuperTokens.init function not called");
            }
            return current;
        } finally { lock.unlock(); }
    }

    static long currentRefreshRevision() {
        lock.lock();
        try {
            return current == null ? -1 : current.getRefreshTokenRevision();
        } finally { lock.unlock(); }
    }

    static Configuration configuration() {
        lock.lock();
        try {
            if (configuration == null) {
                throw new IllegalStateException("SuperTokens.init function not called");
            }
            return configuration;
        } finally { lock.unlock(); }
    }

    static SessionRequestTag capture() {
        lock.lock();
        try {
            if (current == null || configuration == null) {
                throw new IllegalStateException("SuperTokens.init function not called");
            }
            return new SessionRequestTag(current, configuration);
        } finally { lock.unlock(); }
    }

    static SessionSubscription observe(SessionObserver observer) {
        if (observer == null) {
            throw new NullPointerException("observer");
        }
        lock.lock();
        try {
            if (current == null) {
                throw new IllegalStateException("SuperTokens.init function not called");
            }
            SessionSubscription subscription = new SessionSubscription(current, observer);
            subscriptions.add(subscription);
            return subscription;
        } finally { lock.unlock(); }
    }

    static void removeSubscription(SessionSubscription subscription) {
        lock.lock();
        try {
            subscriptions.remove(subscription);
            readySubscriptions.remove(subscription);
        } finally { lock.unlock(); }
    }

    static SessionCommitResult install(Context context, SessionOwner expectedOwner,
                                       SessionTokens tokens, @Nullable String correlation) {
        beginMutation();
        try {
            SessionIdentity identity;
            Configuration capturedConfiguration;
            lock.lock();
            try {
                if (current == null || configuration == null) {
                    return new SessionCommitResult(SessionCommitResult.Status.NOT_CONFIGURED, null, null);
                }
                if (!isCurrentLocked(expectedOwner)) {
                    return staleCommit();
                }
                capturedConfiguration = configuration;
            } finally { lock.unlock(); }

            try {
                validateCorrelation(correlation);
                validateCompleteHeaderTokens(tokens);
                identity = SessionIdentity.parse(tokens, System.currentTimeMillis());
                if (identity.sessionHandle == null) {
                    throw new IllegalArgumentException("A stable session handle is required");
                }
            } catch (IllegalArgumentException e) {
                lock.lock();
                try {
                    return new SessionCommitResult(SessionCommitResult.Status.INVALID_SESSION,
                            current, null);
                } finally { lock.unlock(); }
            }

            lock.lock();
            try {
                if (configuration != capturedConfiguration || !isCurrentLocked(expectedOwner)) {
                    return staleCommit();
                }
                SessionSnapshot previous = current;
                long revision = nextRevision(previous.getRevision());
                String adoption = UUID.randomUUID().toString();
                previous.getOwner().invalidate();
                invalidateFlightLocked();
                current = unavailableSnapshot(configuration, revision, adoption);
                String marker = nextMarker(null);
                SessionStorage.Record record = new SessionStorage.Record(1,
                        SessionSnapshot.Status.AVAILABLE, revision, revision, revision,
                        configuration.id, adoption, correlation, tokens, identity.sessionHandle,
                        identity.userId, identity.tenantId, identity.accessTokenExpiry, marker);
                try {
                    writeStorage(context, record);
                    SessionOwner owner = newOwnerAfterReplacement();
                    current = snapshotFromRecord(record, configuration, owner);
                    if (previous.getStatus() == SessionSnapshot.Status.ABSENT) {
                        return success(offerPublishedLocked(current, EventHandler.EventType.SESSION_CREATED));
                    } else if (payloadChanged(previous, current)) {
                        return success(offerPublishedLocked(current, EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED));
                    } else {
                        return success(offerPublishedLocked(current));
                    }
                } catch (IOException e) {
                    return enterRecoveryLocked(context, previous, revision, e);
                }
            } finally { lock.unlock(); }

        } finally { endMutation(); }
    }

    static SessionCommitResult clear(Context context, SessionOwner expectedOwner) {
        beginMutation();
        try {
            lock.lock();
            try {
                if (current == null || configuration == null) {
                    return new SessionCommitResult(SessionCommitResult.Status.NOT_CONFIGURED, null, null);
                }
                if (!isCurrentLocked(expectedOwner)) {
                    return staleCommit();
                }
                SessionSnapshot previous = current;
                long revision = nextRevision(previous.getRevision());
                String adoption = UUID.randomUUID().toString();
                previous.getOwner().invalidate();
                invalidateFlightLocked();
                current = unavailableSnapshot(configuration, revision, adoption);
                SessionStorage.Record absent = absentRecord(configuration, revision, adoption);
                try {
                    writeStorage(context, absent);
                    current = snapshotFromRecord(absent, configuration, newOwnerAfterReplacement());
                    if (previous.getStatus() == SessionSnapshot.Status.AVAILABLE) {
                        return success(offerPublishedLocked(current, EventHandler.EventType.SIGN_OUT));
                    } else {
                        return success(offerPublishedLocked(current));
                    }
                } catch (IOException e) {
                    return enterRecoveryLocked(context, previous, revision, e);
                }
            } finally { lock.unlock(); }

        } finally { endMutation(); }
    }

    static LegacyEventBatch legacyStorageChanged(Context context, boolean notifyPayload) {
        beginMutation();
        try {
            lock.lock();
            try {
                if (configuration == null || current == null) {
                    return LegacyEventBatch.EMPTY;
                }
                SessionSnapshot previous = pending == null ? current : pending.previous;
                current.getOwner().invalidate();
                invalidateFlightLocked();
                long revision = nextRevision(current.getRevision());
                try {
                    SessionStorage.Record legacy = readStorage(context);
                    if (legacy == null) {
                        SessionStorage.Record absent = absentRecord(configuration, revision,
                                UUID.randomUUID().toString());
                        writeStorage(context, absent);
                        current = snapshotFromRecord(absent, configuration, new SessionOwner());
                        offerPublishedLocked(current);
                        return LegacyEventBatch.EMPTY;
                    }
                    SessionCommitResult result = importLegacyLocked(context, legacy, true);
                    if (result.isSuccess()) {
                        if (notifyPayload && previous.getStatus() == SessionSnapshot.Status.AVAILABLE
                                && current.getStatus() == SessionSnapshot.Status.AVAILABLE
                                && payloadChanged(previous, current)) {
                            return offerPublishedLocked(current, EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED);
                        }
                        return offerPublishedLocked(current);
                    }
                } catch (IOException | IllegalArgumentException e) {
                    enterRecoveryLocked(context, previous, revision, e);
                }
                return LegacyEventBatch.EMPTY;
            } finally { lock.unlock(); }

        } finally { endMutation(); }
    }

    static SessionResolution resolutionWithoutRefresh(SessionOwner expectedOwner,
                                                       boolean forceRefresh,
                                                       @Nullable String failedAccessToken) {
        lock.lock();
        try {
            return resolutionWithoutRefreshLocked(expectedOwner, forceRefresh, failedAccessToken);
        } finally { lock.unlock(); }
    }

    private static SessionResolution resolutionWithoutRefreshLocked(SessionOwner expectedOwner,
                                                                     boolean forceRefresh,
                                                                     @Nullable String failedAccessToken) {
        SessionResolution terminal = resolutionTerminalLocked(expectedOwner);
        if (terminal != null) return terminal;
        if (forceRefresh) return null;
        SessionTokens tokens = current.getInternalTokens();
        String access = tokens == null ? null : tokens.getAccessToken();
        boolean failedTokenAlreadyReplaced = failedAccessToken != null
                && (access == null || !failedAccessToken.equals(access));
        boolean unexpired = current.getAccessTokenExpiry() > System.currentTimeMillis();
        if (unexpired && (failedAccessToken == null || failedTokenAlreadyReplaced)) {
            return new SessionResolution(SessionResolution.Status.SUCCESS, current, null);
        }
        return null;
    }

    private static SessionResolution resolutionTerminalLocked(SessionOwner expectedOwner) {
        if (current == null || configuration == null) {
            return new SessionResolution(SessionResolution.Status.NOT_CONFIGURED, null, null);
        }
        if (!isCurrentLocked(expectedOwner)) {
            return new SessionResolution(SessionResolution.Status.STALE_OWNER, current,
                    new StaleSessionException());
        }
        if (current.getStatus() == SessionSnapshot.Status.ABSENT) {
            return new SessionResolution(SessionResolution.Status.NO_SESSION, current, null);
        }
        if (current.getStatus() != SessionSnapshot.Status.AVAILABLE) {
            // Only the reserved replacement view is benign at legacy entry points. A failed
            // commit or a terminal partial raw tuple must retain its recovery error.
            boolean pendingReplacement = current.getStatus() == SessionSnapshot.Status.UNAVAILABLE
                    && pending != null && current.getOwner() == transientOwner;
            return new SessionResolution(SessionResolution.Status.RECOVERY_REQUIRED, current,
                    new IOException("Session storage recovery is required"), false, pendingReplacement);
        }
        return null;
    }

    static RefreshCoordinator.Acquisition acquireRefresh(SessionOwner expectedOwner,
                                                          boolean forceRefresh,
                                                          @Nullable String failedAccessToken,
                                                          RefreshCoordinator.Transport transport,
                                                          long entryRefreshRevision) {
        return acquireRefresh(expectedOwner, forceRefresh, failedAccessToken, transport, entryRefreshRevision, false);
    }

    static RefreshCoordinator.Acquisition acquireRefresh(SessionOwner expectedOwner,
                                                          boolean forceRefresh,
                                                          @Nullable String failedAccessToken,
                                                          RefreshCoordinator.Transport transport,
                                                          long entryRefreshRevision, boolean compatibleResponse) {
        lock.lock();
        try {
            SessionResolution rechecked = resolutionWithoutRefreshLocked(expectedOwner,
                    forceRefresh, failedAccessToken);
            if (rechecked != null) {
                return new RefreshCoordinator.Acquisition(null, false, rechecked);
            }
            if (current.getRefreshTokenRevision() != entryRefreshRevision
                    && current.getAccessTokenExpiry() > System.currentTimeMillis()) {
                return new RefreshCoordinator.Acquisition(null, false,
                        new SessionResolution(SessionResolution.Status.SUCCESS, current, null));
            }
            long now = System.nanoTime();
            if (refreshFlight != null && sameLineage(refreshFlight, current)) {
                if (!refreshFlight.isDone() && refreshFlight.isLeaderThread()) {
                    IOException error = new IOException("Reentrant refresh on the transport leader");
                    return new RefreshCoordinator.Acquisition(null, false,
                            new SessionResolution(SessionResolution.Status.REFRESH_FAILED,
                                    current, error));
                }
                if (!refreshFlight.isDone() || refreshFlight.isInCooldown(now)) {
                    if (!refreshFlight.isDone() && compatibleResponse) refreshFlight.compatibleResponses = true;
                    return new RefreshCoordinator.Acquisition(refreshFlight, false, null);
                }
            }
            if (pending != null) return new RefreshCoordinator.Acquisition(pending);
            if (activeFlights.size() >= MAX_ACTIVE_FLIGHTS) {
                return new RefreshCoordinator.Acquisition(null, false,
                        new SessionResolution(SessionResolution.Status.REFRESH_FAILED, current,
                                new IOException("Too many unfinished refresh transports")));
            }
            RefreshCoordinator.Flight flight = new RefreshCoordinator.Flight(current,
                    configuration, transport);
            flight.compatibleResponses = compatibleResponse;
            activeFlights.add(flight);
            refreshFlight = flight;
            return new RefreshCoordinator.Acquisition(flight, true, null);
        } finally { lock.unlock(); }
    }

    private static boolean sameLineage(RefreshCoordinator.Flight flight, SessionSnapshot snapshot) {
        SessionTokens capturedTokens = flight.captured.getInternalTokens();
        SessionTokens currentTokens = snapshot.getInternalTokens();
        String capturedRefresh = capturedTokens == null ? null : capturedTokens.getRefreshToken();
        String currentRefresh = currentTokens == null ? null : currentTokens.getRefreshToken();
        return flight.captured.getOwner() == snapshot.getOwner()
                && flight.configuration == configuration
                && flight.captured.getRefreshTokenRevision() == snapshot.getRefreshTokenRevision()
                && equal(capturedRefresh, currentRefresh);
    }

    static boolean startRefresh(RefreshCoordinator.Flight flight) {
        return startRefresh(flight, new RefreshCoordinator.WaitBudget());
    }

    static boolean startRefresh(RefreshCoordinator.Flight flight, RefreshCoordinator.WaitBudget budget) {
        while (true) {
            PendingMutation waiting;
            lock.lock();
            try {
                if (flight.isDone()) {
                    activeFlights.remove(flight);
                    return false;
                }
                waiting = pending;
                if (waiting == null) {
                    if (!sameLineage(flight, current)) {
                        activeFlights.remove(flight);
                        flight.invalidate();
                        return false;
                    }
                    return true;
                }
            } finally { lock.unlock(); }
            try { waiting.await(budget); }
            catch (IOException e) {
                lock.lock();
                try {
                    activeFlights.remove(flight);
                    flight.fail(current, e, 0);
                    if (refreshFlight == flight) refreshFlight = null;
                } finally { lock.unlock(); }
                return false;
            }
        }
    }

    static boolean isCurrent(SessionOwner owner, Configuration expectedConfiguration) {
        lock.lock();
        try {
            return configuration == expectedConfiguration && isCurrentLocked(owner);
        } finally { lock.unlock(); }
    }

    static LegacyEventBatch completeRefresh(Context context, RefreshCoordinator.Flight flight,
                                @Nullable SessionResponse response, @Nullable IOException error) {
        beginMutation();
        try {
            lock.lock();
            try {
                boolean compatibleResponse = flight.compatibleResponses;
                if (flight.isDone()) return LegacyEventBatch.EMPTY;
                if (current == null || configuration != flight.configuration
                        || !isCurrentLocked(flight.captured.getOwner())
                        || current.getRefreshTokenRevision() != flight.captured.getRefreshTokenRevision()) {
                    if (refreshFlight == flight) refreshFlight = null;
                    flight.invalidate();
                    return LegacyEventBatch.EMPTY;
                }
                if (error != null) {
                    failRefreshLocked(flight, error, error instanceof RefreshCoordinator.ConfigurationException
                            ? 0 : RefreshCoordinator.UNCERTAIN_COOLDOWN_MS);
                    return LegacyEventBatch.EMPTY;
                }
                if (response == null) {
                    failRefreshLocked(flight, new IOException("Refresh returned no response"),
                            RefreshCoordinator.UNCERTAIN_COOLDOWN_MS);
                    return LegacyEventBatch.EMPTY;
                }
                if (response.code == configuration.values.sessionExpiredStatusCode) {
                    if (current.getAccessTokenRevision() != flight.captured.getAccessTokenRevision()) {
                        failRefreshLocked(flight, new IOException("Obsolete refresh removal"),
                                RefreshCoordinator.UNCERTAIN_COOLDOWN_MS);
                    } else {
                        return clearForRefreshLocked(context, flight);
                    }
                    return LegacyEventBatch.EMPTY;
                }
                if (response.code < 200 || response.code >= 300) {
                    failRefreshLocked(flight, new IOException("Session refresh failed with response code "
                            + response.code + (response.message.isEmpty() ? "" : ": " + response.message)),
                            response.retryAfterMillis(System.currentTimeMillis()));
                    return LegacyEventBatch.EMPTY;
                }

                SessionSnapshot previous = current;
                try {
                    SessionResponse.Candidate candidate = response.refreshCandidate(previous);
                    boolean headerCredentials = candidate.completeHeader;
                    SessionTokens tokens = candidate.tokens;
                    SessionTokens previousTokens = previous.getInternalTokens();
                    boolean previouslyHeader = previousTokens != null
                            && previousTokens.hasCompleteHeaderTokens();
                    if ((previouslyHeader && !headerCredentials)
                            || (tokens.getAccessToken() == null && tokens.getRefreshToken() != null)) {
                        throw new IllegalArgumentException("Refresh returned a partial credential tuple");
                    }
                    if (headerCredentials) {
                        validateCompleteHeaderTokens(tokens);
                        String oldAccess = previousTokens == null ? null
                                : previous.getInternalTokens().getAccessToken();
                        if (!compatibleResponse && tokens.getAccessToken().equals(oldAccess)) {
                            throw new IllegalArgumentException("Refresh reused the rejected access token");
                        }
                    }
                    SessionIdentity identity = SessionIdentity.parseResponse(tokens, compatibleResponse);
                    if (!sameRefreshIdentity(previous, identity)) {
                        throw new IllegalArgumentException("Refresh changed session identity");
                    }

                    long revision = nextRevision(previous.getRevision());
                    long refreshRevision = revision;
                    SessionStorage.Record record = new SessionStorage.Record(1,
                            SessionSnapshot.Status.AVAILABLE, revision, revision, refreshRevision,
                            configuration.id, previous.getAdoptionId(), previous.getAdoptionCorrelation(),
                            tokens, identity.sessionHandle, identity.userId, identity.tenantId,
                            identity.accessTokenExpiry, nextMarker(null), responseValidation(tokens, compatibleResponse), false);
                    writeStorage(context, record);
                    current = snapshotFromRecord(record, configuration, previous.getOwner());
                    if (refreshFlight == flight) refreshFlight = null;
                    LegacyEventBatch events;
                    if (payloadChanged(previous, current)) {
                        events = offerPublishedLocked(current, EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED,
                                EventHandler.EventType.REFRESH_SESSION);
                    } else {
                        events = offerPublishedLocked(current, EventHandler.EventType.REFRESH_SESSION);
                    }
                    flight.succeed(current);
                    return events;
                } catch (IllegalArgumentException e) {
                    failRefreshLocked(flight, new IOException("Malformed refresh response", e), 0);
                } catch (IOException e) {
                    if (refreshFlight == flight) refreshFlight = null;
                    SessionCommitResult recovery = enterRecoveryLocked(context,
                            previous, nextRevision(previous.getRevision()), e);
                    flight.fail(current, recovery.getPersistenceError(), 0);
                }
                return LegacyEventBatch.EMPTY;
            } finally { lock.unlock(); }

        } finally {
            lock.lock();
            try { activeFlights.remove(flight); } finally { lock.unlock(); }
            endMutation();
        }
    }

    private static void failRefreshLocked(RefreshCoordinator.Flight flight, IOException error,
                                          long cooldown) {
        long now = System.nanoTime();
        long cooldownNanos = cooldown <= 0 ? 0 : cooldown * 1000000L;
        long notBefore = cooldownNanos == 0 ? 0 : now + cooldownNanos;
        if (cooldownNanos > 0 && notBefore < now) notBefore = Long.MAX_VALUE;
        flight.fail(current, error, notBefore);
        if (refreshFlight == flight && cooldown <= 0) {
            refreshFlight = null;
        }
    }

    private static LegacyEventBatch clearForRefreshLocked(Context context, RefreshCoordinator.Flight flight) {
        SessionSnapshot previous = current;
        long revision = nextRevision(previous.getRevision());
        String adoption = UUID.randomUUID().toString();
        previous.getOwner().invalidate();
        if (refreshFlight == flight) refreshFlight = null;
        current = unavailableSnapshot(configuration, revision, adoption);
        SessionStorage.Record absent = absentRecord(configuration, revision, adoption);
        try {
            writeStorage(context, absent);
            current = snapshotFromRecord(absent, configuration, new SessionOwner());
            LegacyEventBatch events = offerPublishedLocked(current, EventHandler.EventType.UNAUTHORISED);
            flight.noSession(current);
            return events;
        } catch (IOException e) {
            SessionCommitResult recovery = enterRecoveryLocked(context, previous,
                    revision, e);
            flight.fail(current, recovery.getPersistenceError(), 0);
            return LegacyEventBatch.EMPTY;
        }
    }

    private static boolean sameStoredIdentity(SessionSnapshot snapshot, SessionIdentity identity) {
        if (snapshot.getSessionHandle() != null && identity.sessionHandle != null) {
            return snapshot.getSessionHandle().equals(identity.sessionHandle)
                    && equal(snapshot.getTenantId(), identity.tenantId)
                    && equal(snapshot.getUserId(), identity.userId);
        }
        return snapshot.getSessionHandle() == null
                && (identity.sessionHandle == null || snapshot.getTokens() == null)
                && equal(snapshot.getUserId(), identity.userId)
                && equal(snapshot.getTenantId(), identity.tenantId);
    }

    private static boolean sameRefreshIdentity(SessionSnapshot snapshot, SessionIdentity identity) {
        if (snapshot.getSessionHandle() != null) {
            return snapshot.getSessionHandle().equals(identity.sessionHandle)
                    && equal(snapshot.getTenantId(), identity.tenantId);
        }
        // Without a known handle, user+tenant continuity is the only available evidence.
        // A matching refresh may enrich the handle; an unproven user change cannot.
        return equal(snapshot.getUserId(), identity.userId) && equal(snapshot.getTenantId(), identity.tenantId);
    }

    private static SessionStorage.Record.Validation responseValidation(SessionTokens tokens, boolean compatible) {
        return compatible || !tokens.hasCompleteHeaderTokens()
                ? SessionStorage.Record.Validation.LEGACY : SessionStorage.Record.Validation.STRICT;
    }

    // Only call after the sign-out endpoint's successful body has been validated.
    static SessionCommitResult applyConfirmedSignOutResponse(Context context, SessionRequestTag tag,
                                                              SessionResponse response) {
        beginMutation();
        try {
            SessionResponse.Candidate candidate;
            try {
                candidate = response.ordinaryCandidate();
            } catch (IllegalArgumentException e) {
                lock.lock();
                try {
                    return new SessionCommitResult(SessionCommitResult.Status.INVALID_SESSION, current, null);
                } finally { lock.unlock(); }
            }
            if (response.code < 200 || response.code >= 300 || candidate == null || !candidate.removal) {
                return applyOrdinaryResponse(context, tag, response);
            }
            lock.lock();
            try {
                if (current == null || configuration == null) {
                    return new SessionCommitResult(SessionCommitResult.Status.NOT_CONFIGURED, null, null);
                }
                if (configuration != tag.configuration || !isCurrentLocked(tag.getOwner())) {
                    return staleCommit();
                }
                if (current.getStatus() == SessionSnapshot.Status.RECOVERY_REQUIRED
                        || current.getStatus() == SessionSnapshot.Status.UNAVAILABLE) {
                    return new SessionCommitResult(SessionCommitResult.Status.RECOVERY_REQUIRED, current, null);
                }
                return clearForResponseLocked(context, tag, response.code);
            } finally { lock.unlock(); }

        } finally { endMutation(); }
    }

    static SessionCommitResult applyOrdinaryResponse(Context context, SessionRequestTag tag,
                                                      SessionResponse response) {
        beginMutation();
        try {
            return applyOrdinaryResponse(context, tag, response, false);

        } finally { endMutation(); }
    }

    static SessionCommitResult applyOrdinaryResponse(Context context, SessionRequestTag tag,
                                                      SessionResponse response, boolean compatibleResponse) {
        beginMutation();
        try {
            SessionResponse.Candidate candidate;
            try {
                candidate = response.ordinaryCandidate();
            } catch (IllegalArgumentException e) {
                lock.lock();
                try {
                    return new SessionCommitResult(SessionCommitResult.Status.INVALID_SESSION, current, null);
                } finally { lock.unlock(); }
            }

            lock.lock();
            try {
                if (current == null || configuration == null) {
                    return new SessionCommitResult(SessionCommitResult.Status.NOT_CONFIGURED, null, null);
                }
                if (configuration != tag.configuration || !isCurrentLocked(tag.getOwner())) {
                    return staleCommit();
                }
                if ((current.getStatus() == SessionSnapshot.Status.RECOVERY_REQUIRED
                        || current.getStatus() == SessionSnapshot.Status.UNAVAILABLE)
                        && (candidate == null || candidate.removal
                        || (!candidate.completeHeader
                        && !"cookie".equalsIgnoreCase(configuration.values.tokenTransferMethod))
                        || response.code < 200 || response.code >= 300)) {
                    return new SessionCommitResult(SessionCommitResult.Status.RECOVERY_REQUIRED, current, null);
                }
                if (candidate == null || (response.code >= 300 && !candidate.removal)) {
                    if (current.getStatus() == SessionSnapshot.Status.ABSENT
                            && response.code == configuration.values.sessionExpiredStatusCode) {
                        return success(new LegacyEventBatch(configuration.values.eventHandler,
                                EventHandler.EventType.UNAUTHORISED));
                    }
                    return success();
                }
                if (tag.getSnapshot().getAccessTokenRevision() != current.getAccessTokenRevision()
                        || tag.getSnapshot().getRefreshTokenRevision() != current.getRefreshTokenRevision()) {
                    return success();
                }
                if (candidate.removal) {
                    return clearForResponseLocked(context, tag, response.code);
                }
                if (refreshFlight != null && !refreshFlight.isDone()
                        && sameLineage(refreshFlight, current)) {
                    return success();
                }
                if (response.code < 200 || response.code >= 300) {
                    return success();
                }

                SessionSnapshot previous = current;
                boolean cookieAllowed = "cookie".equalsIgnoreCase(configuration.values.tokenTransferMethod)
                        || (previous.getStatus() == SessionSnapshot.Status.AVAILABLE
                        && previous.getInternalTokens() != null && !previous.getInternalTokens().hasCompleteHeaderTokens());
                boolean headerMode = !cookieAllowed;
                if (headerMode && !candidate.hasAccessAndFront) {
                    return new SessionCommitResult(SessionCommitResult.Status.INVALID_SESSION, current, null);
                }
                SessionTokens merged = mergeResponseTokens(previous, candidate, response);
                SessionIdentity identity;
                try {
                    if (headerMode || merged.hasCompleteHeaderTokens()) validateCompleteHeaderTokens(merged);
                    identity = SessionIdentity.parseResponse(merged, compatibleResponse);
                } catch (IllegalArgumentException e) {
                    return new SessionCommitResult(SessionCommitResult.Status.INVALID_SESSION,
                            current, null);
                }

                boolean established = previous.getStatus() != SessionSnapshot.Status.AVAILABLE;
                boolean identityChanged = !established && (!sameStoredIdentity(previous, identity)
                        || (candidate.completeHeader && previous.getTokens() == null));
                if (established) {
                    if (!candidate.completeHeader && headerMode) {
                        return new SessionCommitResult(SessionCommitResult.Status.INVALID_SESSION,
                                current, null);
                    }
                    return replaceForResponseLocked(context, tag, candidate.tokens, compatibleResponse);
                }
                if (identityChanged) {
                    boolean cookieReplacement = cookieAllowed && candidate.tokens.getRefreshToken() == null;
                    if (!candidate.completeHeader && !cookieReplacement) {
                        return new SessionCommitResult(SessionCommitResult.Status.INVALID_SESSION,
                                current, null);
                    }
                    return replaceForResponseLocked(context, tag, candidate.tokens, compatibleResponse);
                }

                return promoteForResponseLocked(context, tag, merged, identity, candidate.rotatesRefresh, compatibleResponse);
            } finally { lock.unlock(); }

        } finally { endMutation(); }
    }

    private static SessionTokens mergeResponseTokens(SessionSnapshot current,
                                                      SessionResponse.Candidate candidate,
                                                      SessionResponse response) {
        SessionTokens old = current.getInternalTokens();
        String access = response.has(Constants.ACCESS_TOKEN_HEADER_KEY) ? candidate.tokens.getAccessToken()
                : old == null ? null : old.getAccessToken();
        String refresh = response.has(Constants.REFRESH_TOKEN_HEADER_KEY) ? candidate.tokens.getRefreshToken()
                : old == null ? null : old.getRefreshToken();
        String anti = response.has(Constants.CSRF_HEADER_KEY) ? candidate.tokens.getAntiCSRF()
                : old == null ? null : old.getAntiCSRF();
        return new SessionTokens(access, refresh, candidate.tokens.getFrontToken(), anti);
    }

    private static SessionCommitResult replaceForResponseLocked(Context context,
                                                                  SessionRequestTag tag,
                                                                  SessionTokens tokens,
                                                                  boolean compatibleResponse) {
        SessionIdentity identity;
        try {
            // A replacement cannot borrow identity or credentials from the prior session's merged tuple.
            identity = SessionIdentity.parseResponse(tokens, compatibleResponse);
        } catch (IllegalArgumentException malformed) {
            return new SessionCommitResult(SessionCommitResult.Status.INVALID_SESSION, current, null);
        }
        SessionSnapshot previous = current;
        long revision = nextRevision(previous.getRevision());
        String adoption = UUID.randomUUID().toString();
        previous.getOwner().invalidate();
        invalidateFlightLocked();
        current = unavailableSnapshot(configuration, revision, adoption);
        SessionStorage.Record record = new SessionStorage.Record(1,
                SessionSnapshot.Status.AVAILABLE, revision, revision, revision,
                configuration.id, adoption, null, tokens, identity.sessionHandle,
                identity.userId, identity.tenantId, identity.accessTokenExpiry, nextMarker(null),
                responseValidation(tokens, compatibleResponse), false);
        try {
            writeStorage(context, record);
            current = snapshotFromRecord(record, configuration, new SessionOwner());
            LegacyEventBatch events;
            if (previous.getStatus() == SessionSnapshot.Status.AVAILABLE) {
                events = offerPublishedLocked(current, EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED);
            } else {
                events = offerPublishedLocked(current, EventHandler.EventType.SESSION_CREATED);
            }
            tag.markAccepted(current);
            return success(events);
        } catch (IOException e) {
            return enterRecoveryLocked(context, previous, revision, e);
        }
    }

    private static SessionCommitResult promoteForResponseLocked(Context context,
                                                                 SessionRequestTag tag,
                                                                 SessionTokens tokens,
                                                                  SessionIdentity identity,
                                                                  boolean rotatesRefresh, boolean compatibleResponse) {
        SessionSnapshot previous = current;
        long revision = nextRevision(previous.getRevision());
        SessionStorage.Record record = new SessionStorage.Record(1,
                SessionSnapshot.Status.AVAILABLE, revision, revision,
                rotatesRefresh ? revision : previous.getRefreshTokenRevision(), configuration.id,
                previous.getAdoptionId(), previous.getAdoptionCorrelation(), tokens,
                identity.sessionHandle, identity.userId, identity.tenantId,
                identity.accessTokenExpiry, nextMarker(null), responseValidation(tokens, compatibleResponse), false);
        try {
            writeStorage(context, record);
            current = snapshotFromRecord(record, configuration, previous.getOwner());
            LegacyEventBatch events;
            if (payloadChanged(previous, current)) {
                events = offerPublishedLocked(current, EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED);
            } else {
                events = offerPublishedLocked(current);
            }
            tag.markAccepted(current);
            return success(events);
        } catch (IOException e) {
            return enterRecoveryLocked(context, previous, revision, e);
        }
    }

    private static SessionCommitResult clearForResponseLocked(Context context,
                                                               SessionRequestTag tag, int code) {
        SessionSnapshot previous = current;
        long revision = nextRevision(previous.getRevision());
        String adoption = UUID.randomUUID().toString();
        previous.getOwner().invalidate();
        invalidateFlightLocked();
        current = unavailableSnapshot(configuration, revision, adoption);
        SessionStorage.Record absent = absentRecord(configuration, revision, adoption);
        try {
            writeStorage(context, absent);
            current = snapshotFromRecord(absent, configuration, new SessionOwner());
            LegacyEventBatch events = offerPublishedLocked(current, code == configuration.values.sessionExpiredStatusCode
                    ? EventHandler.EventType.UNAUTHORISED : EventHandler.EventType.SIGN_OUT);
            tag.markAccepted(current);
            return success(events);
        } catch (IOException e) {
            return enterRecoveryLocked(context, previous, revision, e);
        }
    }

    static String lastAccessTokenUpdate(SessionOwner owner) {
        lock.lock();
        try {
            if (current == null || current.getOwner() != owner
                    || current.getStatus() != SessionSnapshot.Status.AVAILABLE) {
                return null;
            }
            return committedMarker;
        } finally { lock.unlock(); }
    }

    private static void validateStoredAvailable(SessionStorage.Record record,
                                                boolean requireVersionedIdentity) throws IOException {
        if (record.tokens == null || record.tokens.getFrontToken() == null
                || record.lastAccessTokenUpdate == null) {
            throw new IOException("Incomplete stored session");
        }
        if (record.validation == SessionStorage.Record.Validation.LEGACY) {
            SessionIdentity identity = SessionIdentity.parseLegacy(record.tokens);
            if (requireVersionedIdentity && (record.accessTokenExpiry != identity.accessTokenExpiry
                    || !equal(record.sessionHandle, identity.sessionHandle)
                    || !equal(record.userId, identity.userId) || !equal(record.tenantId, identity.tenantId))) {
                throw new IOException("Stored compatible identity is inconsistent");
            }
            return;
        }
        String access = record.tokens.getAccessToken();
        String refresh = record.tokens.getRefreshToken();
        if ((access == null) != (refresh == null)) {
            throw new IOException("Mixed stored header credentials");
        }
        if (access != null) validateCompleteHeaderTokens(record.tokens);
        SessionIdentity identity = SessionIdentity.parse(record.tokens, 0, access != null);
        if (access != null && identity.sessionHandle == null) {
            throw new IOException("Stored session has no stable identity");
        }
        if (requireVersionedIdentity) {
            if (record.accessTokenExpiry != identity.accessTokenExpiry
                    || !equal(record.sessionHandle, identity.sessionHandle)
                    || !equal(record.userId, identity.userId)
                    || !equal(record.tenantId, identity.tenantId)) {
                throw new IOException("Stored session identity is inconsistent");
            }
        }
    }

    private static SessionSnapshot snapshotFromRecord(SessionStorage.Record record,
                                                       Configuration config,
                                                       SessionOwner owner) throws IOException {
        try {
            validateCorrelation(record.adoptionCorrelation);
            UUID.fromString(record.adoptionId);
        } catch (RuntimeException e) {
            throw new IOException("Invalid stored adoption metadata", e);
        }
        if (record.revision < 0 || record.accessTokenRevision < 0
                || record.refreshTokenRevision < 0
                || record.accessTokenRevision > record.revision
                || record.refreshTokenRevision > record.revision) {
            throw new IOException("Invalid stored revisions");
        }
        if (record.revision == Long.MAX_VALUE) {
            throw new IOException("Session revision exhausted");
        }
        if (record.adoptionId == null || record.adoptionId.trim().isEmpty()) {
            throw new IOException("Missing stored adoption ID");
        }
        if (record.status == SessionSnapshot.Status.AVAILABLE) {
            validateStoredAvailable(record, record.schema == 1);
        } else if (hasAnyCredential(record.tokens)) {
            throw new IOException("Absent/recovery state contains credentials");
        }
        if (current != null && current.getStatus() == SessionSnapshot.Status.UNAVAILABLE) {
            current.getOwner().invalidate();
        }
        return new SessionSnapshot(owner, record.revision, record.accessTokenRevision,
                record.refreshTokenRevision, config.id, record.adoptionId,
                record.adoptionCorrelation, record.status, record.sessionHandle,
                record.userId, record.tenantId, record.accessTokenExpiry, record.tokens);
    }

    private static SessionSnapshot absentSnapshot(Configuration config, long revision,
                                                   String adoption) {
        if (current != null && current.getStatus() == SessionSnapshot.Status.UNAVAILABLE) {
            current.getOwner().invalidate();
        }
        return new SessionSnapshot(new SessionOwner(), revision, revision, revision,
                config.id, adoption, null, SessionSnapshot.Status.ABSENT,
                null, null, null, 0, null);
    }

    private static SessionSnapshot unavailableSnapshot(Configuration config, long revision,
                                                        String adoption) {
        if (current != null && current.getStatus() == SessionSnapshot.Status.UNAVAILABLE) {
            current.getOwner().invalidate();
        }
        transientOwner = new SessionOwner();
        return new SessionSnapshot(transientOwner, revision, revision, revision,
                config.id, adoption, null, SessionSnapshot.Status.UNAVAILABLE,
                null, null, null, 0, null);
    }

    private static SessionStorage.Record absentRecord(Configuration config, long revision,
                                                       String adoption) {
        return new SessionStorage.Record(1, SessionSnapshot.Status.ABSENT,
                revision, revision, revision, config.id, adoption, null,
                null, null, null, null, 0, null);
    }

    private static SessionCommitResult enterRecoveryLocked(Context context,
                                                            @Nullable SessionSnapshot previous,
                                                            long revision, Exception cause) {
        if (current != null) {
            current.getOwner().invalidate();
        }
        invalidateFlightLocked();
        String adoption = UUID.randomUUID().toString();
        SessionOwner owner = new SessionOwner();
        current = new SessionSnapshot(owner, revision, revision, revision,
                configuration.id, adoption, null, SessionSnapshot.Status.RECOVERY_REQUIRED,
                null, null, null, 0, null);
        SessionStorage.Record recovery = new SessionStorage.Record(1,
                SessionSnapshot.Status.RECOVERY_REQUIRED, revision, revision, revision,
                configuration.id, adoption, null, null, null, null, null, 0, null);
        try {
            writeStorage(context, recovery);
        } catch (IOException ignored) {
            // Disk may still contain an older record. The explicit result reports this uncertainty.
        }
        offerPublishedLocked(current);
        IOException error = cause instanceof IOException ? (IOException) cause
                : new IOException("Session recovery is required", cause);
        return new SessionCommitResult(SessionCommitResult.Status.PERSISTENCE_FAILED,
                current, error);
    }

    private static void validateCompleteHeaderTokens(SessionTokens tokens) {
        if (tokens == null || isBlank(tokens.getAccessToken())
                || isBlank(tokens.getRefreshToken()) || isBlank(tokens.getFrontToken())) {
            throw new IllegalArgumentException("Complete session tokens are required");
        }
        if (tokens.getAntiCSRF() != null && isBlank(tokens.getAntiCSRF())) {
            throw new IllegalArgumentException("anti-CSRF must be absent or nonblank");
        }
        validateHeaderValue(tokens.getAccessToken());
        validateHeaderValue(tokens.getRefreshToken());
        validateHeaderValue(tokens.getFrontToken());
        if (tokens.getAntiCSRF() != null) validateHeaderValue(tokens.getAntiCSRF());
    }

    private static void validateHeaderValue(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character <= 32 || character >= 127) {
                throw new IllegalArgumentException("Invalid credential header value");
            }
        }
    }

    private static void validateCorrelation(@Nullable String correlation) {
        if (correlation == null) {
            return;
        }
        if (correlation.trim().isEmpty()
                || correlation.getBytes(Charset.forName("UTF-8")).length > 512) {
            throw new IllegalArgumentException("Invalid adoption correlation");
        }
    }

    private static boolean payloadChanged(SessionSnapshot before, SessionSnapshot after) {
        if (before.getAccessTokenPayload() == null || after.getAccessTokenPayload() == null) {
            return before.getAccessTokenPayload() != after.getAccessTokenPayload();
        }
        return !before.getAccessTokenPayload().toString()
                .equals(after.getAccessTokenPayload().toString());
    }

    private static boolean isCurrentLocked(SessionOwner owner) {
        return current != null && owner != null && current.getOwner() == owner && owner.isCurrent();
    }

    private static SessionCommitResult staleCommit() {
        return new SessionCommitResult(SessionCommitResult.Status.STALE_OWNER, current, null);
    }

    private static SessionCommitResult success() {
        return new SessionCommitResult(SessionCommitResult.Status.SUCCESS, current, null);
    }

    private static SessionCommitResult success(LegacyEventBatch events) {
        return new SessionCommitResult(SessionCommitResult.Status.SUCCESS, current, null, events);
    }

    private static SessionOwner newOwnerAfterReplacement() {
        return new SessionOwner();
    }

    private static void invalidateFlightLocked() {
        if (refreshFlight != null) {
            refreshFlight.invalidate();
            refreshFlight = null;
        }
    }

    private static boolean hasAnyCredential(@Nullable SessionTokens tokens) {
        return tokens != null && (tokens.getAccessToken() != null
                || tokens.getRefreshToken() != null || tokens.getFrontToken() != null
                || tokens.getAntiCSRF() != null);
    }

    private static String nextMarker(@Nullable String stored) {
        long previous = Math.max(lastAccessMarker, parseMarker(stored));
        long now = System.currentTimeMillis();
        long candidate = previous == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(now, previous + 1);
        return Long.toString(candidate);
    }

    private static long nextRevision(long previous) {
        return previous == Long.MAX_VALUE ? Long.MAX_VALUE : previous + 1;
    }

    private static long parseMarker(@Nullable String marker) {
        if (marker == null) return 0;
        try {
            return Math.max(0, Long.parseLong(marker));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean equal(@Nullable String first, @Nullable String second) {
        return first == null ? second == null : first.equals(second);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static LegacyEventBatch offerPublishedLocked(SessionSnapshot snapshot,
                                            EventHandler.EventType... events) {
        if (!mutations.isHeldByCurrentThread()) throw new IllegalStateException("Publication outside mutation");
        publication = snapshot;
        return events.length == 0 ? LegacyEventBatch.EMPTY
                : new LegacyEventBatch(configuration.values.eventHandler, events);
    }

    static void notifyAbsentSignOut(SessionRequestTag captured) throws StaleSessionException {
        LegacyEventBatch events = admitAbsentSignOut(captured);
        if (events == null) throw new StaleSessionException();
        dispatch(events);
    }

    static void notifyAbsentSignOutIfCurrent(SessionRequestTag captured) {
        LegacyEventBatch events = admitAbsentSignOut(captured);
        if (events != null) dispatch(events);
    }

    // Null means guard rejection; exceptions from delivery are never admission results.
    @Nullable
    static LegacyEventBatch admitAbsentSignOut(SessionRequestTag captured) {
        lock.lock();
        try {
            if (configuration != captured.configuration || !isCurrentLocked(captured.getOwner())
                    || current.getStatus() != SessionSnapshot.Status.ABSENT) {
                return null;
            }
            return new LegacyEventBatch(configuration.values.eventHandler, EventHandler.EventType.SIGN_OUT);
        } finally { lock.unlock(); }
    }

    static LegacyEventBatch absentUnauthorised(SessionRequestTag captured) {
        lock.lock();
        try {
            if (configuration != captured.configuration || !isCurrentLocked(captured.getOwner())
                    || current.getStatus() != SessionSnapshot.Status.ABSENT) return LegacyEventBatch.EMPTY;
            return new LegacyEventBatch(configuration.values.eventHandler, EventHandler.EventType.UNAUTHORISED);
        } finally { lock.unlock(); }
    }

    static void dispatch(SessionCommitResult result) {
        dispatch(result.events);
    }

    static void dispatch(LegacyEventBatch events) {
        try {
            events.deliver();
        } finally {
            dispatch();
        }
    }

    static void dispatch() {
        lock.lock();
        try {
            if (dispatching) {
                return;
            }
            dispatching = true;
        } finally { lock.unlock(); }
        while (true) {
            SessionSubscription subscription = null;
            lock.lock();
            try {
                Iterator<SessionSubscription> iterator = readySubscriptions.iterator();
                if (iterator.hasNext()) {
                    subscription = iterator.next();
                    iterator.remove();
                } else {
                    dispatching = false;
                    return;
                }
            } finally { lock.unlock(); }
            try {
                subscription.drain();
            } catch (RuntimeException ignored) {
                // A host callback cannot reclaim write authority or block later state transitions.
            }
        }
    }

    static void resetForTests() {
        beginMutation();
        try {
            lock.lock();
            try {
                if (current != null) {
                    current.getOwner().invalidate();
                }
                invalidateFlightLocked();
                current = null;
                transientOwner = null;
                configuration = null;
                subscriptions.clear();
                readySubscriptions.clear();
                dispatching = false;
                storage = new SharedPreferencesSessionStorage();
                unconfiguredWriteFailed = false;
                lastAccessMarker = 0;
                committedMarker = null;
            } finally { lock.unlock(); }

        } finally { endMutation(); }
    }

    static void setStorageForTests(SessionStorage testStorage) {
        beginMutation();
        try {
            lock.lock();
            try {
                storage = testStorage == null ? new SharedPreferencesSessionStorage() : testStorage;
            } finally { lock.unlock(); }

        } finally { endMutation(); }
    }
}
