package com.example.example;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.supertokens.session.FrontToken;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.Utils;
import java.nio.charset.StandardCharsets;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SessionRestartInstrumentedTest {
    private static final String PREFS = "supertokens-android-shared-preferences";
    private static final String CONTROL = "base-android-restart-control";

    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    private void phase(String name) {
        Assume.assumeTrue(name.equals(InstrumentationRegistry.getArguments().getString("restartPhase")));
    }

    @Test public void seedLegacyPreferences() throws Exception {
        phase("seed");
        Context context = context();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String front = Base64.encodeToString("{\"uid\":\"restart-user\",\"ate\":4102444800000,\"up\":{}}".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        assertTrue(prefs.edit().clear().putString("st-storage-item-st-access-token", "legacy-access")
                .putString("st-storage-item-st-refresh-token", "legacy-refresh")
                .putString("st-storage-item-st-last-access-token-update", "1")
                .putString("supertokens-android-fronttoken-key", front)
                .putString("supertokens-android-anticsrf-key", "legacy-csrf").commit());
        new SuperTokens.Builder(context, "http://localhost").build();
        assertEquals("legacy-access", SuperTokens.getAccessToken(context));
        Utils.setToken(Utils.TokenType.ACCESS, "written-access", context);
        assertEquals("written-access", SuperTokens.getAccessToken(context));
        assertTrue(context.getSharedPreferences(CONTROL, Context.MODE_PRIVATE).edit()
                .putInt("seedPid", Process.myPid()).commit());
        assertTrue(prefs.edit().commit());
    }

    @Test public void restoreAfterExternalForceStop() throws Exception {
        phase("verify");
        Context context = context();
        SharedPreferences control = context.getSharedPreferences(CONTROL, Context.MODE_PRIVATE);
        assertNotEquals(0, control.getInt("seedPid", 0));
        assertNotEquals("controller must restart the target process", control.getInt("seedPid", 0), Process.myPid());
        new SuperTokens.Builder(context, "http://localhost").build();
        SuperTokens.SessionSnapshot snapshot = SuperTokens.getSessionSnapshot(context);
        assertEquals(0, snapshot.generation);
        assertEquals("written-access", SuperTokens.getAccessToken(context));
        assertEquals("legacy-refresh", Utils.getTokenForHeaderAuth(Utils.TokenType.REFRESH, context));
        assertEquals("legacy-csrf", snapshot.antiCsrfToken);
        assertTrue(SuperTokens.doesSessionExist(context));
        FrontToken.removeToken(context);
        assertFalse(SuperTokens.doesSessionExist(context));
        assertNull(SuperTokens.getAccessToken(context));
        assertTrue(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit());
        assertTrue(control.edit().clear().commit());
    }
}
