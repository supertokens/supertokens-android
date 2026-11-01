package com.supertokens.session;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.os.strictmode.DiskWriteViolation;
import android.util.Base64;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class StrictModeMainThreadTest {
    private Context context;
    private SharedPreferences preferences;

    @Before public void setUp() {
        assumeTrue("penaltyListener requires API 28", Build.VERSION.SDK_INT >= 28);
        final String name = "strictmode-startup-" + UUID.randomUUID();
        context = new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String ignored, int mode) {
                return super.getSharedPreferences(name, mode);
            }
        };
        SuperTokens.resetForTests();
        preferences = context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE);
        preferences.getAll();
    }

    @After public void tearDown() {
        SuperTokens.resetForTests();
        if (preferences != null) assertTrue(preferences.edit().clear().commit());
    }

    @Test public void prewarmedEmptyStartupDoesNotWriteOnMainThread() throws Exception {
        verifyStartup(false, null);
    }

    @Test public void prewarmedCoherentStartupDoesNotWriteMetadataOnMainThread() throws Exception {
        new SuperTokens.Builder(context, "https://old-startup.invalid").build();
        SessionCommitResult installed = SuperTokens.installSession(context,
                SuperTokens.getSessionSnapshot(context).getOwner(), tokens());
        assertTrue(installed.isSuccess());
        String adoption = installed.getSnapshot().getAdoptionId();
        SuperTokens.resetForTests();
        verifyStartup(true, adoption);
    }

    private void verifyStartup(boolean present, String adoption) throws Exception {
        Map<String, ?> before = preferences.getAll();
        List<Throwable> violations = monitorMainThread(() -> {
            assertEquals(present, SuperTokens.doesSessionExist(context));
            assertEquals(present, FrontToken.getToken(context) != null);
            new SuperTokens.Builder(context, "https://new-startup.invalid").build();
            SessionSnapshot snapshot = SuperTokens.getSessionSnapshot(context);
            assertEquals(present ? SessionSnapshot.Status.AVAILABLE : SessionSnapshot.Status.ABSENT,
                    snapshot.getStatus());
            assertEquals(present, SuperTokens.doesSessionExist(context));
            if (present) {
                assertEquals(adoption, snapshot.getAdoptionId());
                assertEquals("strict-user", SuperTokens.getUserId(context));
                assertNotNull(SuperTokens.getAccessToken(context));
                assertNotNull(SuperTokens.getAccessTokenPayloadSecurely(context));
            } else {
                assertNull(SuperTokens.getAccessToken(context));
            }
        });
        assertTrue("Unexpected disk-write/network violations: " + violations, violations.isEmpty());
        assertEquals(before, preferences.getAll());
    }

    @Test public void monitorDetectsActualMainThreadDiskWrite() throws Exception {
        File file = File.createTempFile("strictmode-control-", ".bin", context.getCacheDir());
        try {
            List<Throwable> violations = monitorMainThread(() -> {
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write(42);
                    output.getFD().sync();
                }
            });
            assertEquals(1L, file.length());
            boolean detected = false;
            for (Throwable violation : violations) {
                if (violation instanceof DiskWriteViolation) detected = true;
            }
            assertTrue("Monitor missed actual main-thread disk write: " + violations, detected);
        } finally {
            assertTrue(file.delete());
        }
    }

    private interface MainThreadAction {
        void run() throws Exception;
    }

    private List<Throwable> monitorMainThread(MainThreadAction action) throws Exception {
        assertNotSame("Must wait off the main thread", Looper.getMainLooper(), Looper.myLooper());
        Handler main = new Handler(Looper.getMainLooper());
        List<Throwable> violations = Collections.synchronizedList(new ArrayList<Throwable>());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<StrictMode.ThreadPolicy> previous = new AtomicReference<>();
        CountDownLatch restored = new CountDownLatch(1);
        Runnable restore = () -> {
            try {
                StrictMode.ThreadPolicy policy = previous.getAndSet(null);
                if (policy != null) StrictMode.setThreadPolicy(policy);
            } finally {
                restored.countDown();
            }
        };
        Runnable operation = () -> {
            previous.set(StrictMode.getThreadPolicy());
            try {
                StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectDiskWrites().detectNetwork()
                        .penaltyListener(Runnable::run, violations::add).build());
                action.run();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                // StrictMode posts reporting at the front of this looper. A normal post runs after it;
                // its direct executor has completed listener delivery before restoration signals us.
                if (!main.post(restore)) {
                    failure.compareAndSet(null, new AssertionError("Main looper rejected reporting barrier"));
                    restore.run();
                }
            }
        };
        assertTrue("Main looper rejected monitored action", main.post(operation));
        try {
            assertTrue("Timed out waiting for StrictMode reporting/restoration", restored.await(10, TimeUnit.SECONDS));
        } finally {
            // Cancel work that never started; cleanup for an already-running action stays behind reporting.
            main.removeCallbacks(operation);
            if (restored.getCount() != 0) {
                boolean interrupted = Thread.interrupted();
                try {
                    assertTrue("Main looper rejected policy cleanup", main.post(restore));
                    assertTrue("Main thread stuck; policy cleanup could not complete", restored.await(5, TimeUnit.SECONDS));
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        }
        if (failure.get() != null) throw new AssertionError("Monitored main-thread action failed", failure.get());
        return violations;
    }

    private SessionTokens tokens() throws Exception {
        JSONObject payload = new JSONObject().put("sessionHandle", "strict-handle").put("sub", "strict-user")
                .put("tId", "public").put("exp", 4102444800L);
        String access = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"version\":\"4\"}", true)
                + "." + encode(payload.toString(), true) + ".c2ln";
        String front = encode(new JSONObject().put("uid", "strict-user").put("ate", 4102444800000L)
                .put("up", payload).toString(), false);
        return new SessionTokens(access, "strict-refresh", front, "strict-csrf");
    }

    private String encode(String value, boolean url) {
        return Base64.encodeToString(value.getBytes(Charset.forName("UTF-8")),
                url ? Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING : Base64.NO_WRAP);
    }
}
