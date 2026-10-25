package com.supertokens.session;

import java.io.Closeable;

/** Latest-revision observation with an explicit initial-projection/activation handshake. */
public final class SessionSubscription implements Closeable {
    private final Object lock = new Object();
    private final SessionSnapshot initialSnapshot;
    private final SessionObserver observer;
    private SessionSnapshot pending;
    private long lastOfferedRevision;
    private boolean activated;
    private boolean closed;
    private boolean draining;
    private boolean inCallback;
    private Thread callbackThread;

    SessionSubscription(SessionSnapshot initialSnapshot, SessionObserver observer) {
        this.initialSnapshot = initialSnapshot;
        this.observer = observer;
        this.lastOfferedRevision = initialSnapshot.getRevision();
    }

    public SessionSnapshot getInitialSnapshot() {
        return initialSnapshot;
    }

    public void activate() {
        synchronized (lock) {
            if (closed || activated) {
                return;
            }
            activated = true;
        }
        drain();
    }

    void offer(SessionSnapshot snapshot) {
        synchronized (lock) {
            if (!closed && snapshot.getRevision() > lastOfferedRevision) {
                pending = snapshot;
                lastOfferedRevision = snapshot.getRevision();
            }
        }
    }

    void drain() {
        synchronized (lock) {
            if (!activated || closed || draining || pending == null) {
                return;
            }
            draining = true;
        }

        while (true) {
            SessionSnapshot next;
            synchronized (lock) {
                next = pending;
                pending = null;
                if (next == null || closed) {
                    draining = false;
                    lock.notifyAll();
                    return;
                }
                inCallback = true;
                callbackThread = Thread.currentThread();
            }

            try {
                observer.onSessionChanged(next);
            } catch (RuntimeException ignored) {
                // One observer cannot strand its own queue or another owner's progress.
            } finally {
                synchronized (lock) {
                    inCallback = false;
                    callbackThread = null;
                    lock.notifyAll();
                }
            }
        }
    }

    @Override
    public void close() {
        SessionState.removeSubscription(this);
        boolean interrupted = false;
        synchronized (lock) {
            closed = true;
            activated = false;
            pending = null;
            while (inCallback && callbackThread != Thread.currentThread()) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
