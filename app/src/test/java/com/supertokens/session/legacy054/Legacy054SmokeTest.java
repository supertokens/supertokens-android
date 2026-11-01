package com.supertokens.session.legacy054;

import android.content.Context;
import android.content.SharedPreferences;
import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.SuperTokens;
import java.net.CookieHandler;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class Legacy054SmokeTest {
    private MockWebServer server;
    private CookieHandler cookies;

    @Before public void setUp() throws Exception {
        cookies = CookieHandler.getDefault();
        CookieHandler.setDefault(null);
        server = new MockWebServer();
        server.start();
    }

    @After public void tearDown() throws Exception {
        try { server.shutdown(); } finally { CookieHandler.setDefault(cookies); }
    }

    private static Context context(SharedPreferences preferences) {
        Context context = mock(Context.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        return context;
    }

    // Smoke-only string store: the full persistence/failure model belongs to SessionTestFixture.
    private static SharedPreferences preferences() {
        Map<String, String> values = new HashMap<>();
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(preferences.getString(anyString(), any())).thenAnswer(call ->
                values.getOrDefault(call.getArgumentAt(0, String.class), call.getArgumentAt(1, String.class)));
        when(preferences.edit()).thenAnswer(call -> {
            Map<String, String> pending = new HashMap<>();
            SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
            when(editor.putString(anyString(), any())).thenAnswer(write -> {
                pending.put(write.getArgumentAt(0, String.class), write.getArgumentAt(1, String.class));
                return editor;
            });
            when(editor.remove(anyString())).thenAnswer(remove -> {
                pending.put(remove.getArgumentAt(0, String.class), null);
                return editor;
            });
            doAnswer(apply -> {
                pending.forEach((key, value) -> { if (value == null) values.remove(key); else values.put(key, value); });
                return null;
            }).when(editor).apply();
            return editor;
        });
        return preferences;
    }

    private static String front(String user) throws Exception {
        return Base64.getEncoder().encodeToString(new JSONObject().put("uid", user)
                .put("ate", 4102444800000L).put("up", new JSONObject().put("claim", user))
                .toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test public void readsPublishedPreferencesBeforeBuildAndIsolatesStaticState() throws Exception {
        SharedPreferences preferences = preferences();
        preferences.edit().putString("supertokens-android-fronttoken-key", front("seeded")).apply();
        preferences.edit().putString("st-storage-item-st-last-access-token-update", "1").apply();
        Context context = context(preferences);
        try (Legacy054Harness first = new Legacy054Harness(context);
             Legacy054Harness second = new Legacy054Harness(context)) {
            assertNotSame(SuperTokens.class, first.sdkClass());
            assertNotSame(first.sdkClass(), second.sdkClass());
            try {
                first.sdkClass().getClassLoader().loadClass("com.supertokens.session.Legacy054Harness");
                fail("Child loader must not fall back to candidate/test classes");
            } catch (ClassNotFoundException expected) {
                // The harness exists only in the parent loader.
            }
            assertEquals(false, first.invoke("isInitialized"));
            assertEquals("seeded", first.invoke("getUserId"));
            first.build(server.url("/").toString(), "header");
            assertEquals(true, first.invoke("isInitialized"));
            assertEquals(false, second.invoke("isInitialized"));
            assertEquals("seeded", second.invoke("getUserId"));
            Map<?, ?> urls = (Map<?, ?>) first.invoke("normalizeUrls",
                    new Class<?>[]{String.class, String.class}, "https://example.com/ignored", "/custom/");
            assertEquals("https://example.com/custom/session/refresh", urls.get("refresh"));
            assertEquals("https://example.com/custom/signout", urls.get("signOut"));
        }
    }

    @Test public void loginRefreshAndReopenPreserveOnlyExplicitlySharedPreferences() throws Exception {
        Context context = context(preferences());
        Class<?> previous;
        try (Legacy054Harness legacy = new Legacy054Harness(context)) {
            previous = legacy.sdkClass();
            legacy.build(server.url("/").toString(), "header");
            assertEquals(false, legacy.invoke("doesSessionExist"));
            server.enqueue(session("login", "access-1", "refresh-1"));
            Map<String, Object> response = legacy.request(server.url("/login").toString());
            assertEquals(200, response.get("status"));
            assertEquals("ok", response.get("body"));
            RecordedRequest login = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(login);
            assertNull(login.getHeader("Authorization"));
            assertEquals("login", legacy.invoke("getUserId"));
            assertEquals("access-1", legacy.invoke("getAccessToken"));
            server.enqueue(session("rotated", "access-2", "refresh-2"));
            assertEquals(true, legacy.invoke("refresh"));
            RecordedRequest refresh = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(refresh);
            assertEquals("/auth/session/refresh", refresh.getPath());
            assertEquals("POST", refresh.getMethod());
            assertEquals("Bearer refresh-1", refresh.getHeader("Authorization"));
            assertEquals("rotated", legacy.invoke("getUserId"));
            assertEquals("access-2", legacy.invoke("getAccessToken"));
        }
        try (Legacy054Harness reopened = new Legacy054Harness(context);
             Legacy054Harness empty = new Legacy054Harness(context(preferences()))) {
            assertNotSame(previous, reopened.sdkClass());
            assertEquals(false, reopened.invoke("isInitialized"));
            assertEquals("rotated", reopened.invoke("getUserId"));
            reopened.build(server.url("/").toString(), "header");
            assertEquals("access-2", reopened.invoke("getAccessToken"));
            assertEquals(false, empty.invoke("doesSessionExist"));
        }
    }

    private static MockResponse session(String user, String access, String refresh) throws Exception {
        return new MockResponse().setBody("ok").addHeader("front-token", front(user))
                .addHeader("st-access-token", access).addHeader("st-refresh-token", refresh)
                .addHeader("anti-csrf", "csrf");
    }
}
