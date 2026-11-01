package com.supertokens.session;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/** Standalone probe; deliberately outside Android/JUnit source sets. */
public final class RuntimeCompatibilityProbe {
    public static void main(String[] args) throws Exception {
        boolean typedTags = Boolean.parseBoolean(args[0]);
        boolean ownedRefresh = Boolean.parseBoolean(args[1]);
        Request plain = new Request.Builder().url("http://localhost/").build();
        check(OkHttpSessionTags.get(plain) == null, "untagged request");
        Object applicationTag = new Object();
        Request tagged = plain.newBuilder().tag(applicationTag).build();
        check(OkHttpSessionTags.get(tagged) == null, "arbitrary application tag");
        check(tagged.newBuilder().build().tag() == applicationTag, "application tag preserved");
        SessionRequestTag sessionTag = new SessionRequestTag(null, null);
        if (typedTags) {
            ModernTags.checkTyped(tagged, sessionTag, applicationTag);
        } else {
            check(OkHttpSessionTags.get(plain.newBuilder().tag(sessionTag).build()) == sessionTag,
                    "historical session tag");
        }
        new SuperTokensInterceptor();
        OkHttpClient base = new OkHttpClient();
        try {
            if (!ownedRefresh) {
                try {
                    new SuperTokensInterceptor(base);
                    throw new AssertionError("unsupported owned refresh accepted");
                } catch (IllegalArgumentException expected) {
                    check(expected.getMessage().contains("3.14"), "clear capability error");
                }
            } else {
                SuperTokensInterceptor interceptor = new SuperTokensInterceptor(base);
                Field field = SuperTokensInterceptor.class.getDeclaredField("refreshClient");
                field.setAccessible(true);
                OkHttpClient client = (OkHttpClient) field.get(interceptor);
                try {
                    check(!client.retryOnConnectionFailure(), "connection retries disabled");
                    check(!client.followRedirects() && !client.followSslRedirects(), "redirects disabled");
                    check(client.interceptors().isEmpty() && client.networkInterceptors().isEmpty(),
                            "host interceptors removed");
                    for (int status : new int[]{408, 503, 307, 401}) checkSingleExchange(client, status);
                } finally {
                    close(client);
                }
            }
        } finally {
            close(base);
        }
        System.out.println("PASS typedTags=" + typedTags + " ownedRefresh=" + ownedRefresh);
    }

    private static void checkSingleExchange(OkHttpClient client, final int status) throws Exception {
        final AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int count = requests.incrementAndGet();
            exchange.getRequestBody().close();
            exchange.getResponseHeaders().add("Retry-After", "0");
            exchange.getResponseHeaders().add("Location", "/follow-up");
            exchange.sendResponseHeaders(count == 1 ? status : 200, -1);
            exchange.close();
        });
        server.start();
        try {
            // Exercise the runtime's one-shot contract on the actual derived SDK client.
            RequestBody body = new RequestBody() {
                @Override public MediaType contentType() { return MediaType.parse("application/x-www-form-urlencoded"); }
                @Override public long contentLength() { return 0; }
                @Override public boolean isOneShot() { return true; }
                @Override public void writeTo(BufferedSink sink) throws IOException {}
            };
            Request request = new Request.Builder().url("http://127.0.0.1:" + server.getAddress().getPort())
                    .post(body).build();
            try (Response response = client.newCall(request).execute()) {
                check(response.code() == status, "unexpected follow-up response for " + status);
            }
            check(requests.get() == 1, "refresh replayed for " + status);
        } finally {
            server.stop(0);
        }
    }

    private static void close(OkHttpClient client) {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ModernTags {
        static void checkTyped(Request request, SessionRequestTag session, Object applicationTag) {
            Request typed = request.newBuilder().tag(SessionRequestTag.class, session).build();
            check(OkHttpSessionTags.get(typed) == session, "typed session tag recognized");
            check(typed.tag() == applicationTag, "typed and application tags coexist");
            check(OkHttpSessionTags.get(typed.newBuilder().build()) == session, "typed tag preserved");
        }
    }
}
