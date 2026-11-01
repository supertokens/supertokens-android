package com.supertokens.session.legacy054;

import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.Test;
import static org.junit.Assert.*;

public class CrashCheckpointPreferencesTest {
    @Test public void lostApplyRemainsVisibleOnlyUntilCrashAndTypesSurvive() {
        CrashCheckpointPreferences store = new CrashCheckpointPreferences();
        store.arm(1);
        HashSet<String> set = new HashSet<>(Arrays.asList("one", "two"));
        store.preferences.edit().putString("string", "value").putInt("int", 7).putLong("long", 7L)
                .putFloat("float", 1.5f).putBoolean("bool", true).putStringSet("set", set).apply();
        set.clear();
        store.preferences.edit().remove("int").putString("lost", "memory-only").apply();
        assertTrue(store.preferences.contains("lost"));
        assertFalse(store.preferences.contains("int"));
        assertFalse(store.durable().containsKey("lost"));
        assertEquals(2, store.applies);
        assertEquals(1, store.persistedApplies);
        store.crashAndRecover();
        assertFalse(store.preferences.contains("lost"));
        assertEquals(Integer.class, store.durable().get("int").getClass());
        assertEquals(Long.class, store.durable().get("long").getClass());
        assertEquals(Float.class, store.durable().get("float").getClass());
        assertEquals(Boolean.TRUE, store.durable().get("bool"));
        assertEquals(2, store.preferences.getStringSet("set", null).size());
    }

    @Test public void zeroPrefixDropsClearAndRecoveredCommitPersists() {
        CrashCheckpointPreferences store = new CrashCheckpointPreferences();
        assertTrue(store.preferences.edit().putString("seed", "kept").commit());
        store.arm(0);
        SharedPreferences.Editor editor = store.preferences.edit();
        editor.clear().putString("new", "lost").apply();
        assertFalse(store.preferences.contains("seed"));
        editor.putString("second", "also-lost").apply();
        assertTrue(store.preferences.contains("new"));
        store.crashAndRecover();
        assertEquals("kept", store.preferences.getString("seed", null));
        assertFalse(store.preferences.contains("second"));
        assertTrue(store.preferences.edit().putString("recovered", "durable").commit());
        store.crashAndRecover();
        assertEquals("durable", store.preferences.getString("recovered", null));
    }
}
