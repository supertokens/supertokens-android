package com.supertokens.session;

import java.net.SocketTimeoutException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One preparation-plus-wire budget, independent of wall clock and callback cooperation. */
final class RefreshDeadline {
    interface Clock { long nanoTime(); }
    private static volatile Clock clock = System::nanoTime;
    private static final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "supertokens-refresh-deadline");
        thread.setDaemon(true);
        return thread;
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
        clock = source == null ? System::nanoTime : source;
    }
}
