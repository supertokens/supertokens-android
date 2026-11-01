package com.supertokens.session;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

/** Runs real interceptor login/refresh/sign-out with the release JAR and historical dependencies. */
public final class HistoricalSessionProbe {
    public static void main(String[] args) throws Exception {
        TestPreferences preferences = new TestPreferences();
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return preferences.preferences;
            }
        };
        String first = access("before");
        String rotated = access("after");
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger protectedRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            if (path.equals("/login")) {
                exchange.getResponseHeaders().add("st-access-token", first);
                exchange.getResponseHeaders().add("st-refresh-token", "refresh-before");
                exchange.getResponseHeaders().add("front-token", front());
            } else if (path.endsWith("/refresh")) {
                refreshes.incrementAndGet();
                if (!"Bearer refresh-before".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    status = 403;
                }
                exchange.getResponseHeaders().add("st-access-token", rotated);
                exchange.getResponseHeaders().add("st-refresh-token", "refresh-after");
                exchange.getResponseHeaders().add("front-token", front());
            } else if (path.equals("/protected")) {
                protectedRequests.incrementAndGet();
                status = ("Bearer " + rotated).equals(exchange.getRequestHeaders().getFirst("Authorization")) ? 200 : 401;
            } else if (path.endsWith("/signout")) {
                exchange.getResponseHeaders().add("front-token", "remove");
            }
            byte[] body = "{\"status\":\"OK\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().close();
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(new SuperTokensInterceptor()).build();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            new SuperTokens.Builder(context, base).tokenTransferMethod("header").build();
            Object applicationTag = new Object();
            try (Response response = client.newCall(new Request.Builder().url(base + "/login")
                    .tag(applicationTag).build()).execute()) {
                check(response.code() == 200, "login response");
                check(response.request().tag() == applicationTag, "application tag retained");
            }
            check(SuperTokens.doesSessionExist(context), "login adopted");
            try (Response response = client.newCall(new Request.Builder().url(base + "/protected").build()).execute()) {
                check(response.code() == 200, "refresh retry response");
            }
            check(refreshes.get() == 1 && protectedRequests.get() == 2, "one refresh and retry");
            check(rotated.equals(SuperTokens.getAccessToken(context)), "rotated token adopted");
            SuperTokens.signOut(context);
            check(!SuperTokens.doesSessionExist(context), "signed out");
            System.out.println("PASS release login/adoption/401/refresh/retry/sign-out/application-tag");
        } finally {
            server.stop(0);
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
            SuperTokens.resetForTests();
        }
    }

    private static String access(String claim) throws Exception {
        return encode("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"version\":\"5\"}", true) + "."
                + encode(new JSONObject().put("sessionHandle", "historical").put("sub", "user")
                .put("rsub", "recipe-user").put("tId", "public").put("iat", 1700000000L)
                .put("exp", 4102444800L).put("claim", claim).toString(), true) + ".c2lnbmF0dXJl";
    }

    private static String front() {
        return encode("{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{}}", false);
    }

    private static String encode(String value, boolean url) {
        return (url ? Base64.getUrlEncoder().withoutPadding() : Base64.getEncoder())
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
