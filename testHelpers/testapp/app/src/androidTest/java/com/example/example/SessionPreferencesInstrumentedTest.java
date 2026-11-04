package com.example.example;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.supertokens.session.SuperTokens;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SessionPreferencesInstrumentedTest {
    private Context context;
    private SharedPreferences prefs;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences("supertokens-android-shared-preferences", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        SuperTokens.resetForTests();
        new SuperTokens.Builder(context, "http://localhost").build();
    }

    @After public void tearDown() {
        SuperTokens.resetForTests();
        prefs.edit().clear().commit();
    }

    @Test public void synchronousPreferenceListenerSeesPublishedSnapshotAndCanInstall() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean entered = new AtomicBoolean();
        SharedPreferences.OnSharedPreferenceChangeListener listener = (preferences, key) -> {
            if (!entered.compareAndSet(false, true)) return;
            try {
                SuperTokens.SessionSnapshot outer = SuperTokens.getSessionSnapshot(context);
                assertEquals(1, outer.generation);
                assertEquals("outer-access", outer.accessToken);
                assertTrue(SuperTokens.installSession(context, outer.generation,
                        new SuperTokens.SessionTokens("nested-access", "nested-refresh", "nested-front", "nested-csrf")));
                assertEquals(2, SuperTokens.getSessionSnapshot(context).generation);
            } catch (Throwable t) { failure.set(t); }
        };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            prefs.registerOnSharedPreferenceChangeListener(listener);
            try {
                assertTrue(SuperTokens.installSession(context, 0,
                        new SuperTokens.SessionTokens("outer-access", "outer-refresh", "outer-front", "outer-csrf")));
            } catch (Throwable t) { failure.set(t); }
            finally { prefs.unregisterOnSharedPreferenceChangeListener(listener); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertTrue("listener must run synchronously on the main thread", entered.get());
        SuperTokens.SessionSnapshot result = SuperTokens.getSessionSnapshot(context);
        assertEquals(2, result.generation);
        assertEquals("nested-access", result.accessToken);
        assertEquals("nested-refresh", result.refreshToken);
        assertEquals("nested-front", result.frontToken);
        assertEquals("nested-csrf", result.antiCsrfToken);
        assertEquals(result.accessToken, prefs.getString("st-storage-item-st-access-token", null));
        assertEquals(result.refreshToken, prefs.getString("st-storage-item-st-refresh-token", null));
        assertEquals(result.frontToken, prefs.getString("supertokens-android-fronttoken-key", null));
        assertEquals(result.antiCsrfToken, prefs.getString("supertokens-android-anticsrf-key", null));
        assertEquals(result.lastAccessTokenUpdate, prefs.getString("st-storage-item-st-last-access-token-update", null));
    }
}
