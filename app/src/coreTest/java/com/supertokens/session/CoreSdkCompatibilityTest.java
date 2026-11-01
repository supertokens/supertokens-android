package com.supertokens.session;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real current-Core legacy header/cookie transport behavior, not synthetic token fixtures. */
public class CoreSdkCompatibilityTest extends CoreSessionProtocolFixture {
    @Test public void urlConnectionHeaderLoginRefreshProtectedAndSignout() throws Exception { urlCycle(false); }
    @Test public void urlConnectionCookieLoginRefreshProtectedAndSignout() throws Exception { urlCycle(true); }
    @Test public void okHttpHeaderLoginProtectedAndSignout() throws Exception { okCycle(false); }
    @Test public void okHttpCookieLoginProtectedAndSignout() throws Exception { okCycle(true); }

    private void urlCycle(boolean cookie) throws Exception {
        CookieHandler previous = CookieHandler.getDefault();
        try {
            if (cookie) CookieHandler.setDefault(new CookieManager());
            assertTrue(new SuperTokens.Builder(context, backend()).tokenTransferMethod(cookie ? "cookie" : "header")
                    .reconfigure().isSuccess());
            byte[] body = ownerBody().getBytes(Charset.forName("UTF-8"));
            HttpURLConnection login = SuperTokensHttpURLConnection.newRequest(new URL(backend() + "/test/email-change-owner"), con -> {
                con.setRequestMethod("POST"); con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json");
                con.setConnectTimeout(10000); con.setReadTimeout(10000);
                con.getOutputStream().write(body); con.getOutputStream().close();
            });
            try { assertEquals(200, login.getResponseCode()); } finally { login.disconnect(); }
            SessionSnapshot installed = SuperTokens.getSessionSnapshot(context);
            assertEquals(SessionSnapshot.Status.AVAILABLE, installed.getStatus());
            assertEquals(cookie, installed.getTokens() == null);
            assertTrue("Actual platform refresh", SuperTokens.attemptRefreshingSession(context));
            assertSame(installed.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            HttpURLConnection protectedCall = SuperTokensHttpURLConnection.newRequest(new URL(backend() + "/test/protected"),
                    con -> { con.setConnectTimeout(10000); con.setReadTimeout(10000); });
            try { assertEquals(200, protectedCall.getResponseCode()); } finally { protectedCall.disconnect(); }
            SuperTokens.signOut(context);
            assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        } finally { CookieHandler.setDefault(previous); }
    }

    private void okCycle(boolean cookie) throws Exception {
        assertTrue(new SuperTokens.Builder(context, backend()).tokenTransferMethod(cookie ? "cookie" : "header")
                .reconfigure().isSuccess());
        CookieJar jar = new CookieJar() {
            final List<Cookie> stored = new ArrayList<>();
            @Override public synchronized void saveFromResponse(HttpUrl url, List<Cookie> values) {
                for (Cookie incoming : values) {
                    for (int i = stored.size() - 1; i >= 0; i--) {
                        Cookie old = stored.get(i);
                        if (old.name().equals(incoming.name()) && old.path().equals(incoming.path())
                                && old.domain().equals(incoming.domain())) stored.remove(i);
                    }
                    if (incoming.expiresAt() > System.currentTimeMillis()) stored.add(incoming);
                }
            }
            @Override public synchronized List<Cookie> loadForRequest(HttpUrl url) {
                List<Cookie> result = new ArrayList<>();
                for (Cookie value : stored) if (value.matches(url)) result.add(value);
                return result;
            }
        };
        OkHttpClient managed = new OkHttpClient.Builder().cookieJar(jar)
                .addInterceptor(new SuperTokensInterceptor()).build();
        try {
            RequestBody body = RequestBody.create(MediaType.get("application/json"), ownerBody());
            try (Response login = managed.newCall(new Request.Builder().url(backend() + "/test/email-change-owner").post(body).build()).execute()) {
                assertEquals(200, login.code());
            }
            assertEquals(SessionSnapshot.Status.AVAILABLE, SuperTokens.getSessionSnapshot(context).getStatus());
            assertEquals(cookie, SuperTokens.getSessionSnapshot(context).getTokens() == null);
            try (Response protectedCall = managed.newCall(new Request.Builder().url(backend() + "/test/protected").build()).execute()) {
                assertEquals(200, protectedCall.code());
            }
            try (Response logout = managed.newCall(new Request.Builder().url(backend() + "/auth/signout")
                    .post(RequestBody.create(MediaType.get("application/json"), "{}")).build()).execute()) {
                assertEquals(200, logout.code());
            }
            assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        } finally {
            managed.connectionPool().evictAll(); managed.dispatcher().executorService().shutdownNow();
        }
    }

    private static String ownerBody() throws Exception {
        return new JSONObject().put("email", "sdk-compat-" + UUID.randomUUID() + "@example.com")
                .put("recipe", "google").toString();
    }
}
