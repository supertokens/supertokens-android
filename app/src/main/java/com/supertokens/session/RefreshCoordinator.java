package com.supertokens.session;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.IOException;

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

    static SessionResolution resolve(Context context, SessionOwner expectedOwner,
                                     boolean forceRefresh, @Nullable String failedAccessToken,
                                     Transport transport) {
        return resolve(context, expectedOwner, forceRefresh, failedAccessToken, transport,
                SessionState.currentRefreshRevision());
    }

    static SessionResolution resolve(Context context, SessionOwner expectedOwner,
                                     boolean forceRefresh, @Nullable String failedAccessToken,
                                     Transport transport, long entryRefreshRevision) {
        SessionResolution immediate = SessionState.resolutionWithoutRefresh(expectedOwner,
                forceRefresh, failedAccessToken);
        if (immediate != null) {
            return immediate;
        }

        Acquisition acquisition = SessionState.acquireRefresh(expectedOwner, forceRefresh,
                failedAccessToken, transport, entryRefreshRevision);
        if (acquisition.immediate != null) {
            return acquisition.immediate;
        }
        Flight flight = acquisition.flight;
        if (acquisition.leader && SessionState.startRefresh(flight)) {
            SessionResponse response = null;
            IOException error = null;
            try {
                response = flight.transport.execute(context, flight.configuration, flight.captured);
            } catch (IOException e) {
                error = e;
            } catch (RuntimeException e) {
                error = new IOException("Refresh transport failed", e);
            } catch (Error fatal) {
                SessionState.completeRefresh(context, flight, null,
                        new IOException("Refresh transport aborted", fatal));
                throw fatal;
            }
            SessionState.completeRefresh(context, flight, response, error);
            SessionState.dispatch();
        }
        SessionResolution result = flight.await();
        if (result.isSuccess() && !SessionState.isCurrent(expectedOwner, flight.configuration)) {
            return new SessionResolution(SessionResolution.Status.STALE_OWNER,
                    result.getSnapshot(), new StaleSessionException());
        }
        return result;
    }

    static final class Acquisition {
        final Flight flight;
        final boolean leader;
        final SessionResolution immediate;

        Acquisition(@Nullable Flight flight, boolean leader, @Nullable SessionResolution immediate) {
            this.flight = flight;
            this.leader = leader;
            this.immediate = immediate;
        }
    }

    static final class Flight {
        final SessionSnapshot captured;
        final SessionState.Configuration configuration;
        final Transport transport;
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
            if (done) return;
            result = new SessionResolution(SessionResolution.Status.REFRESH_FAILED, snapshot, error);
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
            if (isLeaderThread() && !done) {
                return new SessionResolution(SessionResolution.Status.REFRESH_FAILED, captured,
                        new IOException("Reentrant refresh on the transport leader"));
            }
            long started = System.nanoTime();
            long remaining = WAIT_TIMEOUT_MS;
            synchronized (this) {
                while (!done && remaining > 0) {
                    try {
                        wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new SessionResolution(SessionResolution.Status.REFRESH_FAILED,
                                captured, new IOException("Interrupted while waiting for refresh", e));
                    }
                    long elapsedNanos = System.nanoTime() - started;
                    remaining = WAIT_TIMEOUT_MS - elapsedNanos / 1000000L;
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
