package com.supertokens.session.legacy054;

import android.content.Context;
import android.content.SharedPreferences;
import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.SessionTestFixture;
import com.supertokens.session.SuperTokens;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(Parameterized.class)
public class PreInitGetterParityTest extends SessionTestFixture {
    @Parameterized.Parameters(name = "{0}") public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][]{{"empty"}, {"valid"}, {"missingMarker"}, {"expired"},
                {"badBase64"}, {"badJson"}, {"missingUid"}, {"missingAte"}, {"missingUp"}, {"badAte"},
                {"emptyMarker"}, {"badMarker"}, {"wrongFrontType"}, {"wrongMarkerType"},
                {"cookieOnly"}, {"emptyAccess"}, {"wrongAccessType"}, {"markerOnly"}, {"emptyFront"}});
    }

    private final String kind;
    public PreInitGetterParityTest(String kind) { this.kind = kind; }
    @Override protected boolean initializeOnSetup() { return false; }

    @Test public void publishedReadOnlyGettersMatchWithoutInitialization() throws Exception {
        Map<String, Object> values = values();
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        when(preferences.getString(anyString(), any())).thenAnswer(call -> {
            String key = (String) call.getArguments()[0];
            return (String) values.getOrDefault(key, call.getArguments()[1]);
        });
        when(preferences.edit()).thenThrow(new AssertionError("Pre-init getter attempted a write"));
        for (String method : new String[]{"getUserId", "doesSessionExist", "getAccessToken",
                "getAccessTokenPayloadSecurely", "FrontToken.getToken", "FrontToken.doesTokenExist",
                "Utils.getLocalSessionState"}) {
            try (Legacy054Harness old = new Legacy054Harness(context)) {
                String expected = outcome(old.sdkClass().getClassLoader(), method);
                String actual = outcome(SuperTokens.class.getClassLoader(), method);
                assertEquals(kind + " " + method, expected, actual);
                Field initialized = old.sdkClass().getDeclaredField("isInitCalled");
                initialized.setAccessible(true);
                assertFalse(initialized.getBoolean(null));
            }
        }
        verify(preferences, never()).edit();
        assertEquals(0, server.getRequestCount());
        try { SuperTokens.getSessionSnapshot(context); fail("Getter initialized candidate state"); }
        catch (IllegalStateException expected) { assertEquals("SuperTokens.init function not called", expected.getMessage()); }
        try { SuperTokens.captureRequestTag(context); fail("Explicit API must require initialization"); }
        catch (IllegalStateException expected) { assertEquals("SuperTokens.init function not called", expected.getMessage()); }
    }

    private String outcome(ClassLoader loader, String method) throws Exception {
        String type = method.contains(".") ? method.substring(0, method.indexOf('.')) : "SuperTokens";
        String name = method.substring(method.indexOf('.') + 1);
        Class<?> clazz = loader.loadClass("com.supertokens.session." + type);
        try {
            Object result = clazz.getMethod(name, Context.class).invoke(null, context);
            if (name.equals("getLocalSessionState")) {
                Field status = result.getClass().getDeclaredField("status");
                Field marker = result.getClass().getDeclaredField("lastAccessTokenUpdate");
                status.setAccessible(true);
                marker.setAccessible(true);
                return status.get(result) + ":" + marker.get(result);
            }
            return String.valueOf(result);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            return cause.getClass().getName() + ":" + cause.getMessage() + ":cause="
                    + (cause.getCause() == null ? "null" : cause.getCause().getClass().getName());
        }
    }

    private Map<String, Object> values() throws Exception {
        Map<String, Object> values = new HashMap<>();
        if (kind.equals("empty")) return values;
        String json = "{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{\"claim\":true}}";
        if (kind.equals("expired")) json = json.replace("4102444800000", "1");
        if (kind.equals("missingUid")) json = json.replace("\"uid\":\"user\",", "");
        if (kind.equals("missingAte")) json = json.replace("\"ate\":4102444800000,", "");
        if (kind.equals("missingUp")) json = json.replace(",\"up\":{\"claim\":true}", "");
        if (kind.equals("badAte")) json = json.replace("4102444800000", "\"bad\"");
        if (kind.equals("badJson")) json = "not json";
        values.put("supertokens-android-fronttoken-key", kind.equals("badBase64") ? "%%%"
                : Base64.getEncoder().encodeToString(json.getBytes("UTF-8")));
        values.put("st-storage-item-st-access-token", "opaque-access");
        if (!kind.equals("missingMarker")) values.put("st-storage-item-st-last-access-token-update",
                kind.equals("emptyMarker") ? "" : kind.equals("badMarker") ? "bad" : "123");
        if (kind.equals("wrongFrontType")) values.put("supertokens-android-fronttoken-key", 123);
        if (kind.equals("wrongMarkerType")) values.put("st-storage-item-st-last-access-token-update", 123);
        if (kind.equals("wrongAccessType")) values.put("st-storage-item-st-access-token", 123);
        if (kind.equals("emptyAccess")) values.put("st-storage-item-st-access-token", "");
        if (kind.equals("emptyFront")) values.put("supertokens-android-fronttoken-key", "");
        if (kind.equals("cookieOnly")) values.remove("st-storage-item-st-access-token");
        if (kind.equals("markerOnly")) values.remove("supertokens-android-fronttoken-key");
        return values;
    }
}
