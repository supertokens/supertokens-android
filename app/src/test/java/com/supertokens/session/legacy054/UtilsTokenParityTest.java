package com.supertokens.session.legacy054;

import android.content.Context;
import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.SessionTestFixture;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.Utils;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class UtilsTokenParityTest extends SessionTestFixture {
    @Override protected boolean initializeOnSetup() { return false; }

    @Test public void preInitAuthorizationMatchesPublishedTokenPairs() throws Exception {
        for (String access : new String[]{null, "", "access"}) {
            for (String refresh : new String[]{null, "", "refresh"}) {
                Map<String, Object> values = new HashMap<>();
                if (access != null) values.put("st-storage-item-st-access-token", access);
                if (refresh != null) values.put("st-storage-item-st-refresh-token", refresh);
                seedLegacy(values);
                try (Legacy054Harness old = new Legacy054Harness(context)) {
                    Class<?> utils = old.sdkClass().getClassLoader().loadClass("com.supertokens.session.Utils");
                    for (boolean useRefresh : new boolean[]{false, true}) {
                        Object expected = utils.getMethod("getAuthorizationHeaderIfExists", boolean.class, Context.class)
                                .invoke(null, useRefresh, context);
                        assertEquals("access=" + access + ", refresh=" + refresh, expected,
                                Utils.getAuthorizationHeaderIfExists(useRefresh, context));
                    }
                    assertEquals(false, old.invoke("isInitialized"));
                }
            }
        }
        assertEquals(0, server.getRequestCount());
        try { SuperTokens.getSessionSnapshot(context); fail("initialized by read"); }
        catch (IllegalStateException expected) { }
    }

    @Test public void initializedExposedCookieTokenMatchesPublishedAccessor() throws Exception {
        Map<String, Object> values = new HashMap<>();
        values.put("st-storage-item-st-access-token", "exposed-access");
        values.put("st-storage-item-st-last-access-token-update", "123");
        values.put("supertokens-android-fronttoken-key", Base64.getEncoder().encodeToString(
                "{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{}}".getBytes("UTF-8")));
        seedLegacy(values);
        try (Legacy054Harness old = new Legacy054Harness(context)) {
            old.build(server.url("/").toString(), "cookie");
            ClassLoader loader = old.sdkClass().getClassLoader();
            Class<?> utils = loader.loadClass("com.supertokens.session.Utils");
            Class<?> tokenType = loader.loadClass("com.supertokens.session.Utils$TokenType");
            Object access = tokenType.getField("ACCESS").get(null);
            Object expected = utils.getMethod("getTokenForHeaderAuth", tokenType, Context.class)
                    .invoke(null, access, context);
            assertEquals("exposed-access", expected);
            new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("cookie").build();
            assertEquals(expected, Utils.getTokenForHeaderAuth(Utils.TokenType.ACCESS, context));
            assertNull(Utils.getTokenForHeaderAuth(Utils.TokenType.REFRESH, context));
            assertNull(Utils.getAuthorizationHeaderIfExists(false, context));
            assertNull(Utils.getAuthorizationHeaderIfExists(true, context));
            assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
        }
    }
}
