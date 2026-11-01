package com.supertokens.session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.junit.Test;
import static org.junit.Assert.*;

public class LargeUnauthorisedBodyTest extends SessionTestFixture {
    @Test public void fullTwoMiBBodySurvivesNonRetryOutcomesAndClosesOnceBothTransports() throws Exception {
        char[] chars = new char[2 * 1024 * 1024];
        Arrays.fill(chars, 'x');
        String body = new String(chars);
        AtomicInteger closes = new AtomicInteger();
        client = client.newBuilder().addNetworkInterceptor(chain -> {
            Response response = chain.proceed(chain.request());
            if (!chain.request().url().encodedPath().equals("/large") || response.code() != 401) return response;
            ResponseBody original = response.body();
            BufferedSource source = Okio.buffer(new ForwardingSource(original.source()) {
                @Override public void close() throws IOException { closes.incrementAndGet(); super.close(); }
            });
            return response.newBuilder().body(new ResponseBody() {
                @Override public MediaType contentType() { return original.contentType(); }
                @Override public long contentLength() { return original.contentLength(); }
                @Override public BufferedSource source() { return source; }
            }).build();
        }).build();
        for (boolean url : new boolean[]{false, true}) for (int outcome = 0; outcome < 4; outcome++) {
            SessionSnapshot before = install("large", "before");
            SessionTokens refreshed = tokens("large", "after");
            int mode = outcome;
            AtomicInteger protectedCalls = new AtomicInteger();
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().equals("/auth/session/refresh")) {
                        if (mode == 0) return new MockResponse().setResponseCode(401).addHeader("front-token", "remove");
                        if (mode == 1) {
                            try { install("replacement", "after"); } catch (Exception e) { throw new AssertionError(e); }
                            return rotation(refreshed);
                        }
                        if (mode == 2) return new MockResponse().setBody("<html>invalid refresh</html>");
                        return rotation(refreshed);
                    }
                    if (protectedCalls.getAndIncrement() == 0) return new MockResponse().setResponseCode(401)
                            .setHeader("Content-Type", "text/plain; charset=utf-8").setBody(body);
                    return new MockResponse().setBody("retried");
                }
            });
            int closeBefore = closes.get(), requests = server.getRequestCount();
            try {
                if (url) {
                    HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(server.url("/large").url(), null);
                    try {
                        assertEquals(mode == 3 ? 200 : 401, connection.getResponseCode());
                        if (mode != 3) assertEquals("text/plain; charset=utf-8", connection.getContentType());
                        try (InputStream stream = mode == 3 ? connection.getInputStream() : connection.getErrorStream()) {
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            byte[] chunk = new byte[8192];
                            int count;
                            while ((count = stream.read(chunk)) != -1) bytes.write(chunk, 0, count);
                            assertEquals(mode == 3 ? "retried" : body, bytes.toString("UTF-8"));
                        }
                    } finally { connection.disconnect(); }
                } else {
                    try (Response response = client.newCall(new Request.Builder().url(server.url("/large")).build()).execute()) {
                        assertEquals(mode == 3 ? 200 : 401, response.code());
                        if (mode != 3) assertEquals("text/plain; charset=utf-8", response.body().contentType().toString());
                        assertEquals(mode == 3 ? "retried" : body, response.body().string());
                    }
                }
                assertNotEquals("Malformed refresh must fail", 2, mode);
            } catch (IOException expected) { assertEquals(2, mode); }
            if (!url) assertEquals(closeBefore + 1, closes.get());
            assertEquals(requests + (mode == 3 ? 3 : 2), server.getRequestCount());
            if (mode == 0) assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
            if (mode == 1) assertEquals("replacement", SuperTokens.getSessionSnapshot(context).getSessionHandle());
            if (mode == 2) assertSame(before, SuperTokens.getSessionSnapshot(context));
            if (mode == 3) assertSame(before.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
        }
    }
}
