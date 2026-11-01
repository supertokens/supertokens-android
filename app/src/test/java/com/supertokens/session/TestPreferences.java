package com.supertokens.session;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A deterministic memory/disk model; apply is flushed explicitly, never by a timer. */
final class TestPreferences {
    enum CommitMode { ACKNOWLEDGED, RETURN_FALSE, THROW, RETURN_FALSE_AFTER_MEMORY, THROW_AFTER_MEMORY }
    volatile CommitMode commitMode = CommitMode.ACKNOWLEDGED;
    private final Map<String, Object> memory = new HashMap<>();
    private final Map<String, Object> disk = new HashMap<>();
    int commits;
    int applies;
    final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                synchronized (this) {
                    String name = method.getName();
                    if (name.equals("getAll")) return copy(memory);
                    if (name.equals("contains")) return memory.containsKey(args[0]);
                    if (name.startsWith("get")) return copyValue(memory.getOrDefault(args[0], args[1]));
                    if (name.equals("edit")) return editor();
                    if (name.equals("registerOnSharedPreferenceChangeListener") || name.equals("unregisterOnSharedPreferenceChangeListener"))
                        throw new UnsupportedOperationException("Listener delivery is not modelled");
                    throw new UnsupportedOperationException(name);
                }
            });

    synchronized void seed(Map<String, ?> values) {
        memory.clear();
        values.forEach((key, value) -> { if (value != null) memory.put(key, copyValue(value)); });
        flush();
    }

    synchronized void restart() { memory.clear(); memory.putAll(copy(disk)); }
    synchronized void flush() { disk.clear(); disk.putAll(copy(memory)); }
    synchronized Map<String, Object> durableValues() { return copy(disk); }

    private SharedPreferences.Editor editor() {
        Map<String, Object> pending = new HashMap<>();
        boolean[] clear = {false};
        return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                    synchronized (this) {
                        String name = method.getName();
                        if (name.startsWith("put")) { pending.put((String) args[0], copyValue(args[1])); return proxy; }
                        if (name.equals("remove")) { pending.put((String) args[0], null); return proxy; }
                        if (name.equals("clear")) { clear[0] = true; return proxy; }
                        if (!name.equals("commit") && !name.equals("apply")) throw new UnsupportedOperationException(name);
                        boolean apply = name.equals("apply");
                        if (apply) applies++; else commits++;
                        CommitMode mode = commitMode;
                        if (apply || mode == CommitMode.ACKNOWLEDGED || mode == CommitMode.RETURN_FALSE_AFTER_MEMORY || mode == CommitMode.THROW_AFTER_MEMORY) {
                            if (clear[0]) memory.clear();
                            pending.forEach((key, value) -> { if (value == null) memory.remove(key); else memory.put(key, copyValue(value)); });
                        }
                        pending.clear();
                        clear[0] = false;
                        if (apply) return null;
                        if (mode == CommitMode.THROW || mode == CommitMode.THROW_AFTER_MEMORY) throw new IllegalStateException("Injected commit failure");
                        if (mode != CommitMode.ACKNOWLEDGED) return false;
                        flush();
                        return true;
                    }
                });
    }

    private static Object copyValue(Object value) { return value instanceof Set ? new HashSet<>((Set<?>) value) : value; }
    private static Map<String, Object> copy(Map<String, Object> source) {
        Map<String, Object> result = new HashMap<>();
        source.forEach((key, value) -> result.put(key, copyValue(value)));
        return result;
    }
}
