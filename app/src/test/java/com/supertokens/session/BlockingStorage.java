package com.supertokens.session;

import android.content.Context;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Gate one write without holding a monitor needed by readers. */
final class BlockingStorage implements SessionStorage {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    private final SessionStorage delegate;
    private final java.util.concurrent.atomic.AtomicBoolean block = new java.util.concurrent.atomic.AtomicBoolean(true);

    BlockingStorage(SessionStorage delegate) { this.delegate = delegate; }

    @Override public void editLegacy(Context context, LegacyEdit edit) throws IOException {
        gate();
        delegate.editLegacy(context, edit);
    }

    @Override public Record read(Context context) throws IOException { return delegate.read(context); }

    @Override public void write(Context context, Record record) throws IOException {
        gate();
        delegate.write(context, record);
    }

    private void gate() throws IOException {
        if (block.compareAndSet(true, false)) {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Blocked storage watchdog expired");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
    }
}
