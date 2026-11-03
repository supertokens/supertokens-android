package com.supertokens.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.Charset;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FrontTokenLockInstrumentedTest {
    private Context context;
    private SharedPreferences prefs;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences("supertokens-android-shared-preferences", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        SuperTokens.resetForTests();
        new SuperTokens.Builder(context, "http://localhost").build();
        assertTrue(SuperTokens.installSession(context, 0, tokens("old")));
    }

    @After public void tearDown() {
        FrontToken.setBeforeSnapshotReadForTests(null);
        SuperTokens.resetForTests();
        prefs.edit().clear().commit();
    }

    private SuperTokens.SessionTokens tokens(String suffix) {
        String payload = "{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{}}";
        String front = Base64.encodeToString(payload.getBytes(Charset.forName("UTF-8")), Base64.NO_WRAP);
        return new SuperTokens.SessionTokens("access-" + suffix, "refresh-" + suffix, front, "csrf-" + suffix);
    }

    @Test public void mainThreadPreferenceCallbackCanReadWhileAnotherReaderIsPaused() throws Exception {
        CountDownLatch readerPaused = new CountDownLatch(1), releaseReader = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1), callbackFinished = new CountDownLatch(1);
        AtomicReference<Thread> readerThread = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        AtomicBoolean abortReader = new AtomicBoolean();
        AtomicBoolean firstCallback = new AtomicBoolean();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> {
            if (!firstCallback.compareAndSet(false, true)) return;
            callbackEntered.countDown();
            try { assertEquals("user", SuperTokens.getUserId(context)); }
            catch (Throwable t) { callbackFailure.set(t); }
            finally { callbackFinished.countDown(); }
        };
        FrontToken.setBeforeSnapshotReadForTests(() -> {
            if (Thread.currentThread() != readerThread.get()) return;
            readerPaused.countDown();
            try {
                if (!releaseReader.await(5, TimeUnit.SECONDS)) throw new AssertionError("reader not released");
            } catch (InterruptedException e) { throw new AssertionError(e); }
            if (abortReader.get()) throw new IllegalStateException("release blocked reader safely");
        });
        Future<?> reader = workers.submit(() -> {
            readerThread.set(Thread.currentThread());
            assertEquals("user", SuperTokens.getUserId(context));
        });
        Future<?> writer = null;
        boolean completed = false;
        try {
            assertTrue(readerPaused.await(5, TimeUnit.SECONDS));
            prefs.registerOnSharedPreferenceChangeListener(listener);
            writer = workers.submit(() -> InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    assertTrue(SuperTokens.installSession(context, 1, tokens("new")))));
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
            completed = callbackFinished.await(2, TimeUnit.SECONDS);
        } finally {
            abortReader.set(!completed);
            releaseReader.countDown();
            try { reader.get(5, TimeUnit.SECONDS); }
            catch (ExecutionException e) { if (completed) throw e; }
            if (writer != null) writer.get(5, TimeUnit.SECONDS);
            prefs.unregisterOnSharedPreferenceChangeListener(listener);
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
        if (callbackFailure.get() != null) throw new AssertionError(callbackFailure.get());
        assertTrue("preference callback blocked behind a paused front-token reader", completed);
        assertEquals("access-new", SuperTokens.getSessionSnapshot(context).accessToken);
    }
}
