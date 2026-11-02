package com.supertokens.session;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.*;

public class StrictModeApi21ProcessTest {
    @Test public void detectsDiskRead() throws Exception { runProbe(0, 2); }
    @Test public void detectsDiskWrite() throws Exception {
        checkCrashDiagnosticsAreAllowlisted();
        runProbe(1, 1);
    }
    @Test public void detectsNetwork() throws Exception { runProbe(2, 4); }
    @Test public void prewarmedEmptyStartupSurvives() throws Exception { runProbe(3, 0); }
    @Test public void prewarmedCoherentStartupSurvives() throws Exception { runProbe(4, 0); }

    private static void checkCrashDiagnosticsAreAllowlisted() {
        String detail = "true|android.os.StrictMode$StrictModeViolation|policy=71 violation=2|"
                + "android.os.StrictMode.executeDeathPenalty\nsecret-path";
        assertEquals("CRASH(expected=false,main=true,classMatch=true,policy=71,violation=2,framework=true)",
                safeEvent("CRASH", detail, 1));
        String unknown = "CRASH(expected=false,main=unknown,classMatch=false,policy=unknown,violation=unknown,framework=false)";
        assertEquals(unknown, safeEvent("CRASH", "secret|secret|policy=999999999999 violation=1|secret", 1));
        assertEquals(unknown, safeEvent("CRASH", "secret|secret|policy=2147483648 violation=1|secret", 1));
        assertEquals(unknown, safeEvent("CRASH", "secret|secret|prefix policy=71 violation=1|secret", 1));
        assertEquals(unknown, safeEvent("CRASH", "secret|secret|policy=71 violation=1secret|secret", 1));
        assertEquals(unknown, safeEvent("CRASH", "secret|secret|secret|policy=71 violation=1", 1));
        assertEquals(unknown, safeEvent("CRASH", null, 1));
    }

    private void runProbe(int action, int violation) throws Exception {
        assertEquals("API 21 only", 21, Build.VERSION.SDK_INT);
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String packageName = context.getPackageName();
        int uid = context.getPackageManager().getApplicationInfo(packageName, 0).uid;
        assertEquals("test service must share the instrumentation UID", uid, Process.myUid());
        assertEquals("self-instrumenting test APK required", packageName, target.getPackageName());
        String nonce = UUID.randomUUID().toString();
        File control = new File(context.getCacheDir(), "strictmode-api21-" + nonce + ".bin");
        File prefs = new File(new File(context.getApplicationInfo().dataDir, "shared_prefs"),
                "strictmode-api21-" + nonce + ".xml");
        File backup = new File(prefs.getPath() + ".bak");
        assertFalse("fixture already exists", control.exists() || prefs.exists() || backup.exists());
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch identified = new CountDownLatch(1);
        CountDownLatch dead = new CountDownLatch(1);
        AtomicBoolean deathEntered = new AtomicBoolean();
        CountDownLatch diagnosticReceived = new CountDownLatch(1);
        AtomicReference<String> diagnostic = new AtomicReference<>("NO_RESPONSE");
        AtomicReference<String> observed = new AtomicReference<>("");
        AtomicReference<IBinder> remote = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        AtomicBoolean bound = new AtomicBoolean(true);
        List<String> events = new ArrayList<>();
        int[] childPid = {0};
        Binder callback = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code != StrictModeApi21Service.EVENT) return super.onTransact(code, data, reply, flags);
                data.enforceInterface("strictmode.callback");
                String receivedNonce = data.readString();
                String phase = data.readString();
                String detail = data.readString();
                int pid = Binder.getCallingPid();
                int callingUid = Binder.getCallingUid();
                synchronized (events) {
                    if (!nonce.equals(receivedNonce) || callingUid != uid || pid == Process.myPid() || pid <= 0)
                        error.compareAndSet(null, "unauthenticated callback");
                    if (childPid[0] == 0) {
                        childPid[0] = pid;
                        try {
                            if (!readProcessName(pid).equals(packageName + ":strictmode_probe"))
                                error.compareAndSet(null, "wrong child process");
                        } catch (Exception e) { error.compareAndSet(null, "cannot verify child process: " + e); }
                    } else if (pid != childPid[0]) error.compareAndSet(null, "callback from another process");
                    if (phase.equals("DIAG")) {
                        diagnostic.set(safeStage(detail));
                        diagnosticReceived.countDown();
                    } else {
                        events.add(phase + "|" + detail);
                        String summary = safeEvent(phase, detail, violation);
                        String prior = observed.get();
                        observed.set(prior.length() < 512 ? prior + summary + ";" : prior);
                        if (phase.equals("IDENTITY")) identified.countDown();
                    }
                }
                reply.writeNoException();
                return true;
            }
        };
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                if (!remote.compareAndSet(null, service)) {
                    error.compareAndSet(null, "unexpected rebind");
                    try { command(service, StrictModeApi21Service.STOP, null, null, 0); }
                    catch (RemoteException ignored) { }
                    return;
                }
                try {
                    service.linkToDeath(() -> {
                        deathEntered.set(true);
                        try {
                            if (bound.compareAndSet(true, false)) context.unbindService(this);
                        } finally { dead.countDown(); }
                    }, 0);
                } catch (RemoteException e) { dead.countDown(); }
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if (bound.compareAndSet(true, false)) context.unbindService(this);
            }
        };
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(packageName, StrictModeApi21Service.class.getName()));
        boolean didBind = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!didBind) bound.set(false);
        assertTrue("bind failed", didBind);
        boolean deathConfirmed = false;
        try {
            assertTrue("service connection timeout", connected.await(15, TimeUnit.SECONDS));
            IBinder service = remote.get();
            command(service, StrictModeApi21Service.HELLO, callback, nonce, 0);
            assertTrue("identity timeout", identified.await(10, TimeUnit.SECONDS));
            synchronized (events) {
                assertTrue("callback authentication failed", error.get() == null);
                assertEquals("identity sequence: " + observed.get(), 1, events.size());
                assertTrue("identity event: " + observed.get(), "IDENTITY|".equals(events.get(0)));
            }
            command(service, StrictModeApi21Service.START, null, null, action);
            if (!dead.await(20, TimeUnit.SECONDS)) {
                try {
                    command(service, StrictModeApi21Service.DIAG, null, null, 0);
                    diagnosticReceived.await(1, TimeUnit.SECONDS);
                } catch (RemoteException ignored) { }
                fail("child death timeout; events=" + observed.get() + "; child=" + diagnostic.get()
                        + "; identityOk=" + (error.get() == null) + "; binderAlive=" + service.isBinderAlive() + "; deathEntered=" + deathEntered.get()
                        + "; deathCompleted=" + (dead.getCount() == 0));
            }
            deathConfirmed = !service.isBinderAlive();
            assertTrue("binder still alive", deathConfirmed);
            synchronized (events) {
                assertTrue("callback authentication failed", error.get() == null);
                assertEquals("event sequence: " + observed.get(), 4, events.size());
                assertTrue("identity event: " + observed.get(), "IDENTITY|".equals(events.get(0)));
                assertTrue("ready event: " + observed.get(), "READY|".equals(events.get(1)));
                assertTrue("policy event: " + observed.get(), "POLICY|[StrictMode.ThreadPolicy; mask=71]".equals(events.get(2)));
                String terminal = events.get(3);
                if (violation == 0) assertTrue("success event: " + observed.get(), "SUCCESS|barrier-and-cleanup".equals(terminal));
                else {
                    String prefix = "CRASH|true|android.os.StrictMode$StrictModeViolation|policy=71 violation="
                            + violation + "|";
                    assertTrue("wrong exception: " + observed.get(), terminal.startsWith(prefix));
                    assertTrue("not a framework penaltyDeath: " + observed.get(),
                            terminal.contains("android.os.StrictMode.executeDeathPenalty"));
                }
            }
        } finally {
            IBinder service = remote.get();
            if (service != null && !deathConfirmed) {
                try { command(service, StrictModeApi21Service.STOP, null, null, 0); }
                catch (RemoteException ignored) { }
                deathConfirmed = awaitDeath(dead, 40) && !service.isBinderAlive();
            }
            if (bound.compareAndSet(true, false)) context.unbindService(connection);
            assertNotNull("service Binder unavailable; child death cannot be confirmed; events=" + observed.get(), service);
            if (service != null) {
                assertTrue("owned child death unconfirmed; fixture retained; events=" + observed.get() + "; child=" + diagnostic.get() + "; deathEntered=" + deathEntered.get(), deathConfirmed);
                boolean cleanedControl = deleteIfPresent(control);
                boolean cleanedPrefs = deleteIfPresent(prefs);
                boolean cleanedBackup = deleteIfPresent(backup);
                assertTrue("nonce fixture cleanup failed", cleanedControl && cleanedPrefs && cleanedBackup);
            }
        }
    }

    private static String safeEvent(String phase, String detail, int violation) {
        if ("IDENTITY".equals(phase) || "READY".equals(phase) || "SUCCESS".equals(phase)
                || "FAIL".equals(phase)) return phase;
        if ("POLICY".equals(phase))
            return "POLICY(mask71=" + "[StrictMode.ThreadPolicy; mask=71]".equals(detail) + ")";
        if ("CRASH".equals(phase)) {
            String expected = "true|android.os.StrictMode$StrictModeViolation|policy=71 violation="
                    + violation + "|";
            String[] fields = detail == null ? new String[0] : detail.split("\\|", 4);
            String main = fields.length > 0 && ("true".equals(fields[0]) || "false".equals(fields[0]))
                    ? fields[0] : "unknown";
            boolean classMatch = fields.length > 1
                    && "android.os.StrictMode$StrictModeViolation".equals(fields[1]);
            String policy = "unknown";
            String observedViolation = "unknown";
            if (fields.length > 2) {
                Matcher matcher = Pattern.compile("^policy=([0-9]{1,10}) violation=([0-9]{1,10})(?:$|\\s)")
                        .matcher(fields[2]);
                if (matcher.find()) {
                    try {
                        policy = Integer.toString(Integer.parseInt(matcher.group(1)));
                        observedViolation = Integer.toString(Integer.parseInt(matcher.group(2)));
                    } catch (NumberFormatException ignored) {
                        policy = "unknown";
                        observedViolation = "unknown";
                    }
                }
            }
            return "CRASH(expected=" + (detail != null && detail.startsWith(expected))
                    + ",main=" + main + ",classMatch=" + classMatch + ",policy=" + policy
                    + ",violation=" + observedViolation
                    + ",framework=" + (detail != null && detail.contains("android.os.StrictMode.executeDeathPenalty")) + ")";
        }
        return "OTHER";
    }

    private static String safeStage(String stage) {
        String[] stages = { "CREATED", "PREPARE_ENTER", "PREFS_GET", "PREFS_WARM", "RESET", "CONTROL_PREPARE",
                "SOCKET_PREPARE", "READY_SEND", "READY_SENT", "INSTALL_ENTER",
                "POLICY_SEND", "POLICY_SENT", "OPERATE_ENTER", "CONTROL_ENTER", "CONTROL_RETURNED",
                "BARRIER_ENTER", "FINISH_ENTER", "CRASH_HANDLER", "CRASH_SEND", "FORWARD_HANDLER" };
        for (String candidate : stages) if (candidate.equals(stage)) return candidate;
        return "OTHER";
    }

    private static void command(IBinder service, int code, IBinder callback, String nonce, int action)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken("strictmode.probe");
            if (code == StrictModeApi21Service.HELLO) {
                data.writeStrongBinder(callback);
                data.writeString(nonce);
            } else if (code == StrictModeApi21Service.START) data.writeInt(action);
            if (!service.transact(code, data, null, IBinder.FLAG_ONEWAY))
                throw new RemoteException("command rejected");
        } finally { data.recycle(); }
    }

    private static boolean deleteIfPresent(File path) {
        return (!path.exists() || path.delete()) && !path.exists();
    }

    private static boolean awaitDeath(CountDownLatch dead, int seconds) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        try {
            for (;;) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return dead.getCount() == 0;
                try { return dead.await(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static String readProcessName(int pid) throws Exception {
        try (FileInputStream input = new FileInputStream("/proc/" + pid + "/cmdline");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            int b;
            while ((b = input.read()) > 0) output.write(b);
            return output.toString("UTF-8");
        }
    }
}
