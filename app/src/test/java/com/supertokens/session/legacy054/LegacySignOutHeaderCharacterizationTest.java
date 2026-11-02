package com.supertokens.session.legacy054;

import android.content.Context;
import com.supertokens.session.Legacy054Harness;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacySignOutHeaderCharacterizationTest {
    @Test public void providerHeadersOnWire() throws Exception {
        java.net.CookieHandler previous = java.net.CookieHandler.getDefault();
        java.net.CookieHandler.setDefault(new java.net.CookieManager());
        try {
        for (boolean cookie : new boolean[]{false, true}) for (boolean duplicates : new boolean[]{false, true}) {
            Map<String, Object> initial = new LinkedHashMap<>();
            initial.put(OraclePreferences.FRONT, Base64.getEncoder().encodeToString(
                    "{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{}}".getBytes(StandardCharsets.UTF_8)));
            initial.put(OraclePreferences.MARKER, "1");
            if (!cookie) {
                initial.put(OraclePreferences.ACCESS, "access");
                initial.put(OraclePreferences.REFRESH, "refresh");
            }
            OraclePreferences store = new OraclePreferences(initial);
            try (MockWebServer server = new MockWebServer(); Legacy054Harness legacy = new Legacy054Harness(store.context)) {
                server.start();
                ClassLoader loader = legacy.sdkClass().getClassLoader();
                Class<?> provider = loader.loadClass("com.supertokens.session.CustomHeaderProvider");
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Authorization", "Basic custom");
                headers.put("rid", "custom-recipe");
                if (duplicates) {
                    headers.put("authorization", "Basic second");
                    headers.put("RID", "second-recipe");
                }
                Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{provider}, (p, method, args) -> headers);
                Class<?> builder = loader.loadClass("com.supertokens.session.SuperTokens$Builder");
                Object instance = builder.getConstructor(Context.class, String.class).newInstance(store.context, server.url("/").toString());
                builder.getMethod("tokenTransferMethod", String.class).invoke(instance, cookie ? "cookie" : "header");
                builder.getMethod("customHeaderProvider", provider).invoke(instance, proxy);
                builder.getMethod("build").invoke(instance);
                server.enqueue(new MockResponse().setBody("{\"status\":\"OK\"}"));
                legacy.invoke("signOut");
                RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
                assertNotNull(request);
                assertEquals(java.util.Collections.singletonList(duplicates ? "Basic second" : "Basic custom"),
                        request.getHeaders().values("Authorization"));
                assertEquals(java.util.Collections.singletonList(duplicates ? "second-recipe" : "custom-recipe"),
                        request.getHeaders().values("rid"));
            }
        }
        } finally { java.net.CookieHandler.setDefault(previous); }
    }
}
