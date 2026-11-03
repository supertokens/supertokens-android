package com.example.example;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.supertokens.session.FrontToken;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensHttpURLConnection;
import com.supertokens.session.SuperTokensInterceptor;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import com.franmontiel.persistentcookiejar.PersistentCookieJar;
import com.franmontiel.persistentcookiejar.cache.SetCookieCache;
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CookieFlowInstrumentedTest {
    private final String base = "http://10.0.2.2:18082";

    @Test public void okhttpCookieFlow() throws Exception { flow(true); }
    @Test public void urlConnectionCookieFlow() throws Exception { flow(false); }

    private void flow(boolean okhttp) throws Exception {
        Assume.assumeTrue("1".equals(InstrumentationRegistry.getArguments().getString("deviceCookie")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CookieHandler previous = CookieHandler.getDefault();
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cookies);
        PersistentCookieJar okhttpCookies = new PersistentCookieJar(new SetCookieCache(), new SharedPrefsCookiePersistor(context));
        okhttpCookies.clear();
        OkHttpClient client = new OkHttpClient.Builder().cookieJar(okhttpCookies)
                .addInterceptor(new SuperTokensInterceptor()).readTimeout(5, TimeUnit.SECONDS).build();
        try {
            context.getSharedPreferences("supertokens-android-shared-preferences", Context.MODE_PRIVATE).edit().clear().commit();
            SuperTokens.resetForTests();
            new SuperTokens.Builder(context, base).tokenTransferMethod("cookie").build();
            assertEquals(200, raw("/reset-observations", true));
            assertEquals(200, request(okhttp, client, "/login", true));
            assertTrue(SuperTokens.doesSessionExist(context));
            assertEquals(200, request(okhttp, client, "/protected", false));
            long expires = FrontToken.getToken(context).getLong("ate");
            long remaining = expires - System.currentTimeMillis();
            assertTrue("Core must use a short, genuine access-token TTL", remaining > 0 && remaining < 30000);
            while (System.currentTimeMillis() <= expires + 500) Thread.sleep(100);
            int afterExpiry = request(okhttp, client, "/protected", false);
            assertEquals("redacted observations: " + readRaw("/observations"), 200, afterExpiry);
            assertEquals(200, request(okhttp, client, "/auth/signout", true));
            assertFalse(SuperTokens.doesSessionExist(context));
            JSONArray seen = new JSONArray(readRaw("/observations"));
            assertEquals(6, seen.length());
            assertRequest(seen.getJSONObject(0), "/login", 200, false, false, false);
            assertRequest(seen.getJSONObject(1), "/protected", 200, true, false, true);
            assertRequest(seen.getJSONObject(2), "/protected", 401, true, false, true);
            assertRequest(seen.getJSONObject(3), "/auth/session/refresh", 200, true, true, true);
            assertRequest(seen.getJSONObject(4), "/protected", 200, true, false, true);
            assertRequest(seen.getJSONObject(5), "/auth/signout", 200, true, false, true);
        } finally {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
            okhttpCookies.clear();
            CookieHandler.setDefault(previous);
            SuperTokens.resetForTests();
            context.getSharedPreferences("supertokens-android-shared-preferences", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    private void assertRequest(JSONObject item, String path, int status, boolean access, boolean refresh, boolean csrf) throws Exception {
        assertEquals(path, item.getString("path"));
        assertEquals(status, item.getInt("status"));
        assertEquals(access, item.getBoolean("accessCookie"));
        assertEquals(refresh, item.getBoolean("refreshCookie"));
        assertEquals(csrf, item.getBoolean("csrf"));
    }

    private int request(boolean okhttp, OkHttpClient client, String path, boolean post) throws Exception {
        if (okhttp) {
            Request.Builder request = new Request.Builder().url(base + path);
            if (post) request.post(RequestBody.create(null, new byte[0]));
            try (Response response = client.newCall(request.build()).execute()) { return response.code(); }
        }
        HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(new URL(base + path), con -> {
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            if (post) con.setRequestMethod("POST");
        });
        try { return connection.getResponseCode(); } finally { connection.disconnect(); }
    }

    private int raw(String path, boolean post) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(base + path).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);
        if (post) con.setRequestMethod("POST");
        try { return con.getResponseCode(); } finally { con.disconnect(); }
    }

    private String readRaw(String path) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(base + path).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);
        try (java.util.Scanner scanner = new java.util.Scanner(con.getInputStream(), "UTF-8").useDelimiter("\\A")) {
            return scanner.hasNext() ? scanner.next() : "";
        } finally { con.disconnect(); }
    }
}
