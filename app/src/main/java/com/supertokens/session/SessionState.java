package com.supertokens.session;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class SessionState {
    private static final Object lock = new Object();
    private static final Set<SessionSubscription> subscriptions = new HashSet<>();
    private static final LinkedHashSet<SessionSubscription> readySubscriptions = new LinkedHashSet<>();
    private static final ArrayDeque<LegacyEvent> legacyEvents = new ArrayDeque<>();
    private static final int MAX_LEGACY_EVENTS = 64;
    private static boolean dispatching;
    private static SessionStorage storage = new SharedPreferencesSessionStorage();
    private static Configuration configuration;
    private static SessionSnapshot current;
    private static RefreshCoordinator.Flight refreshFlight;
    private static final Set<RefreshCoordinator.Flight> activeFlights = new HashSet<>();
    private static final int MAX_ACTIVE_FLIGHTS = 8;
    private static long lastAccessMarker;

    private SessionState() {}

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

    private static final class LegacyEvent {
        final Configuration configuration;
        final EventHandler.EventType type;
        final SessionOwner owner;

        LegacyEvent(Configuration configuration, EventHandler.EventType type, SessionOwner owner) {
            this.configuration = configuration;
            this.type = type;
            this.owner = owner;
        }
    }

    static SessionCommitResult configure(Context context, Utils.NormalisedInputType values,
                                         boolean reconfigure) {
        Configuration newConfiguration = new Configuration(values);
        synchronized (lock) {
            if (configuration != null) {
                if (configuration.id.equals(newConfiguration.id)) {
                    if (configuration.callbacksEqual(newConfiguration)) {
                        return new SessionCommitResult(SessionCommitResult.Status.SUCCESS,
                                current, null);
                    }
                    if (!reconfigure) {
                        throw new IllegalStateException("SuperTokens is already initialized with different callbacks; use reconfigure()");
                    }
                    return replaceConfigurationLocked(context, newConfiguration);
                }
                if (!reconfigure) {
                    throw new IllegalStateException("SuperTokens is already initialized with a different configuration; use reconfigure()");
                }
                return replaceConfigurationLocked(context, newConfiguration);
            }

            configuration = newConfiguration;
            lastAccessMarker = 0;
            try {
                SessionStorage.Record record = storage.read(context);
                if (record == null) {
                    SessionStorage.Record absence = absentRecord(newConfiguration, 1,
                            UUID.randomUUID().toString());
                    storage.write(context, absence);
                    current = snapshotFromRecord(absence, newConfiguration, new SessionOwner());
                    return success();
                }
                if (record.schema == 1 && !newConfiguration.id.equals(record.configurationId)) {
                    current = absentSnapshot(newConfiguration, record.revision,
                            UUID.randomUUID().toString());
                    return persistConfigurationClearLocked(context, record.revision);
                }
                if (record.schema == 0) {
                    return importLegacyLocked(context, record, false);
                }
                current = snapshotFromRecord(record, newConfiguration, new SessionOwner());
                lastAccessMarker = parseMarker(record.lastAccessTokenUpdate);
                if (current.getStatus() == SessionSnapshot.Status.RECOVERY_REQUIRED) {
                    return new SessionCommitResult(SessionCommitResult.Status.RECOVERY_REQUIRED,
                            current, null);
                }
                return success();
            } catch (IOException | IllegalArgumentException e) {
                return enterRecoveryLocked(context, null, 1, e);
            }
        }
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
            storage.write(context, absent);
            current = snapshotFromRecord(absent, newConfiguration, newOwnerAfterReplacement());
            if (previous.getStatus() == SessionSnapshot.Status.AVAILABLE) {
                offerPublishedLocked(current, EventHandler.EventType.SIGN_OUT);
            } else {
                offerPublishedLocked(current);
            }
            return success();
        } catch (IOException e) {
            return enterRecoveryLocked(context, previous, revision, e);
        }
    }

    private static SessionCommitResult persistConfigurationClearLocked(Context context,
                                                                       long previousRevision)
            throws IOException {
        long revision = nextRevision(previousRevision);
        String adoption = UUID.randomUUID().toString();
        SessionStorage.Record absent = absentRecord(configuration, revision, adoption);
        try {
            storage.write(context, absent);
            current = snapshotFromRecord(absent, configuration, new SessionOwner());
            return success();
        } catch (IOException e) {
            return enterRecoveryLocked(context, null, revision, e);
        }
    }

    private static SessionCommitResult importLegacyLocked(Context context,
                                                           SessionStorage.Record legacy,
                                                           boolean fromRawWrite) throws IOException {
        long revision = current == null ? 1 : nextRevision(current.getRevision());
        String adoption = UUID.randomUUID().toString();
        if (legacy.status == SessionSnapshot.Status.ABSENT) {
            if (hasAnyCredential(legacy.tokens)) {
                if (fromRawWrite) {
                    current = unavailableSnapshot(configuration, revision, adoption);
                    return new SessionCommitResult(SessionCommitResult.Status.RECOVERY_REQUIRED,
                            current, null);
                }
                throw new IOException("Incomplete legacy credentials");
            }
            SessionStorage.Record absent = absentRecord(configuration, revision, adoption);
            storage.write(context, absent);
            current = snapshotFromRecord(absent, configuration, new SessionOwner());
            return success();
        }

        validateStoredAvailable(legacy, false);
        SessionStorage.Record imported = new SessionStorage.Record(1,
                SessionSnapshot.Status.AVAILABLE, revision, revision, revision,
                configuration.id, adoption, null, legacy.tokens, null, null, null, 0,
                nextMarker(legacy.lastAccessTokenUpdate));
        SessionIdentity identity = SessionIdentity.parse(legacy.tokens, 0, false);
        SessionStorage.Record identified = withIdentity(imported, identity);
        storage.write(context, identified);
        current = snapshotFromRecord(identified, configuration, new SessionOwner());
        return success();
    }

    private static SessionStorage.Record withIdentity(SessionStorage.Record record,
                                                       SessionIdentity identity) {
        return new SessionStorage.Record(record.schema, record.status, record.revision,
                record.accessTokenRevision, record.refreshTokenRevision, record.configurationId,
                record.adoptionId, record.adoptionCorrelation, record.tokens,
                identity.sessionHandle, identity.userId, identity.tenantId,
                identity.accessTokenExpiry, record.lastAccessTokenUpdate);
    }

    static SessionSnapshot snapshot() {
        synchronized (lock) {
            if (current == null) {
                throw new IllegalStateException("SuperTokens.init function not called");
            }
            return current;
        }
    }

    static long currentRefreshRevision() {
        synchronized (lock) {
            return current == null ? -1 : current.getRefreshTokenRevision();
        }
    }

    static Configuration configuration() {
        synchronized (lock) {
            if (configuration == null) {
                throw new IllegalStateException("SuperTokens.init function not called");
            }
            return configuration;
        }
    }

    static SessionRequestTag capture() {
        synchronized (lock) {
            if (current == null || configuration == null) {
                throw new IllegalStateException("SuperTokens.init function not called");
            }
            return new SessionRequestTag(current, configuration);
        }
    }

    static SessionSubscription observe(SessionObserver observer) {
        if (observer == null) {
            throw new NullPointerException("observer");
        }
        synchronized (lock) {
            if (current == null) {
                throw new IllegalStateException("SuperTokens.init function not called");
            }
            SessionSubscription subscription = new SessionSubscription(current, observer);
            subscriptions.add(subscription);
            return subscription;
        }
    }

    static void removeSubscription(SessionSubscription subscription) {
        synchronized (lock) {
            subscriptions.remove(subscription);
            readySubscriptions.remove(subscription);
        }
    }

    static SessionCommitResult install(Context context, SessionOwner expectedOwner,
                                       SessionTokens tokens, @Nullable String correlation) {
        SessionIdentity identity;
        Configuration capturedConfiguration;
        synchronized (lock) {
            if (current == null || configuration == null) {
                return new SessionCommitResult(SessionCommitResult.Status.NOT_CONFIGURED, null, null);
            }
            if (!isCurrentLocked(expectedOwner)) {
                return staleCommit();
            }
            capturedConfiguration = configuration;
        }

        try {
            validateCorrelation(correlation);
            validateCompleteHeaderTokens(tokens);
            identity = SessionIdentity.parse(tokens, System.currentTimeMillis());
            if (identity.sessionHandle == null) {
                throw new IllegalArgumentException("A stable session handle is required");
            }
        } catch (IllegalArgumentException e) {
            synchronized (lock) {
                return new SessionCommitResult(SessionCommitResult.Status.INVALID_SESSION,
                        current, null);
            }
        }

        synchronized (lock) {
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
                storage.write(context, record);
                SessionOwner owner = newOwnerAfterReplacement();
                current = snapshotFromRecord(record, configuration, owner);
                if (previous.getStatus() == SessionSnapshot.Status.ABSENT) {
                    offerPublishedLocked(current, EventHandler.EventType.SESSION_CREATED);
                } else if (payloadChanged(previous, current)) {
                    offerPublishedLocked(current, EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED);
                } else {
                    offerPublishedLocked(current);
                }
                return success();
            } catch (IOException e) {
                return enterRecoveryLocked(context, previous, revision, e);
            }
        }
    }

    static SessionCommitResult clear(Context context, SessionOwner expectedOwner) {
        synchronized (lock) {
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
                storage.write(context, absent);
                current = snapshotFromRecord(absent, configuration, newOwnerAfterReplacement());
                if (previous.getStatus() == SessionSnapshot.Status.AVAILABLE) {
                    offerPublishedLocked(current, EventHandler.EventType.SIGN_OUT);
                } else {
                    offerPublishedLocked(current);
                }
                return success();
            } catch (IOException e) {
                return enterRecoveryLocked(context, previous, revision, e);
            }
        }
    }

    static void legacyStorageChanged(Context context) {
        synchronized (lock) {
            if (configuration == null || current == null) {
                return;
            }
            current.getOwner().invalidate();
            invalidateFlightLocked();
            long revision = nextRevision(current.getRevision());
            try {
                SharedPreferencesSessionStorage.removeSessionMetadata(context);
                SessionStorage.Record legacy = storage.read(context);
                if (legacy == null) {
                    SessionStorage.Record absent = absentRecord(configuration, revision,
                            UUID.randomUUID().toString());
                    storage.write(context, absent);
                    current = snapshotFromRecord(absent, configuration, new SessionOwner());
                    offerPublishedLocked(current);
                    return;
                }
                SessionCommitResult result = importLegacyLocked(context, legacy, true);
                if (result.isSuccess()) {
                    offerPublishedLocked(current);
                }
            } catch (IOException | IllegalArgumentException e) {
                current = unavailableSnapshot(configuration, revision, UUID.randomUUID().toString());
            }
        }
    }

    static SessionResolution resolutionWithoutRefresh(SessionOwner expectedOwner,
                                                       boolean forceRefresh,
                                                       @Nullable String failedAccessToken) {
        synchronized (lock) {
            return resolutionWithoutRefreshLocked(expectedOwner, forceRefresh, failedAccessToken);
        }
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
            return new SessionResolution(SessionResolution.Status.RECOVERY_REQUIRED, current,
                    new IOException("Session storage recovery is required"));
        }
        return null;
    }

    static RefreshCoordinator.Acquisition acquireRefresh(SessionOwner expectedOwner,
                                                          boolean forceRefresh,
                                                          @Nullable String failedAccessToken,
                                                          RefreshCoordinator.Transport transport,
                                                          long entryRefreshRevision) {
        synchronized (lock) {
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
                    return new RefreshCoordinator.Acquisition(refreshFlight, false, null);
                }
            }
            if (activeFlights.size() >= MAX_ACTIVE_FLIGHTS) {
                return new RefreshCoordinator.Acquisition(null, false,
                        new SessionResolution(SessionResolution.Status.REFRESH_FAILED, current,
                                new IOException("Too many unfinished refresh transports")));
            }
            RefreshCoordinator.Flight flight = new RefreshCoordinator.Flight(current,
                    configuration, transport);
            activeFlights.add(flight);
            refreshFlight = flight;
            return new RefreshCoordinator.Acquisition(flight, true, null);
        }
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
        synchronized (lock) {
            if (flight.isDone()) {
                activeFlights.remove(flight);
                return false;
            }
            return true;
        }
    }

    static boolean isCurrent(SessionOwner owner, Configuration expectedConfiguration) {
        synchronized (lock) {
            return configuration == expectedConfiguration && isCurrentLocked(owner);
        }
    }

    static void completeRefresh(Context context, RefreshCoordinator.Flight flight,
                                @Nullable SessionResponse response, @Nullable IOException error) {
        synchronized (lock) {
            activeFlights.remove(flight);
            if (flight.isDone()) return;
            if (current == null || configuration != flight.configuration
                    || !isCurrentLocked(flight.captured.getOwner())
                    || current.getRefreshTokenRevision() != flight.captured.getRefreshTokenRevision()) {
                if (refreshFlight == flight) refreshFlight = null;
                flight.invalidate();
                return;
            }
            if (error != null) {
                failRefreshLocked(flight, error, error instanceof RefreshCoordinator.ConfigurationException
                        ? 0 : RefreshCoordinator.UNCERTAIN_COOLDOWN_MS);
                return;
            }
            if (response == null) {
                failRefreshLocked(flight, new IOException("Refresh returned no response"),
                        RefreshCoordinator.UNCERTAIN_COOLDOWN_MS);
                return;
            }
            if (response.code == configuration.values.sessionExpiredStatusCode) {
                if (current.getAccessTokenRevision() != flight.captured.getAccessTokenRevision()) {
                    failRefreshLocked(flight, new IOException("Obsolete refresh removal"),
                            RefreshCoordinator.UNCERTAIN_COOLDOWN_MS);
                } else {
                    clearForRefreshLocked(context, flight);
                }
                return;
            }
            if (response.code < 200 || response.code >= 300) {
                failRefreshLocked(flight, new IOException("Session refresh failed with response code "
                        + response.code + (response.message.isEmpty() ? "" : ": " + response.message)),
                        response.retryAfterMillis(System.currentTimeMillis()));
                return;
            }

            SessionSnapshot previous = current;
            try {
                SessionResponse.Candidate candidate = response.refreshCandidate(previous);
                boolean headerCredentials = candidate.hasAccessAndFront;
                SessionTokens tokens = candidate.tokens;
                SessionTokens previousTokens = previous.getInternalTokens();
                boolean previouslyHeader = previousTokens != null
                        && previousTokens.getAccessToken() != null;
                if ((previouslyHeader && !headerCredentials)
                        || (tokens.getAccessToken() == null && tokens.getRefreshToken() != null)) {
                    throw new IllegalArgumentException("Refresh returned a partial credential tuple");
                }
                if (headerCredentials) {
                    validateCompleteHeaderTokens(tokens);
                    String oldAccess = previousTokens == null ? null
                            : previous.getInternalTokens().getAccessToken();
                    if (tokens.getAccessToken().equals(oldAccess)) {
                        throw new IllegalArgumentException("Refresh reused the rejected access token");
                    }
                }
                SessionIdentity identity = SessionIdentity.parse(tokens,
                        System.currentTimeMillis(), headerCredentials);
                if (!sameStoredIdentity(previous, identity)) {
                    throw new IllegalArgumentException("Refresh changed session identity");
                }

                long revision = nextRevision(previous.getRevision());
                long refreshRevision = revision;
                SessionStorage.Record record = new SessionStorage.Record(1,
                        SessionSnapshot.Status.AVAILABLE, revision, revision, refreshRevision,
                        configuration.id, previous.getAdoptionId(), previous.getAdoptionCorrelation(),
                        tokens, identity.sessionHandle, identity.userId, identity.tenantId,
                        identity.accessTokenExpiry, nextMarker(null));
                storage.write(context, record);
                current = snapshotFromRecord(record, configuration, previous.getOwner());
                if (refreshFlight == flight) refreshFlight = null;
                if (payloadChanged(previous, current)) {
                    offerPublishedLocked(current, EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED,
                            EventHandler.EventType.REFRESH_SESSION);
                } else {
                    offerPublishedLocked(current, EventHandler.EventType.REFRESH_SESSION);
                }
                flight.succeed(current);
            } catch (IllegalArgumentException e) {
                failRefreshLocked(flight, new IOException("Malformed refresh response", e), 0);
            } catch (IOException e) {
                if (refreshFlight == flight) refreshFlight = null;
                SessionCommitResult recovery = enterRecoveryLocked(context,
                        previous, nextRevision(previous.getRevision()), e);
                flight.fail(current, recovery.getPersistenceError(), 0);
            }
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

    private static void clearForRefreshLocked(Context context, RefreshCoordinator.Flight flight) {
        SessionSnapshot previous = current;
        long revision = nextRevision(previous.getRevision());
        String adoption = UUID.randomUUID().toString();
        previous.getOwner().invalidate();
        if (refreshFlight == flight) refreshFlight = null;
        current = unavailableSnapshot(configuration, revision, adoption);
        SessionStorage.Record absent = absentRecord(configuration, revision, adoption);
        try {
            storage.write(context, absent);
            current = snapshotFromRecord(absent, configuration, new SessionOwner());
            offerPublishedLocked(current, EventHandler.EventType.UNAUTHORISED);
            flight.noSession(current);
        } catch (IOException e) {
            SessionCommitResult recovery = enterRecoveryLocked(context, previous,
                    revision, e);
            flight.fail(current, recovery.getPersistenceError(), 0);
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

    static SessionCommitResult applyOrdinaryResponse(Context context, SessionRequestTag tag,
                                                      SessionResponse response) {
        SessionResponse.Candidate candidate;
        try {
            candidate = response.ordinaryCandidate();
        } catch (IllegalArgumentException e) {
            synchronized (lock) {
                return new SessionCommitResult(SessionCommitResult.Status.INVALID_SESSION, current, null);
            }
        }

        synchronized (lock) {
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
            if (candidate == null || (response.code >= 300 && !candidate.removal)) {
                return success();
            }
            if (tag.getSnapshot().getAccessTokenRevision() != current.getAccessTokenRevision()
                    || tag.getSnapshot().getRefreshTokenRevision() != current.getRefreshTokenRevision()
                    || (refreshFlight != null && !refreshFlight.isDone()
                    && sameLineage(refreshFlight, current))) {
                return success();
            }
            if (candidate.removal) {
                if (tag.getSnapshot().getAccessTokenRevision() != current.getAccessTokenRevision()
                        || tag.getSnapshot().getRefreshTokenRevision() != current.getRefreshTokenRevision()) {
                    return success();
                }
                return clearForResponseLocked(context, tag, response.code);
            }
            if (response.code < 200 || response.code >= 300) {
                return success();
            }

            SessionSnapshot previous = current;
            boolean headerMode = !"cookie".equalsIgnoreCase(configuration.values.tokenTransferMethod)
                    || (previous.getInternalTokens() != null
                    && previous.getInternalTokens().getAccessToken() != null);
            if (headerMode && !candidate.hasAccessAndFront) {
                return new SessionCommitResult(SessionCommitResult.Status.INVALID_SESSION, current, null);
            }
            SessionTokens merged = mergeResponseTokens(previous, candidate, response);
            SessionIdentity identity;
            try {
                boolean strict = candidate.hasAccessAndFront;
                if (strict) validateCompleteHeaderTokens(merged);
                identity = SessionIdentity.parse(merged, System.currentTimeMillis(), strict);
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
                return replaceForResponseLocked(context, tag, candidate.tokens, identity);
            }
            if (identityChanged) {
                if (!candidate.completeHeader) {
                    return new SessionCommitResult(SessionCommitResult.Status.INVALID_SESSION,
                            current, null);
                }
                return replaceForResponseLocked(context, tag, candidate.tokens, identity);
            }

            return promoteForResponseLocked(context, tag, merged, identity, candidate.rotatesRefresh);
        }
    }

    private static SessionTokens mergeResponseTokens(SessionSnapshot current,
                                                      SessionResponse.Candidate candidate,
                                                      SessionResponse response) {
        SessionTokens old = current.getInternalTokens();
        String access = candidate.tokens.getAccessToken() != null ? candidate.tokens.getAccessToken()
                : old == null ? null : old.getAccessToken();
        String refresh = candidate.tokens.getRefreshToken() != null ? candidate.tokens.getRefreshToken()
                : old == null ? null : old.getRefreshToken();
        String anti = response.has(Constants.CSRF_HEADER_KEY) ? candidate.tokens.getAntiCSRF()
                : old == null ? null : old.getAntiCSRF();
        return new SessionTokens(access, refresh, candidate.tokens.getFrontToken(), anti);
    }

    private static SessionCommitResult replaceForResponseLocked(Context context,
                                                                 SessionRequestTag tag,
                                                                 SessionTokens tokens,
                                                                 SessionIdentity identity) {
        SessionSnapshot previous = current;
        long revision = nextRevision(previous.getRevision());
        String adoption = UUID.randomUUID().toString();
        previous.getOwner().invalidate();
        invalidateFlightLocked();
        current = unavailableSnapshot(configuration, revision, adoption);
        SessionStorage.Record record = new SessionStorage.Record(1,
                SessionSnapshot.Status.AVAILABLE, revision, revision, revision,
                configuration.id, adoption, null, tokens, identity.sessionHandle,
                identity.userId, identity.tenantId, identity.accessTokenExpiry, nextMarker(null));
        try {
            storage.write(context, record);
            current = snapshotFromRecord(record, configuration, new SessionOwner());
            if (previous.getStatus() == SessionSnapshot.Status.AVAILABLE) {
                offerPublishedLocked(current, EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED);
            } else {
                offerPublishedLocked(current, EventHandler.EventType.SESSION_CREATED);
            }
            tag.markAccepted(current);
            return success();
        } catch (IOException e) {
            return enterRecoveryLocked(context, previous, revision, e);
        }
    }

    private static SessionCommitResult promoteForResponseLocked(Context context,
                                                                 SessionRequestTag tag,
                                                                 SessionTokens tokens,
                                                                 SessionIdentity identity,
                                                                 boolean rotatesRefresh) {
        SessionSnapshot previous = current;
        long revision = nextRevision(previous.getRevision());
        SessionStorage.Record record = new SessionStorage.Record(1,
                SessionSnapshot.Status.AVAILABLE, revision, revision,
                rotatesRefresh ? revision : previous.getRefreshTokenRevision(), configuration.id,
                previous.getAdoptionId(), previous.getAdoptionCorrelation(), tokens,
                identity.sessionHandle, identity.userId, identity.tenantId,
                identity.accessTokenExpiry, nextMarker(null));
        try {
            storage.write(context, record);
            current = snapshotFromRecord(record, configuration, previous.getOwner());
            if (payloadChanged(previous, current)) {
                offerPublishedLocked(current, EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED);
            } else {
                offerPublishedLocked(current);
            }
            tag.markAccepted(current);
            return success();
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
            storage.write(context, absent);
            current = snapshotFromRecord(absent, configuration, new SessionOwner());
            offerPublishedLocked(current, code == configuration.values.sessionExpiredStatusCode
                    ? EventHandler.EventType.UNAUTHORISED : EventHandler.EventType.SIGN_OUT);
            tag.markAccepted(current);
            return success();
        } catch (IOException e) {
            return enterRecoveryLocked(context, previous, revision, e);
        }
    }

    static String lastAccessTokenUpdate(SessionOwner owner) {
        synchronized (lock) {
            if (current == null || current.getOwner() != owner
                    || current.getStatus() != SessionSnapshot.Status.AVAILABLE) {
                return null;
            }
            return Long.toString(lastAccessMarker);
        }
    }

    private static void validateStoredAvailable(SessionStorage.Record record,
                                                boolean requireVersionedIdentity) throws IOException {
        if (record.tokens == null || record.tokens.getFrontToken() == null
                || record.lastAccessTokenUpdate == null) {
            throw new IOException("Incomplete stored session");
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
        return new SessionSnapshot(owner, record.revision, record.accessTokenRevision,
                record.refreshTokenRevision, config.id, record.adoptionId,
                record.adoptionCorrelation, record.status, record.sessionHandle,
                record.userId, record.tenantId, record.accessTokenExpiry, record.tokens);
    }

    private static SessionSnapshot absentSnapshot(Configuration config, long revision,
                                                   String adoption) {
        return new SessionSnapshot(new SessionOwner(), revision, revision, revision,
                config.id, adoption, null, SessionSnapshot.Status.ABSENT,
                null, null, null, 0, null);
    }

    private static SessionSnapshot unavailableSnapshot(Configuration config, long revision,
                                                        String adoption) {
        return new SessionSnapshot(new SessionOwner(), revision, revision, revision,
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
            storage.write(context, recovery);
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
        lastAccessMarker = previous == Long.MAX_VALUE ? Long.MAX_VALUE
                : Math.max(now, previous + 1);
        return Long.toString(lastAccessMarker);
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

    private static void offerPublishedLocked(SessionSnapshot snapshot,
                                            EventHandler.EventType... events) {
        for (SessionSubscription subscription : subscriptions) {
            subscription.offer(snapshot);
            readySubscriptions.add(subscription);
        }
        for (EventHandler.EventType event : events) {
            if (legacyEvents.size() == MAX_LEGACY_EVENTS) {
                legacyEvents.removeFirst();
            }
            legacyEvents.add(new LegacyEvent(configuration, event, snapshot.getOwner()));
        }
    }

    static void notifyAbsentSignOut(SessionRequestTag captured) throws StaleSessionException {
        synchronized (lock) {
            if (configuration != captured.configuration || !isCurrentLocked(captured.getOwner())
                    || current.getStatus() != SessionSnapshot.Status.ABSENT) {
                throw new StaleSessionException();
            }
            if (legacyEvents.size() == MAX_LEGACY_EVENTS) legacyEvents.removeFirst();
            legacyEvents.add(new LegacyEvent(configuration, EventHandler.EventType.SIGN_OUT,
                    captured.getOwner()));
        }
        dispatch();
    }

    static void dispatch() {
        synchronized (lock) {
            if (dispatching) {
                return;
            }
            dispatching = true;
        }
        while (true) {
            SessionSubscription subscription = null;
            LegacyEvent event = null;
            synchronized (lock) {
                Iterator<SessionSubscription> iterator = readySubscriptions.iterator();
                if (iterator.hasNext()) {
                    subscription = iterator.next();
                    iterator.remove();
                } else if (!legacyEvents.isEmpty()) {
                    event = legacyEvents.removeFirst();
                } else {
                    dispatching = false;
                    return;
                }
            }
            try {
                if (subscription != null) {
                    subscription.drain();
                } else if (isCurrent(event.owner, event.configuration)) {
                    event.configuration.values.eventHandler.handleEvent(event.type);
                }
            } catch (RuntimeException ignored) {
                // A host callback cannot reclaim write authority or block later state transitions.
            }
        }
    }

    static void resetForTests() {
        synchronized (lock) {
            if (current != null) {
                current.getOwner().invalidate();
            }
            invalidateFlightLocked();
            current = null;
            configuration = null;
            subscriptions.clear();
            readySubscriptions.clear();
            legacyEvents.clear();
            dispatching = false;
            storage = new SharedPreferencesSessionStorage();
            lastAccessMarker = 0;
        }
    }

    static void setStorageForTests(SessionStorage testStorage) {
        synchronized (lock) {
            storage = testStorage == null ? new SharedPreferencesSessionStorage() : testStorage;
        }
    }
}
