package com.supertokens.session;

import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.StrictMode;
import android.util.Base64;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Map;
import org.json.JSONObject;

/** Private test process; every invocation gets a fresh process and fixture. */
public final class StrictModeApi21Service extends Service {
    static final int START = IBinder.FIRST_CALL_TRANSACTION;
    static final int HELLO = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int STOP = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int EVENT = IBinder.FIRST_CALL_TRANSACTION;
    private final Handler main = new Handler(Looper.getMainLooper());
    private IBinder callback;
    private String nonce;
    private int action;
    private SharedPreferences preferences;
    private Context fixture;
    private Map<String, ?> before;
    private String adoption;
    private File file;
    private ServerSocket server;
    private Socket socket;
    private java.net.InetSocketAddress endpoint;
    private StrictMode.ThreadPolicy policy;

    private boolean started;
    private final Object watchdogLock = new Object();
    private boolean crashing;
    private final Binder binder = new Binder() {
        @Override protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code != START && code != HELLO && code != STOP) return super.onTransact(code, data, reply, flags);
            data.enforceInterface("strictmode.probe");
            if (code == STOP) {
                Process.killProcess(Process.myPid());
            } else if (code == HELLO) {
                if (callback != null) throw new IllegalStateException("duplicate hello");
                callback = data.readStrongBinder();
                nonce = data.readString();
                new Thread(() -> {
                    try { send("IDENTITY", ""); } catch (RemoteException e) { fail(e); }
                }, "strictmode-identity").start();
            } else {
                if (callback == null || started) throw new IllegalStateException("invalid start");
                started = true;
                action = data.readInt();
                new Thread(() -> prepare(), "strictmode-fixture").start();
            }
            return true;
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(30000); } catch (InterruptedException ignored) { }
            synchronized (watchdogLock) {
                if (!crashing) Process.killProcess(Process.myPid());
            }
            try { Thread.sleep(25000); } catch (InterruptedException ignored) { }
            Process.killProcess(Process.myPid());
        }, "strictmode-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    private void send(String phase, String detail) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("strictmode.callback");
            data.writeString(nonce);
            data.writeString(phase);
            data.writeString(detail);
            callback.transact(EVENT, data, reply, 0);
            reply.readException();
        } finally { reply.recycle(); data.recycle(); }
    }

    private void prepare() {
        try {
            String name = "strictmode-api21-" + nonce;
            fixture = new ContextWrapper(this) {
                @Override public Context getApplicationContext() { return this; }
                @Override public SharedPreferences getSharedPreferences(String ignored, int mode) {
                    return super.getSharedPreferences(name, mode);
                }
            };
            preferences = fixture.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, MODE_PRIVATE);
            preferences.getAll();
            SuperTokens.resetForTests();
            if (action == 4) {
                new SuperTokens.Builder(fixture, "https://old-startup.invalid").build();
                SessionCommitResult installed = SuperTokens.installSession(fixture,
                        SuperTokens.getSessionSnapshot(fixture).getOwner(), tokens());
                check(installed.isSuccess(), "install failed");
                adoption = installed.getSnapshot().getAdoptionId();
                SuperTokens.resetForTests();
            }
            before = preferences.getAll();
            file = new File(getCacheDir(), "strictmode-api21-" + nonce + ".bin");
            check(file.createNewFile(), "control file already exists");
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(42); }
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            socket = new Socket();
            endpoint = new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.getLocalPort());
            send("READY", "");
            check(main.post(this::install), "main looper rejected install");
        } catch (Throwable t) { fail(t); }
    }

    private void install() {
        final Thread thread = Thread.currentThread();
        final Thread.UncaughtExceptionHandler original = thread.getUncaughtExceptionHandler();
        thread.setUncaughtExceptionHandler((t, error) -> {
            synchronized (watchdogLock) { crashing = true; }
            try {
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX);
                StringBuilder stack = new StringBuilder();
                for (StackTraceElement frame : error.getStackTrace()) stack.append(frame.toString()).append('\n');
                send("CRASH", (t == thread && Looper.myLooper() == Looper.getMainLooper()) + "|"
                        + error.getClass().getName() + "|" + error.getMessage() + "|" + stack);
            } catch (Throwable ignored) {
            } finally { original.uncaughtException(t, error); }
        });
        try {
            policy = new StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites()
                    .detectNetwork().penaltyDeath().build();
            StrictMode.setThreadPolicy(policy);
            requirePolicy();
            send("POLICY", StrictMode.getThreadPolicy().toString());
            check(main.post(this::operate), "main looper rejected operation");
        } catch (RemoteException e) { throw new RuntimeException(e); }
    }

    private void requirePolicy() {
        check(StrictMode.getThreadPolicy().toString().equals(policy.toString()), "policy changed");
    }

    private void operate() {
        requirePolicy();
        try {
            if (action == 0) { try (FileInputStream in = new FileInputStream(file)) { in.read(); } }
            else if (action == 1) { try (FileOutputStream out = new FileOutputStream(file)) { out.write(1); } }
            else if (action == 2) { socket.connect(endpoint); }
            else { startup(action == 4); }
            check(main.post(() -> {
                requirePolicy();
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX);
                new Thread(() -> finish(), "strictmode-result").start();
            }), "main looper rejected barrier");
        } catch (IOException | org.json.JSONException e) { throw new RuntimeException(e); }
    }

    private void startup(boolean present) throws IOException, org.json.JSONException {
        check(SuperTokens.doesSessionExist(fixture) == present, "initial session");
        check((FrontToken.getToken(fixture) != null) == present, "front token");
        new SuperTokens.Builder(fixture, "https://new-startup.invalid").build();
        SessionSnapshot snapshot = SuperTokens.getSessionSnapshot(fixture);
        check(snapshot.getStatus() == (present ? SessionSnapshot.Status.AVAILABLE : SessionSnapshot.Status.ABSENT), "status");
        check(SuperTokens.doesSessionExist(fixture) == present, "session after build");
        if (present) {
            check(adoption.equals(snapshot.getAdoptionId()), "adoption");
            check("strict-user".equals(SuperTokens.getUserId(fixture)), "user");
            check(SuperTokens.getAccessToken(fixture) != null, "access token");
            check(SuperTokens.getAccessTokenPayloadSecurely(fixture) != null, "payload");
        } else check(SuperTokens.getAccessToken(fixture) == null, "empty access token");
    }

    private void finish() {
        try {
            check(before.equals(preferences.getAll()), "preferences changed");
            socket.close();
            server.close();
            check(file.delete() && !file.exists(), "control file cleanup");
            check(preferences.edit().clear().commit(), "fixture cleanup");
            File prefs = new File(new File(getApplicationInfo().dataDir, "shared_prefs"),
                    "strictmode-api21-" + nonce + ".xml");
            deleteIfPresent(prefs);
            deleteIfPresent(new File(prefs.getPath() + ".bak"));
            send("SUCCESS", "barrier-and-cleanup");
        } catch (Throwable t) { fail(t); return; }
        Process.killProcess(Process.myPid());
    }

    private static void deleteIfPresent(File path) {
        check(!path.exists() || path.delete(), "cannot delete fixture " + path);
        check(!path.exists(), "fixture still exists " + path);
    }

    private void fail(Throwable t) {
        try { send("FAIL", t.toString()); } catch (Throwable ignored) { }
        Process.killProcess(Process.myPid());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String encode(String value, boolean url) {
        return Base64.encodeToString(value.getBytes(Charset.forName("UTF-8")),
                url ? Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING : Base64.NO_WRAP);
    }

    private static SessionTokens tokens() throws Exception {
        JSONObject payload = new JSONObject().put("sessionHandle", "strict-handle").put("sub", "strict-user")
                .put("tId", "public").put("exp", 4102444800L);
        String access = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"version\":\"4\"}", true)
                + "." + encode(payload.toString(), true) + ".c2ln";
        String front = encode(new JSONObject().put("uid", "strict-user").put("ate", 4102444800000L)
                .put("up", payload).toString(), false);
        return new SessionTokens(access, "strict-refresh", front, "strict-csrf");
    }
}
