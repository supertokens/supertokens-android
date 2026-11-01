package com.supertokens.session;

import java.net.SocketTimeoutException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;

/** One preparation-plus-wire budget, independent of wall clock and callback cooperation. */
final class RefreshDeadline {
    interface Clock { long nanoTime(); }
    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override public long nanoTime() { return System.nanoTime(); }
    };
    private static volatile Clock clock = SYSTEM_CLOCK;
    private static final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "supertokens-refresh-deadline");
            thread.setDaemon(true);
            return thread;
        }
    });
    private final long started = clock.nanoTime();

    long remainingMillis() throws SocketTimeoutException {
        long remaining = RefreshCoordinator.NETWORK_TIMEOUT_MS * 1000000L - (clock.nanoTime() - started);
        if (remaining <= 0) throw new SocketTimeoutException("Session refresh deadline exceeded");
        return Math.max(1, remaining / 1000000L);
    }

    ScheduledFuture<?> cancelAfterDeadline(Runnable cancel) throws SocketTimeoutException {
        return timer.schedule(cancel, remainingMillis(), TimeUnit.MILLISECONDS);
    }

    static void finished(ScheduledFuture<?> timeout) {
        if (timeout != null) timeout.cancel(false);
        timer.purge();
    }

    static void setClockForTests(Clock source) {
        clock = source == null ? SYSTEM_CLOCK : source;
    }
}
