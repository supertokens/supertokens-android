package com.example.example;

import android.content.Context;
import android.content.SharedPreferences;
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder;
import com.supertokens.session.EventHandler;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensHttpURLConnection;
import com.supertokens.session.SuperTokensInterceptor;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(Parameterized.class)
public class SignOutLiveTest {
    @Parameterized.Parameters(name = "{0}, urlConnection={1}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][]{{"header", false}, {"header", true}, {"cookie", false}, {"cookie", true}});
    }

    private final String method;
    private final boolean urlConnection;
    private LiveFixture fixture;

    public SignOutLiveTest(String method, boolean urlConnection) {
        this.method = method;
        this.urlConnection = urlConnection;
    }

    @BeforeClass public static void start() { com.example.TestUtils.beforeAll(); }
    @AfterClass public static void stop() { com.example.TestUtils.afterAll(); }
    @Before public void setup() throws Exception { fixture = new LiveFixture(method, urlConnection); }
    @After public void cleanup() { if (fixture != null) fixture.close(); }

    @Test public void signOutWithoutSession() throws Exception {
        SuperTokens.signOut(fixture.context);
        assertFalse(SuperTokens.doesSessionExist(fixture.context));
        assertEquals(Collections.singletonList(EventHandler.EventType.SIGN_OUT), fixture.events);
    }

    @Test public void signOutWithSession() throws Exception {
        assertEquals(200, fixture.login("sign-out"));
        fixture.events.clear();
        SuperTokens.signOut(fixture.context);
        assertFalse(SuperTokens.doesSessionExist(fixture.context));
        assertEquals(Collections.singletonList(EventHandler.EventType.SIGN_OUT), fixture.events);
    }

    @Test public void signOutAfterAccessExpires() throws Exception {
        assertEquals(200, fixture.login("sign-out-expired"));
        fixture.events.clear();
        int before = com.example.TestUtils.getRefreshTokenCounter();
        Thread.sleep(5000);
        SuperTokens.signOut(fixture.context);
        assertFalse(SuperTokens.doesSessionExist(fixture.context));
        assertEquals(before + 1, com.example.TestUtils.getRefreshTokenCounter());
        assertTrue(fixture.events.contains(EventHandler.EventType.REFRESH_SESSION));
        assertEquals(EventHandler.EventType.SIGN_OUT, fixture.events.get(fixture.events.size() - 1));
    }
}

class LiveFixture implements AutoCloseable {
    final Context context;
    final CookieHandler previous;
    final CookieManager cookies;
    final OkHttpClient client;
    final boolean urlConnection;
    final List<EventHandler.EventType> events = Collections.synchronizedList(new ArrayList<>());

    LiveFixture(String method, boolean urlConnection) throws java.net.MalformedURLException {
        this(method, urlConnection, null);
    }

    LiveFixture(String method, boolean urlConnection, com.supertokens.session.CustomHeaderProvider provider)
            throws java.net.MalformedURLException {
        com.example.TestUtils.beforeEach();
        com.example.TestUtils.startST(3);
        this.urlConnection = urlConnection;
        context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        Map<String, SharedPreferences> preferences = new ConcurrentHashMap<>();
        when(context.getSharedPreferences(anyString(), anyInt())).thenAnswer(call ->
                preferences.computeIfAbsent((String) call.getArguments()[0], key -> new SPMockBuilder().createSharedPreferences()));
        previous = CookieHandler.getDefault();
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cookies);
        try {
            new SuperTokens.Builder(context, Constants.apiDomain).tokenTransferMethod(method).customHeaderProvider(provider).eventHandler(events::add).build();
        } catch (java.net.MalformedURLException | RuntimeException | Error error) {
            CookieHandler.setDefault(previous);
            SuperTokens.resetForTests();
            throw error;
        }
        client = new OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).cookieJar(new CookieJar() {
            @Override public void saveFromResponse(HttpUrl url, List<Cookie> values) {
                List<String> headers = new ArrayList<>();
                for (Cookie cookie : values) headers.add(cookie.toString());
                try { cookies.put(url.uri(), Collections.singletonMap("Set-Cookie", headers)); }
                catch (Exception error) { throw new AssertionError(error); }
            }
            @Override public List<Cookie> loadForRequest(HttpUrl url) {
                List<Cookie> result = new ArrayList<>();
                try {
                    for (List<String> values : cookies.get(url.uri(), Collections.emptyMap()).values()) {
                        for (String value : values) for (String part : value.split(";")) {
                            Cookie cookie = Cookie.parse(url, part.trim());
                            if (cookie != null) result.add(cookie);
                        }
                    }
                } catch (Exception error) { throw new AssertionError(error); }
                return result;
            }
        }).addInterceptor(new SuperTokensInterceptor()).build();
    }

    int login(String user) throws Exception { return request("/login", "{\"userId\":\"" + user + "\"}"); }

    int request(String path, String json) throws Exception {
        if (urlConnection) {
            HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(new URL(Constants.apiDomain + path), con -> {
                con.setConnectTimeout(15000);
                con.setReadTimeout(15000);
                if (json != null) {
                    con.setRequestMethod("POST"); con.setDoOutput(true);
                    con.setRequestProperty("Content-Type", "application/json");
                    try (java.io.OutputStream out = con.getOutputStream()) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
                }
            });
            try { return connection.getResponseCode(); } finally { connection.disconnect(); }
        }
        Request.Builder request = new Request.Builder().url(Constants.apiDomain + path);
        if (json != null) request.post(RequestBody.create(MediaType.parse("application/json"), json));
        try (Response response = client.newCall(request.build()).execute()) { return response.code(); }
    }

    @Override public void close() {
        SuperTokens.resetForTests();
        CookieHandler.setDefault(previous);
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
    }
}
