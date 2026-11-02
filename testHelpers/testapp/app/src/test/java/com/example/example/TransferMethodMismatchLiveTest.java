package com.example.example;

import android.content.Context;
import android.content.SharedPreferences;
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensHttpURLConnection;
import com.supertokens.session.SuperTokensInterceptor;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import retrofit2.Retrofit;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(Parameterized.class)
public class TransferMethodMismatchLiveTest {
    enum Transport { OKHTTP, RETROFIT, URLCONNECTION }
    @Parameterized.Parameters(name = "sdk={0}, server={1}, transport={2}")
    public static Collection<Object[]> cases() {
        List<Object[]> result = new ArrayList<>();
        for (Transport transport : Transport.values()) {
            result.add(new Object[]{"header", "cookie", transport});
            result.add(new Object[]{"cookie", "header", transport});
        }
        return result;
    }
    public interface Api {
        @POST("login") retrofit2.Call<ResponseBody> login(@Body RequestBody body);
        @GET("/") retrofit2.Call<ResponseBody> protectedRequest();
    }
    private final String sdkMethod;
    private final String serverMethod;
    private final Transport transport;
    private Context context;
    private CookieHandler previous;
    private boolean cookieHandlerInstalled;
    private boolean overrideInstalled;
    private OkHttpClient client;
    private CookieManager manager;

    public TransferMethodMismatchLiveTest(String sdkMethod, String serverMethod, Transport transport) {
        this.sdkMethod = sdkMethod; this.serverMethod = serverMethod; this.transport = transport;
    }
    @BeforeClass public static void startFixture() { com.example.TestUtils.beforeAll(); }
    @AfterClass public static void stopFixture() { com.example.TestUtils.afterAll(); }

    @Before public void setup() throws Exception {
        com.example.TestUtils.beforeEach();
        com.example.TestUtils.startST(3);
        int status = setServerMethod("\"" + serverMethod + "\"");
        Assume.assumeTrue("CAPABILITY_EXCLUSION: node-transfer-method-override (Node SDK < 14)", status != 422);
        assertEquals(200, status);
        overrideInstalled = true;
        context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        Map<String, SharedPreferences> preferences = new HashMap<>();
        when(context.getSharedPreferences(anyString(), anyInt())).thenAnswer(call ->
                preferences.computeIfAbsent((String) call.getArguments()[0], key -> new SPMockBuilder().createSharedPreferences()));
        previous = CookieHandler.getDefault();
        manager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(manager);
        cookieHandlerInstalled = true;
        client = new OkHttpClient.Builder().cookieJar(new CookieJar() {
            @Override public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                List<String> values = new ArrayList<>();
                for (Cookie cookie : cookies) values.add(cookie.toString());
                try { manager.put(url.uri(), Collections.singletonMap("Set-Cookie", values)); }
                catch (Exception error) { throw new AssertionError(error); }
            }
            @Override public List<Cookie> loadForRequest(HttpUrl url) {
                List<Cookie> result = new ArrayList<>();
                try {
                    for (List<String> values : manager.get(url.uri(), Collections.emptyMap()).values()) {
                        for (String value : values) for (String part : value.split(";")) {
                            Cookie cookie = Cookie.parse(url, part.trim());
                            if (cookie != null) result.add(cookie);
                        }
                    }
                } catch (Exception error) { throw new AssertionError(error); }
                return result;
            }
        }).addInterceptor(new SuperTokensInterceptor()).build();
        new SuperTokens.Builder(context, Constants.apiDomain).tokenTransferMethod(sdkMethod).build();
    }

    @After public void cleanup() throws Exception {
        try { if (overrideInstalled) assertEquals(200, setServerMethod("null")); }
        finally {
            SuperTokens.resetForTests();
            if (cookieHandlerInstalled) CookieHandler.setDefault(previous);
            if (client != null) { client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow(); }
        }
    }

    @Test public void loginRefreshAndSignOut() throws Exception {
        assertEquals(200, call(true));
        assertTrue(SuperTokens.doesSessionExist(context));
        boolean hasRefreshCookie = false;
        for (HttpCookie cookie : manager.getCookieStore().getCookies()) {
            if (cookie.getName().equals("sRefreshToken")) hasRefreshCookie = true;
        }
        assertEquals("server must use the requested transfer method", serverMethod.equals("cookie"), hasRefreshCookie);
        if (serverMethod.equals("header")) assertNotNull(SuperTokens.getAccessToken(context));
        assertEquals(200, call(false));
        int before = com.example.TestUtils.getRefreshTokenCounter();
        Thread.sleep(5000);
        assertEquals(200, call(false));
        assertEquals(before + 1, com.example.TestUtils.getRefreshTokenCounter());
        SuperTokens.signOut(context);
        assertFalse(SuperTokens.doesSessionExist(context));
    }

    private int call(boolean login) throws Exception {
        String base = Constants.apiDomain;
        String json = "{\"userId\":\"transfer-mismatch\"}";
        if (transport == Transport.URLCONNECTION) {
            HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(new URL(base + (login ? "/login" : "/")), con -> {
                if (login) {
                    con.setRequestMethod("POST"); con.setDoOutput(true);
                    con.setRequestProperty("Content-Type", "application/json");
                    try (java.io.OutputStream out = con.getOutputStream()) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
                }
            });
            try { return connection.getResponseCode(); } finally { connection.disconnect(); }
        }
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), json);
        if (transport == Transport.RETROFIT) {
            Api api = new Retrofit.Builder().baseUrl(base.endsWith("/") ? base : base + "/").client(client).build().create(Api.class);
            retrofit2.Response<ResponseBody> response = (login ? api.login(body) : api.protectedRequest()).execute();
            if (response.body() != null) response.body().close();
            if (response.errorBody() != null) response.errorBody().close();
            return response.code();
        }
        Request.Builder request = new Request.Builder().url(base + (login ? "/login" : "/"));
        if (login) request.post(body);
        try (Response response = client.newCall(request.build()).execute()) { return response.code(); }
    }

    private static int setServerMethod(String method) throws Exception {
        Request request = new Request.Builder().url(Constants.apiDomain + "/test/transfer-method")
                .header("x-st-test-run-id", System.getenv("ST_TEST_RUN_ID"))
                .post(RequestBody.create(MediaType.parse("application/json"), "{\"method\":" + method + "}")).build();
        OkHttpClient control = new OkHttpClient();
        try (Response response = control.newCall(request).execute()) { return response.code(); }
        finally { control.connectionPool().evictAll(); control.dispatcher().executorService().shutdownNow(); }
    }
}
