package com.supertokens.session;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

final class RefreshCoordinator {
    static final long WAIT_TIMEOUT_MS = 30000;
    static final long NETWORK_TIMEOUT_MS = 10000;
    static final long UNCERTAIN_COOLDOWN_MS = 5000;

    private RefreshCoordinator() {}

    interface Transport {
        SessionResponse execute(Context context, SessionState.Configuration configuration,
                                SessionSnapshot captured) throws IOException;
    }

    static final class ConfigurationException extends IOException {
        ConfigurationException(String message) {
            super(message);
        }
    }

    static IOException httpFailure(int code, String message) {
        return new IOException("Session refresh failed with response code " + code
                + (message.isEmpty() ? "" : ": " + message));
    }

    static SessionResolution resolve(Context context, SessionOwner expectedOwner,
                                     boolean forceRefresh, @Nullable String failedAccessToken,
                                     Transport transport) {
        return resolve(context, expectedOwner, forceRefresh, failedAccessToken, transport,
                SessionState.currentRefreshRevision());
    }

    static SessionResolution resolve(Context context, SessionOwner expectedOwner,
                                     boolean forceRefresh, @Nullable String failedAccessToken,
                                     Transport transport, long entryRefreshRevision) {
        return resolve(context, expectedOwner, forceRefresh, failedAccessToken, transport, entryRefreshRevision, false);
    }

    static SessionResolution resolve(Context context, SessionOwner expectedOwner,
                                     boolean forceRefresh, @Nullable String failedAccessToken,
                                     Transport transport, boolean compatibleResponse) {
        return resolve(context, expectedOwner, forceRefresh, failedAccessToken, transport,
                SessionState.currentRefreshRevision(), compatibleResponse);
    }

    static SessionResolution resolve(Context context, SessionOwner expectedOwner,
                                     boolean forceRefresh, @Nullable String failedAccessToken,
                                     Transport transport, long entryRefreshRevision, boolean compatibleResponse) {
        return resolve(context, expectedOwner, forceRefresh, failedAccessToken, transport,
                entryRefreshRevision, compatibleResponse, new WaitBudget());
    }

    static SessionResolution resolve(Context context, SessionOwner expectedOwner, boolean forceRefresh,
                                     @Nullable String failedAccessToken, Transport transport,
                                     long entryRefreshRevision, boolean compatibleResponse, WaitBudget budget) {
        SessionResolution immediate = SessionState.resolutionWithoutRefresh(expectedOwner,
                forceRefresh, failedAccessToken);
        if (immediate != null) {
            return validateForCaller(immediate, compatibleResponse);
        }

        Acquisition acquisition;
        while (true) {
            acquisition = SessionState.acquireRefresh(expectedOwner, forceRefresh,
                    failedAccessToken, transport, entryRefreshRevision, compatibleResponse);
            if (acquisition.pending == null) break;
            try { acquisition.pending.await(budget); }
            catch (IOException e) {
                return new SessionResolution(SessionResolution.Status.REFRESH_FAILED, SessionState.snapshot(), e);
            }
        }
        if (acquisition.immediate != null) {
            return validateForCaller(acquisition.immediate, compatibleResponse);
        }
        Flight flight = acquisition.flight;
        if (!acquisition.leader) SessionState.notifyFollowerAcquiredForTests();
        if (acquisition.leader && SessionState.startRefresh(flight, budget)) {
            SessionResponse response = null;
            IOException error = null;
            LegacyEventBatch.FailureScope failures = new LegacyEventBatch.FailureScope();
            try {
                response = flight.transport.execute(context, flight.configuration, flight.captured);
            } catch (IOException e) {
                error = e;
            } catch (RuntimeException e) {
                if (failures.contains(e)) {
                    SessionState.completeRefresh(context, flight, null,
                            new IOException("Refresh callback failed", e));
                    return handlerFailure(flight, e, compatibleResponse);
                }
                error = new IOException("Refresh transport failed", e);
            } catch (Error fatal) {
                SessionState.completeRefresh(context, flight, null,
                        new IOException("Refresh transport aborted", fatal));
                throw fatal;
            } finally {
                failures.close();
            }
            LegacyEventBatch events = SessionState.completeRefresh(context, flight, response, error);
            // The flight is terminal before callbacks: followers and reentrant getters cannot wait on the handler.
            try {
                SessionState.dispatch(events);
            } catch (RuntimeException failure) {
                return handlerFailure(flight, failure, compatibleResponse);
            }
        }
        SessionResolution result = flight.await(budget);
        if (result.isSuccess() && !SessionState.isCurrent(expectedOwner, flight.configuration)) {
            return new SessionResolution(SessionResolution.Status.STALE_OWNER,
                    result.getSnapshot(), new StaleSessionException());
        }
        return validateForCaller(result, compatibleResponse);
    }

    private static SessionResolution handlerFailure(Flight flight, RuntimeException failure,
                                                    boolean legacyCaller) {
        if (!legacyCaller) throw failure;
        // Only the producing legacy call fails. The completed flight remains available to followers.
        return new SessionResolution(SessionResolution.Status.REFRESH_FAILED,
                flight.await().getSnapshot(), new IOException(failure), true);
    }

    private static SessionResolution validateForCaller(SessionResolution result, boolean compatibleResponse) {
        if (!compatibleResponse && result.isSuccess()) {
            try {
                // Legacy admission also feeds fast paths; validate without mutating or refreshing the shared session.
                SessionIdentity.parseResponse(result.getSnapshot().getInternalTokens(), false);
            } catch (IllegalArgumentException malformed) {
                return new SessionResolution(SessionResolution.Status.REFRESH_FAILED, result.getSnapshot(),
                        new IOException("Session does not satisfy explicit resolution", malformed));
            }
        }
        return result;
    }

    interface NanoClock {
        long now();
    }

    static final class WaitBudget {
        private static final NanoClock SYSTEM_CLOCK = new NanoClock() {
            @Override public long now() { return System.nanoTime(); }
        };
        private final NanoClock clock;
        private final long started;
        private final long duration;

        WaitBudget() {
            this(SYSTEM_CLOCK, TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS));
        }

        WaitBudget(NanoClock clock, long durationNanos) {
            if (durationNanos <= 0) throw new IllegalArgumentException("Wait budget must be positive");
            this.clock = clock;
            this.started = clock.now();
            this.duration = durationNanos;
        }

        long remainingNanos() {
            // Subtraction handles nanoTime wrap; adding an absolute deadline can overflow.
            long elapsed = clock.now() - started;
            return elapsed < 0 || elapsed >= duration ? 0 : duration - elapsed;
        }
    }

    static final class Acquisition {
        final Flight flight;
        final SessionState.PendingMutation pending;
        final boolean leader;
        final SessionResolution immediate;

        Acquisition(@Nullable Flight flight, boolean leader, @Nullable SessionResolution immediate) {
            this.pending = null;
            this.flight = flight;
            this.leader = leader;
            this.immediate = immediate;
        }

        Acquisition(SessionState.PendingMutation pending) {
            this.pending = pending;
            this.flight = null;
            this.leader = false;
            this.immediate = null;
        }

    }

    static final class Flight {
        final SessionSnapshot captured;
        final SessionState.Configuration configuration;
        final Transport transport;
        // Admission is shared; strict callers validate their own result. A strict follower of a
        // compatible flight shares its failure but may start a new flight immediately afterwards.
        // Guarded by SessionState's lock.
        boolean compatibleResponses;
        private volatile boolean done;
        private volatile SessionResolution result;
        private long retryNotBeforeNanos;
        private final Thread leaderThread = Thread.currentThread();

        Flight(SessionSnapshot captured, SessionState.Configuration configuration,
               Transport transport) {
            this.captured = captured;
            this.configuration = configuration;
            this.transport = transport;
        }

        boolean isDone() {
            return done;
        }

        boolean isInCooldown(long nowNanos) {
            return done && result != null
                    && result.getStatus() == SessionResolution.Status.REFRESH_FAILED
                    && nowNanos < retryNotBeforeNanos;
        }

        boolean isLeaderThread() {
            return leaderThread == Thread.currentThread();
        }

        synchronized void succeed(SessionSnapshot snapshot) {
            if (done) return;
            result = new SessionResolution(SessionResolution.Status.SUCCESS, snapshot, null);
            done = true;
            notifyAll();
        }

        synchronized void fail(SessionSnapshot snapshot, IOException error, long retryNotBeforeNanos) {
            fail(snapshot, error, retryNotBeforeNanos, false);
        }

        synchronized void fail(SessionSnapshot snapshot, IOException error, long retryNotBeforeNanos,
                               boolean httpFailure) {
            if (done) return;
            result = new SessionResolution(SessionResolution.Status.REFRESH_FAILED, snapshot, error,
                    false, false, httpFailure);
            this.retryNotBeforeNanos = retryNotBeforeNanos;
            done = true;
            notifyAll();
        }

        synchronized void noSession(SessionSnapshot snapshot) {
            if (done) return;
            result = new SessionResolution(SessionResolution.Status.NO_SESSION, snapshot, null);
            done = true;
            notifyAll();
        }

        synchronized void invalidate() {
            if (done) return;
            result = new SessionResolution(SessionResolution.Status.STALE_OWNER, captured,
                    new StaleSessionException());
            done = true;
            notifyAll();
        }

        SessionResolution await() {
            return await(new WaitBudget());
        }

        SessionResolution await(WaitBudget budget) {
            if (isLeaderThread() && !done) {
                return new SessionResolution(SessionResolution.Status.REFRESH_FAILED, captured,
                        new IOException("Reentrant refresh on the transport leader"));
            }
            synchronized (this) {
                long remaining = budget.remainingNanos();
                while (!done && remaining > 0) {
                    try {
                        TimeUnit.NANOSECONDS.timedWait(this, remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new SessionResolution(SessionResolution.Status.REFRESH_FAILED,
                                captured, new IOException("Interrupted while waiting for refresh", e));
                    }
                    remaining = budget.remainingNanos();
                }
                if (done) {
                    return result;
                }
            }
            return new SessionResolution(SessionResolution.Status.REFRESH_FAILED, captured,
                    new IOException("Timed out waiting for shared session refresh"));
        }
    }
}
