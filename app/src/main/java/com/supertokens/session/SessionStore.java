package com.supertokens.session;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.Response;
import org.jetbrains.annotations.TestOnly;

/** The lock protects coherent reads/writes, never requests, listeners or event handlers. */
final class SessionStore {
    private static final Object lock = new Object();
    private static SuperTokens.SessionSnapshot snapshot;
    static final CopyOnWriteArrayList<SuperTokens.SessionListener> listeners = new CopyOnWriteArrayList<SuperTokens.SessionListener>();
    static final CopyOnWriteArrayList<SuperTokens.SessionEventListener> eventListeners = new CopyOnWriteArrayList<SuperTokens.SessionEventListener>();
    private static volatile Runnable beforeEventDeliveryForTests;
    @TestOnly static void setBeforeEventDeliveryForTests(Runnable hook) { beforeEventDeliveryForTests = hook; }
    private static final ThreadLocal<ArrayList<Change>> deferred = new ThreadLocal<ArrayList<Change>>();
    private static final ThreadLocal<Integer> refreshDepth = new ThreadLocal<Integer>();

    static void enterRefreshLock() {
        Integer depth = refreshDepth.get();
        refreshDepth.set(depth == null ? 1 : depth + 1);
    }

    static void leaveRefreshLock() {
        int depth = refreshDepth.get() - 1;
        if (depth == 0) refreshDepth.remove(); else refreshDepth.set(depth);
        flushCallbacks();
    }

    private static boolean callbackLocked() {
        return Thread.holdsLock(lock) || refreshDepth.get() != null;
    }

    static void flushCallbacks() {
        if (callbackLocked()) return;
        ArrayList<Change> pending = deferred.get();
        if (pending == null) return;
        deferred.remove();
        for (Change change : pending) change.dispatch();
    }

    static SuperTokens.SessionSnapshot read(Context context) {
        synchronized (lock) {
            if (snapshot == null) {
                snapshot = readPreferences(context, 0);
            }
            return snapshot;
        }
    }

    private static SuperTokens.SessionSnapshot readPreferences(Context context, long generation) {
        SharedPreferences p = Utils.getSharedPreferences(context);
        return new SuperTokens.SessionSnapshot(generation,
                p.getString(key(Constants.ACCESS_TOKEN_PREFS_KEY), null),
                p.getString(key(Constants.REFRESH_TOKEN_PREFS_KEY), null),
                p.getString(Constants.FRONT_TOKEN_PREFS_KEY, null),
                p.getString(Constants.CSRF_TOKEN_PREFS_KEY, null),
                p.getString(key(Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY), null));
    }

    static String key(String name) { return "st-storage-item-" + name; }
    private static String nonempty(String value) { return value == null || value.length() == 0 ? null : value; }

    private static Change write(Context context, SuperTokens.SessionSnapshot next) {
        SharedPreferences.Editor e = Utils.getSharedPreferences(context).edit();
        e.putString(key(Constants.ACCESS_TOKEN_PREFS_KEY), next.accessToken);
        e.putString(key(Constants.REFRESH_TOKEN_PREFS_KEY), next.refreshToken);
        e.putString(Constants.FRONT_TOKEN_PREFS_KEY, next.frontToken);
        e.putString(Constants.CSRF_TOKEN_PREFS_KEY, next.antiCsrfToken);
        e.putString(key(Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY), next.lastAccessTokenUpdate);
        e.remove(key("sIRTFrontend"));
        // apply invokes preference listeners synchronously on the main thread.
        snapshot = next;
        try {
            e.apply();
        } catch (RuntimeException | Error failure) {
            // A custom editor or listener can fail before or after publishing preferences.
            // Re-read the actual values without reusing a generation that may have escaped.
            snapshot = readPreferences(context, snapshot.generation + 1);
            throw failure;
        }
        return new Change(next, true);
    }

    static Change replace(Context context, long expected, SuperTokens.SessionTokens tokens) {
        Change change;
        synchronized (lock) {
            SuperTokens.SessionSnapshot old = read(context);
            if (old.generation != expected) return null;
            change = write(context, new SuperTokens.SessionSnapshot(old.generation + 1,
                    tokens == null ? null : nonempty(tokens.accessToken),
                    tokens == null ? null : nonempty(tokens.refreshToken),
                    tokens == null ? null : nonempty(tokens.frontToken),
                    tokens == null ? null : nonempty(tokens.antiCsrfToken),
                    tokens == null ? null : Long.toString(System.currentTimeMillis())));
        }
        wakeWaiters();
        return change;
    }

    static Change response(Context context, long generation, Response response, boolean refresh) {
        return response(context, generation, response, refresh, false);
    }

    static Change response(Context context, long generation, Response response, boolean refresh, boolean legacy) {
        return response(context, generation, response.header(Constants.ACCESS_TOKEN_HEADER_KEY),
                response.header(Constants.REFRESH_TOKEN_HEADER_KEY), response.header(Constants.FRONT_TOKEN_HEADER_KEY),
                response.header(Constants.CSRF_HEADER_KEY), response.code(), refresh, legacy);
    }

    static Change response(Context context, long generation, SuperTokensCustomHttpURLConnection response, boolean refresh) throws IOException {
        return response(context, generation, response, refresh, false);
    }

    static Change response(Context context, long generation, SuperTokensCustomHttpURLConnection response, boolean refresh, boolean legacy) throws IOException {
        return response(context, generation, response.getHeaderField(Constants.ACCESS_TOKEN_HEADER_KEY),
                response.getHeaderField(Constants.REFRESH_TOKEN_HEADER_KEY), response.getHeaderField(Constants.FRONT_TOKEN_HEADER_KEY),
                response.getHeaderField(Constants.CSRF_HEADER_KEY), response.getResponseCode(), refresh, legacy);
    }

    private static Change response(Context context, long generation, String access, String refreshToken,
                                   String front, String csrf, int status, boolean refresh, boolean legacy) {
        Change change;
        synchronized (lock) {
            SuperTokens.SessionSnapshot old = read(context);
            if (old.generation != generation) return null;
            boolean remove = "remove".equalsIgnoreCase(front) || (refresh && status == SuperTokens.config.sessionExpiredStatusCode);
            boolean changed = legacy || remove || access != null || refreshToken != null || front != null || csrf != null;
            if (!changed) {
                change = new Change(old, false);
            } else {
                boolean created = !old.doesSessionExist() && front != null && !remove;
                SuperTokens.SessionSnapshot next = new SuperTokens.SessionSnapshot(
                        old.generation + (legacy || remove || created ? 1 : 0),
                        remove ? null : access == null ? old.accessToken : nonempty(access),
                        remove ? null : refreshToken == null ? old.refreshToken : nonempty(refreshToken),
                        remove ? null : front == null ? old.frontToken : front,
                        remove ? null : csrf == null ? old.antiCsrfToken : nonempty(csrf),
                        remove ? null : front == null ? old.lastAccessTokenUpdate : Long.toString(System.currentTimeMillis()));
                boolean payloadChanged = front != null && old.frontToken != null && !remove
                        && FrontToken.payloadChanged(old.frontToken, front);
                change = write(context, next);
                if (!legacy && remove && old.doesSessionExist()) {
                    change.events.add(status == SuperTokens.config.sessionExpiredStatusCode ? EventHandler.EventType.UNAUTHORISED : EventHandler.EventType.SIGN_OUT);
                } else if (!legacy && created) {
                    change.events.add(EventHandler.EventType.SESSION_CREATED);
                } else if (payloadChanged) {
                    change.events.add(EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED);
                }
            }
            if (refresh && status >= 200 && status < 300 && change.next.doesSessionExist()) {
                change.events.add(EventHandler.EventType.REFRESH_SESSION);
            }
        }
        wakeWaiters();
        return change;
    }

    // Legacy partial writes are replacements: they cannot safely claim ownership of an in-flight request.
    static void legacy(Context context, String name, String value) {
        legacy(context, name, value, true, true);
    }

    static void legacy(Context context, String name, String value, boolean updateFrontTimestamp, boolean clearOnRemove) {
        Change change;
        synchronized (lock) {
            SuperTokens.SessionSnapshot s = read(context);
            String v = nonempty(value);
            boolean payloadChanged = Constants.FRONT_TOKEN_PREFS_KEY.equals(name) && s.frontToken != null
                    && v != null && !"remove".equalsIgnoreCase(v) && FrontToken.payloadChanged(s.frontToken, v);
            if (clearOnRemove && Constants.FRONT_TOKEN_PREFS_KEY.equals(name) && (v == null || "remove".equalsIgnoreCase(v))) {
                change = write(context, new SuperTokens.SessionSnapshot(s.generation + 1, null, null, null, null, null));
            } else {
                change = write(context, new SuperTokens.SessionSnapshot(s.generation + 1,
                        Constants.ACCESS_TOKEN_PREFS_KEY.equals(name) ? v : s.accessToken,
                        Constants.REFRESH_TOKEN_PREFS_KEY.equals(name) ? v : s.refreshToken,
                        Constants.FRONT_TOKEN_PREFS_KEY.equals(name) ? v : s.frontToken,
                        Constants.CSRF_TOKEN_PREFS_KEY.equals(name) ? v : s.antiCsrfToken,
                        Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY.equals(name) ? v :
                                Constants.FRONT_TOKEN_PREFS_KEY.equals(name) && updateFrontTimestamp ? Long.toString(System.currentTimeMillis()) : s.lastAccessTokenUpdate));
            }
            if (payloadChanged) change.events.add(EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED);
        }
        wakeWaiters();
        flushCallbacks();
        change.dispatch();
    }

    static boolean isSessionKey(String name) {
        return Constants.ACCESS_TOKEN_PREFS_KEY.equals(name) || Constants.REFRESH_TOKEN_PREFS_KEY.equals(name)
                || Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY.equals(name);
    }

    interface CookieWrite { void run() throws IOException, IllegalAccessException; }

    static void saveCookies(Change change, CookieWrite write) throws IOException, IllegalAccessException {
        try {
            synchronized (lock) {
                if (change != null && change.current()) write.run();
            }
        } finally {
            wakeWaiters();
            flushCallbacks();
        }
    }

    private static void wakeWaiters() {
        if (Thread.holdsLock(lock)) return;
        synchronized (FrontToken.tokenLock) { FrontToken.tokenLock.notifyAll(); }
    }

    static void reset() {
        synchronized (lock) { snapshot = null; listeners.clear(); eventListeners.clear(); beforeEventDeliveryForTests = null; }
        wakeWaiters();
    }

    static final class Change {
        final SuperTokens.SessionSnapshot next;
        final boolean changed;
        final ArrayList<EventHandler.EventType> events = new ArrayList<EventHandler.EventType>();
        Change(SuperTokens.SessionSnapshot next, boolean changed) { this.next = next; this.changed = changed; }

        boolean current() { synchronized (lock) { return snapshot == next; } }

        boolean sameGeneration() {
            synchronized (lock) { return snapshot != null && snapshot.generation == next.generation; }
        }

        void dispatch() {
            if (callbackLocked()) {
                ArrayList<Change> pending = deferred.get();
                if (pending == null) {
                    pending = new ArrayList<Change>();
                    deferred.set(pending);
                }
                pending.add(this);
                return;
            }
            if (changed) for (SuperTokens.SessionListener listener : listeners) {
                if (!current()) return;
                listener.onSessionChanged(next);
            }
            for (EventHandler.EventType event : events) {
                SuperTokens.SessionEvent notification = new SuperTokens.SessionEvent(event, next);
                for (SuperTokens.SessionEventListener listener : eventListeners) {
                    if (!current()) return;
                    runEventDeliveryHook();
                    listener.onSessionEvent(notification);
                }
                if (!current()) return;
                runEventDeliveryHook();
                SuperTokens.config.eventHandler.handleEvent(event);
            }
        }

        private void runEventDeliveryHook() {
            Runnable hook = beforeEventDeliveryForTests;
            if (hook != null) hook.run();
        }
    }
}
