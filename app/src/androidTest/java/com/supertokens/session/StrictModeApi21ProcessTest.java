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
import org.junit.Test;
import static org.junit.Assert.*;

public class StrictModeApi21ProcessTest {
    @Test public void detectsDiskRead() throws Exception { runProbe(0, 2); }
    @Test public void detectsDiskWrite() throws Exception { runProbe(1, 1); }
    @Test public void detectsNetwork() throws Exception { runProbe(2, 4); }
    @Test public void prewarmedEmptyStartupSurvives() throws Exception { runProbe(3, 0); }
    @Test public void prewarmedCoherentStartupSurvives() throws Exception { runProbe(4, 0); }

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
                    events.add(phase + "|" + detail);
                    if (phase.equals("IDENTITY")) identified.countDown();
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
                assertNull(error.get(), error.get());
                assertEquals("identity sequence: " + events, 1, events.size());
                assertEquals("IDENTITY|", events.get(0));
            }
            command(service, StrictModeApi21Service.START, null, null, action);
            assertTrue("child death timeout", dead.await(20, TimeUnit.SECONDS));
            deathConfirmed = !service.isBinderAlive();
            assertTrue("binder still alive", deathConfirmed);
            synchronized (events) {
                assertNull(error.get(), error.get());
                assertEquals("event sequence: " + events, 4, events.size());
                assertEquals("IDENTITY|", events.get(0));
                assertEquals("READY|", events.get(1));
                assertEquals("POLICY|[StrictMode.ThreadPolicy; mask=71]", events.get(2));
                String terminal = events.get(3);
                if (violation == 0) assertEquals("SUCCESS|barrier-and-cleanup", terminal);
                else {
                    String prefix = "CRASH|true|android.os.StrictMode$StrictModeViolation|policy=71 violation="
                            + violation + "|";
                    assertTrue("wrong exception: " + terminal, terminal.startsWith(prefix));
                    assertTrue("not a framework penaltyDeath: " + terminal,
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
            assertNotNull("service Binder unavailable; child death cannot be confirmed", service);
            if (service != null) {
                assertTrue("owned child death unconfirmed; fixture retained", deathConfirmed);
                boolean cleanedControl = deleteIfPresent(control);
                boolean cleanedPrefs = deleteIfPresent(prefs);
                boolean cleanedBackup = deleteIfPresent(backup);
                assertTrue("nonce fixture cleanup failed", cleanedControl && cleanedPrefs && cleanedBackup);
            }
        }
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
