package com.supertokens.session.legacy054;

import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import static org.mockito.Mockito.*;

/** Oracle-local acknowledged storage, with value-free diffs for each published SDK write. */
final class OraclePreferences {
    static final String ACCESS = "st-storage-item-st-access-token";
    static final String REFRESH = "st-storage-item-st-refresh-token";
    static final String FRONT = "supertokens-android-fronttoken-key";
    static final String CSRF = "supertokens-android-anticsrf-key";
    static final String MARKER = "st-storage-item-st-last-access-token-update";
    static final String OLD_FRONTEND = "st-storage-item-sIRTFrontend";
    final Map<String, Object> values = new LinkedHashMap<>();
    final List<String> writes = new ArrayList<>();
    final Context context;

    OraclePreferences(Map<String, ?> initial) {
        values.putAll(initial);
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    synchronized (values) {
                        String name = method.getName();
                        if (name.equals("getAll")) return snapshot();
                        if (name.equals("contains")) return values.containsKey(args[0]);
                        if (name.startsWith("get")) return values.getOrDefault(args[0], args[1]);
                        if (name.equals("edit")) return editor();
                        throw new UnsupportedOperationException(name);
                    }
                });
        context = mock(Context.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
    }

    private Object editor() {
        Map<String, Object> pending = new LinkedHashMap<>();
        boolean[] clear = {false};
        return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                    synchronized (values) {
                        String name = method.getName();
                        if (name.startsWith("put")) { pending.put((String) args[0], args[1]); return proxy; }
                        if (name.equals("remove")) { pending.put((String) args[0], null); return proxy; }
                        if (name.equals("clear")) { clear[0] = true; return proxy; }
                        if (name.equals("apply") || name.equals("commit")) {
                            Map<String, Object> before = snapshot();
                            if (clear[0]) values.clear();
                            pending.forEach((key, value) -> { if (value == null) values.remove(key); else values.put(key, value); });
                            writes.add(name + ":" + diff(before, values));
                            return name.equals("commit") ? true : null;
                        }
                        throw new UnsupportedOperationException(name);
                    }
                });
    }

    Map<String, Object> snapshot() {
        synchronized (values) { return new LinkedHashMap<>(values); }
    }

    static String diff(Map<String, ?> before, Map<String, ?> after) {
        List<String> changes = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            Object oldValue = before.get(key);
            Object newValue = after.get(key);
            if (java.util.Objects.equals(oldValue, newValue)) continue;
            changes.add((oldValue == null ? "+" : newValue == null ? "-" : "~") + key
                    + "(" + type(oldValue) + "->" + type(newValue) + ")");
        }
        return changes.toString();
    }

    private static String type(Object value) { return value == null ? "absent" : value.getClass().getSimpleName(); }
}
