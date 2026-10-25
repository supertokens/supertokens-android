package com.supertokens.session;

import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public abstract class SessionTestFixture {
    protected Context context;
    protected MockWebServer server;
    protected OkHttpClient client;

    @Before public void setUpSession() throws Exception {
        SuperTokens.resetForTests();
        context = mock(Context.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences());
        server = new MockWebServer();
        server.start();
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header").build();
        client = new OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS)
                .addInterceptor(new SuperTokensInterceptor()).build();
    }

    @After public void tearDownSession() throws Exception {
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
        server.shutdown();
        SuperTokens.resetForTests();
    }

    // Stage edits until commit so readers cannot observe a partially written tuple.
    static SharedPreferences preferences() {
        Map<String, Object> values = new HashMap<>();
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    synchronized (values) {
                        String name = method.getName();
                        if (name.equals("getAll")) return new HashMap<>(values);
                        if (name.equals("contains")) return values.containsKey(args[0]);
                        if (name.startsWith("get")) return values.getOrDefault(args[0], args[1]);
                        if (name.equals("edit")) {
                            Map<String, Object> pending = new HashMap<>();
                            boolean[] clear = {false};
                            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                    new Class<?>[]{SharedPreferences.Editor.class}, (editor, operation, arguments) -> {
                                        synchronized (values) {
                                            String action = operation.getName();
                                            if (action.startsWith("put")) { pending.put((String) arguments[0], arguments[1]); return editor; }
                                            if (action.equals("remove")) { pending.put((String) arguments[0], null); return editor; }
                                            if (action.equals("clear")) { clear[0] = true; return editor; }
                                            if (action.equals("commit") || action.equals("apply")) {
                                                if (clear[0]) values.clear();
                                                pending.forEach((key, value) -> { if (value == null) values.remove(key); else values.put(key, value); });
                                                return action.equals("commit") ? true : null;
                                            }
                                            throw new UnsupportedOperationException(action);
                                        }
                                    });
                        }
                        throw new UnsupportedOperationException(name);
                    }
                });
    }

    static SessionTokens tokens(String handle, String claim) throws Exception {
        JSONObject payload = new JSONObject().put("sessionHandle", handle).put("sub", "primary-user")
                .put("rsub", "recipe-user").put("tId", "public").put("exp", 4102444800L).put("iat", 1700000000L).put("claim", claim);
        String access = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"version\":\"5\"}", true) + "."
                + encode(payload.toString(), true) + ".c2lnbmF0dXJl";
        String front = encode(new JSONObject().put("uid", "primary-user").put("ate", 4102444800000L)
                .put("up", payload).toString(), false);
        return new SessionTokens(access, handle + "-refresh-" + claim, front, "csrf-" + claim);
    }

    private static String encode(String value, boolean url) {
        return (url ? Base64.getUrlEncoder().withoutPadding() : Base64.getEncoder())
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    protected SessionSnapshot install(String handle, String claim) throws Exception {
        SessionCommitResult result = SuperTokens.installSession(context, SuperTokens.getSessionSnapshot(context).getOwner(), tokens(handle, claim));
        assertEquals(SessionCommitResult.Status.SUCCESS, result.getStatus());
        return result.getSnapshot();
    }

    static MockResponse promotion(SessionTokens tokens) {
        return new MockResponse().setBody("old-body").addHeader("st-access-token", tokens.getAccessToken())
                .addHeader("front-token", tokens.getFrontToken());
    }

    static MockResponse rotation(SessionTokens tokens) {
        return promotion(tokens).addHeader("st-refresh-token", tokens.getRefreshToken());
    }

    protected int request(boolean urlConnection, SessionRequestTag tag, String path) throws Exception {
        if (urlConnection) {
            java.net.HttpURLConnection connection = tag == null
                    ? SuperTokensHttpURLConnection.newRequest(server.url(path).url(), con -> { con.setConnectTimeout(8000); con.setReadTimeout(8000); })
                    : SuperTokensHttpURLConnection.newRequest(server.url(path).url(), con -> { con.setConnectTimeout(8000); con.setReadTimeout(8000); }, tag);
            try { return connection.getResponseCode(); } finally { connection.disconnect(); }
        }
        return request(client, tag, path);
    }

    protected int request(OkHttpClient transport, SessionRequestTag tag, String path) throws Exception {
        Request.Builder request = new Request.Builder().url(server.url(path));
        if (tag != null) request.tag(SessionRequestTag.class, tag);
        try (Response response = transport.newCall(request.build()).execute()) { return response.code(); }
    }
}
