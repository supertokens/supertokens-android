package com.supertokens.session.legacy054;

import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import static org.mockito.Mockito.*;

/** Applies always change process memory; only the selected prefix reaches durable storage. */
final class CrashCheckpointPreferences {
    private final Map<String, Object> memory = new LinkedHashMap<>();
    private final Map<String, Object> disk = new LinkedHashMap<>();
    private int limit = Integer.MAX_VALUE;
    int applies;
    int commits;
    int persistedApplies;
    final List<String> edits = new ArrayList<>();
    final Context context = mock(Context.class);
    final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                synchronized (this) {
                    String name = method.getName();
                    if (name.equals("getAll")) return copy(memory);
                    if (name.equals("contains")) return memory.containsKey(args[0]);
                    if (name.startsWith("get")) return copyValue(memory.getOrDefault(args[0], args[1]));
                    if (name.equals("edit")) return editor();
                    throw new UnsupportedOperationException(name);
                }
            });

    CrashCheckpointPreferences() {
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
    }

    synchronized void arm(int acknowledgedApplies) {
        if (acknowledgedApplies < 0) throw new IllegalArgumentException("Negative checkpoint");
        limit = acknowledgedApplies;
        applies = commits = persistedApplies = 0;
        edits.clear();
    }

    synchronized void crashAndRecover() {
        memory.clear();
        memory.putAll(copy(disk));
        limit = Integer.MAX_VALUE;
    }

    synchronized Map<String, Object> durable() { return copy(disk); }
    synchronized Map<String, Object> memory() { return copy(memory); }

    private Object editor() {
        Map<String, Object> pending = new LinkedHashMap<>();
        boolean[] clear = {false};
        return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                    synchronized (this) {
                        String name = method.getName();
                        if (name.startsWith("put")) { pending.put((String) args[0], copyValue(args[1])); return proxy; }
                        if (name.equals("remove")) { pending.put((String) args[0], null); return proxy; }
                        if (name.equals("clear")) { clear[0] = true; return proxy; }
                        if (!name.equals("apply") && !name.equals("commit")) throw new UnsupportedOperationException(name);
                        boolean apply = name.equals("apply");
                        if (apply) applies++; else commits++;
                        edits.add(name + ":clear=" + clear[0] + ":keys=" + new TreeSet<>(pending.keySet()));
                        if (clear[0]) memory.clear();
                        pending.forEach((key, value) -> { if (value == null) memory.remove(key); else memory.put(key, copyValue(value)); });
                        pending.clear();
                        clear[0] = false;
                        boolean persist = !apply || applies <= limit;
                        if (persist) {
                            disk.clear();
                            disk.putAll(copy(memory));
                            if (apply) persistedApplies++;
                        }
                        return apply ? null : true;
                    }
                });
    }

    private static Object copyValue(Object value) { return value instanceof Set ? new HashSet<>((Set<?>) value) : value; }
    private static Map<String, Object> copy(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, copyValue(value)));
        return result;
    }
}
