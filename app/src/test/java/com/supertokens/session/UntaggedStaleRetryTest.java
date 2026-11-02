package com.supertokens.session;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class UntaggedStaleRetryTest extends SessionTestFixture {
    @Test public void okhttpReturnsReadableRejectionAfterOwnerChangesDuringRefresh() throws Exception { duringRefresh(false, false); }
    @Test public void urlConnectionReturnsReadableRejectionAfterOwnerChangesDuringRefresh() throws Exception { duringRefresh(true, false); }
    @Test public void okhttpTaggedRefreshStillThrows() throws Exception { duringRefresh(false, true); }
    @Test public void urlConnectionTaggedRefreshStillThrows() throws Exception { duringRefresh(true, true); }

    @Test public void urlConnectionInitialCallbackDispatchFallsBackWithoutSdkCredentials() throws Exception {
        callbackDispatch(false, false);
    }

    @Test public void urlConnectionInitialTaggedCallbackDispatchThrows() throws Exception {
        callbackDispatch(false, true);
    }

    @Test public void urlConnectionRetryCallbackDispatchReturnsRetainedBody() throws Exception {
        callbackDispatch(true, false);
    }

    @Test public void urlConnectionTaggedRetryCallbackDispatchThrows() throws Exception {
        callbackDispatch(true, true);
    }

    private void callbackDispatch(boolean retry, boolean tagged) throws Exception {
        for (String operation : new String[]{"connect", "output", "code", "headers"}) {
            SessionSnapshot initial = install("A", "initial");
            SessionRequestTag tag = tagged ? SessionState.capture() : null;
            SessionTokens replacement = tokens("B", "replacement");
            int requests = server.getRequestCount();
            if (retry) {
                server.enqueue(new MockResponse().setResponseCode(401).setBody("readable rejection"));
                server.enqueue(rotation(tokens("A", "refreshed")));
            } else if (!tagged) {
                server.enqueue(rotation(tokens("C", "must-not-adopt")).setResponseCode(401)
                        .setBody("readable rejection"));
            }
            AtomicInteger callbacks = new AtomicInteger();
            try {
                String body = read(true, tag, connection -> {
                    if (callbacks.incrementAndGet() != (retry ? 2 : 1)) return;
                    assertTrue(SuperTokens.installSession(context, SessionState.snapshot().getOwner(), replacement).isSuccess());
                    switch (operation) {
                        case "connect": connection.connect(); break;
                        case "output":
                            connection.setDoOutput(true);
                            connection.getOutputStream().close();
                            break;
                        case "code": connection.getResponseCode(); break;
                        case "headers": connection.getHeaderFields(); break;
                        default: fail("Unknown dispatch operation");
                    }
                });
                assertFalse("Tagged dispatch must fail", tagged);
                assertEquals("readable rejection", body);
            } catch (StaleSessionException stale) { assertTrue(tagged); }
            assertEquals(retry ? 2 : 1, callbacks.get());
            assertEquals(replacement.getAccessToken(), SuperTokens.getAccessToken(context));
            int expected = retry ? 2 : tagged ? 0 : 1;
            assertEquals(requests + expected, server.getRequestCount());
            for (int i = 0; i < expected; i++) {
                RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
                assertNotNull(sent);
                if (retry) {
                    assertEquals("Bearer " + (i == 0 ? initial.getTokens().getAccessToken()
                            : initial.getTokens().getRefreshToken()), sent.getHeader("Authorization"));
                } else {
                    assertNoCredential(sent.getHeader("Authorization"));
                    assertNoCredential(sent.getHeader("anti-csrf"));
                }
            }
        }
    }

    private void assertNoCredential(String value) {
        assertTrue("Unexpected SDK credential: " + value, value == null || value.isEmpty());
    }

    @Test public void initialFallbackPreservesForeignAuthorizationBothTransports() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            for (String kind : new String[]{"Basic caller", "Bearer foreign", "managed"}) {
                SessionSnapshot initial = install("A", "initial");
                SessionTokens replacement = tokens("B", "replacement");
                String auth = kind.equals("managed") ? "Bearer " + initial.getTokens().getAccessToken() : kind;
                server.enqueue(rotation(tokens("C", "must-not-adopt")).setResponseCode(401)
                        .setBody("readable rejection"));
                if (url) {
                    assertEquals("readable rejection", read(true, null, connection -> {
                        connection.setRequestProperty("Authorization", auth);
                        assertTrue(SuperTokens.installSession(context, SessionState.snapshot().getOwner(), replacement).isSuccess());
                        connection.getResponseCode();
                    }));
                } else {
                    SuperTokensInterceptor interceptor = new SuperTokensInterceptor();
                    SessionTransport.beforeDispatchForTests = () -> assertTrue(SuperTokens.installSession(
                            context, SessionState.snapshot().getOwner(), replacement).isSuccess());
                    okhttp3.OkHttpClient.Builder builder = client.newBuilder();
                    builder.interceptors().clear();
                    client = builder.addInterceptor(interceptor).build();
                    try (Response response = client.newCall(new Request.Builder().url(server.url("/protected"))
                            .header("Authorization", auth).build()).execute()) {
                        assertEquals(401, response.code());
                        assertEquals("readable rejection", response.body().string());
                    } finally { SessionTransport.beforeDispatchForTests = null; }
                }
                RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
                assertNotNull(sent);
                if (kind.equals("managed")) assertNoCredential(sent.getHeader("Authorization"));
                else assertEquals(auth, sent.getHeader("Authorization"));
                assertNoCredential(sent.getHeader("anti-csrf"));
                assertEquals(replacement.getAccessToken(), SuperTokens.getAccessToken(context));
            }
        }
    }

    @Test public void callbackRuntimeFailureIsNotUnwrappedOrHidden() throws Exception {
        install("A", "initial");
        SessionTokens replacement = tokens("B", "replacement");
        IllegalStateException failure = new IllegalStateException(new java.io.IOException("caller failure"));
        try {
            read(true, null, connection -> {
                assertTrue(SuperTokens.installSession(context, SessionState.snapshot().getOwner(), replacement).isSuccess());
                throw failure;
            });
            fail("Caller failure must propagate");
        } catch (IllegalStateException expected) { assertSame(failure, expected); }
        assertEquals(0, server.getRequestCount());
    }

    @Test public void configurationRoundTripUsesCredentialFreeFallbackButTaggedStillThrows() throws Exception {
        for (boolean tagged : new boolean[]{false, true}) {
            SessionSnapshot initial = install("A", "initial");
            SessionRequestTag tag = tagged ? SessionState.capture() : null;
            int requests = server.getRequestCount();
            if (!tagged) server.enqueue(new MockResponse().setResponseCode(401).setBody("readable rejection"));
            try {
                String body = read(true, tag, connection -> {
                    assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                            .apiBasePath("/other-auth").tokenTransferMethod("header").reconfigure().isSuccess());
                    assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                            .tokenTransferMethod("header").reconfigure().isSuccess());
                    connection.connect();
                });
                assertFalse(tagged);
                assertEquals("readable rejection", body);
            } catch (StaleSessionException expected) { assertTrue(tagged); }
            assertEquals(initial.getConfigurationId(), SessionState.snapshot().getConfigurationId());
            assertNotSame(initial.getOwner(), SessionState.snapshot().getOwner());
            assertEquals(SessionSnapshot.Status.ABSENT, SessionState.snapshot().getStatus());
            assertEquals(requests + (tagged ? 0 : 1), server.getRequestCount());
            if (!tagged) {
                RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
                assertNotNull(sent);
                assertNoCredential(sent.getHeader("Authorization"));
                assertNoCredential(sent.getHeader("anti-csrf"));
            }
        }
    }

    @Test public void okhttpInitialPredispatchFallsBackWithoutSdkCredentials() throws Exception { okhttpDispatch(false, false); }
    @Test public void okhttpInitialTaggedPredispatchThrows() throws Exception { okhttpDispatch(false, true); }
    @Test public void okhttpRetryPredispatchReturnsRetainedBody() throws Exception { okhttpDispatch(true, false); }
    @Test public void okhttpTaggedRetryPredispatchThrows() throws Exception { okhttpDispatch(true, true); }

    private void okhttpDispatch(boolean retry, boolean tagged) throws Exception {
        SessionSnapshot initial = install("A", "initial");
        SessionRequestTag tag = tagged ? SessionState.capture() : null;
        if (retry) {
            server.enqueue(new MockResponse().setResponseCode(401).setBody("readable rejection"));
            server.enqueue(rotation(tokens("A", "refreshed")));
        } else if (!tagged) {
            server.enqueue(rotation(tokens("C", "must-not-adopt")).setResponseCode(401)
                    .setBody("readable rejection"));
        }
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger dispatches = new AtomicInteger();
        SuperTokensInterceptor interceptor = new SuperTokensInterceptor();
        SessionTransport.beforeDispatchForTests = () -> {
            if (dispatches.incrementAndGet() != (retry ? 2 : 1)) return;
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        };
        okhttp3.OkHttpClient.Builder builder = client.newBuilder();
        builder.interceptors().clear();
        client = builder.addInterceptor(interceptor).build();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = worker.submit(() -> {
                try { return read(false, tag, null); }
                catch (StaleSessionException stale) { if (tagged) return "stale"; throw stale; }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            SessionSnapshot replacement = install("B", "replacement");
            release.countDown();
            assertEquals(tagged ? "stale" : "readable rejection", result.get(5, TimeUnit.SECONDS));
            assertSame(replacement, SessionState.snapshot());
            int expected = retry ? 2 : tagged ? 0 : 1;
            assertEquals(expected, server.getRequestCount());
            for (int i = 0; i < expected; i++) {
                RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
                assertNotNull(sent);
                if (retry) {
                    assertEquals("Bearer " + (i == 0 ? initial.getTokens().getAccessToken()
                            : initial.getTokens().getRefreshToken()), sent.getHeader("Authorization"));
                } else {
                    assertNull(sent.getHeader("Authorization"));
                    assertNull(sent.getHeader("anti-csrf"));
                }
            }
        } finally {
            release.countDown();
            worker.shutdownNow();
            SessionTransport.beforeDispatchForTests = null;
        }
    }

    @Test public void nextWireNeverCapturesReplacementCredentials() throws Exception {
        install("A", "initial");
        SessionRequestTag logical = SessionState.capture();
        install("B", "replacement");
        assertNull(SessionTransport.nextWire(logical, false));
        try {
            SessionTransport.nextWire(logical, true);
            fail("Tagged retry must reject the replacement owner");
        } catch (StaleSessionException expected) { }
    }

    @Test public void urlConnectionReturnsReadableRejectionWhenRetryCallbackChangesOwner() throws Exception { beforeRetry(false); }
    @Test public void urlConnectionTaggedRetryCallbackStillThrows() throws Exception { beforeRetry(true); }

    @Test public void retryCallbackFailureIsNotHiddenByOwnerChange() throws Exception {
        install("A", "initial");
        SessionTokens replacement = tokens("B", "replacement");
        server.enqueue(new MockResponse().setResponseCode(401).setBody("readable rejection"));
        server.enqueue(rotation(tokens("A", "refreshed")));
        AtomicInteger callbacks = new AtomicInteger();
        java.io.IOException failure = new java.io.IOException("callback failed");
        try {
            read(true, null, connection -> {
                if (callbacks.incrementAndGet() == 2) {
                    assertTrue(SuperTokens.installSession(context, SessionState.snapshot().getOwner(), replacement).isSuccess());
                    throw failure;
                }
            });
            fail("Caller failure must propagate");
        } catch (java.io.IOException expected) { assertSame(failure, expected); }
        assertEquals(2, server.getRequestCount());
        assertEquals(replacement.getAccessToken(), SuperTokens.getAccessToken(context));
    }

    private void beforeRetry(boolean tagged) throws Exception {
        install("A", "initial");
        SessionRequestTag tag = tagged ? SessionState.capture() : null;
        server.enqueue(new MockResponse().setResponseCode(401).setBody("readable rejection"));
        server.enqueue(rotation(tokens("A", "refreshed")));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = worker.submit(() -> {
                try {
                    return read(true, tag, connection -> {
                        if (callbacks.incrementAndGet() == 2) {
                            entered.countDown();
                            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
                        }
                    });
                } catch (StaleSessionException stale) { if (tagged) return "stale"; throw stale; }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            SessionSnapshot replacement = install("B", "replacement");
            release.countDown();
            assertEquals(tagged ? "stale" : "readable rejection", result.get(5, TimeUnit.SECONDS));
            assertSame(replacement, SessionState.snapshot());
            assertEquals(2, server.getRequestCount());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    private void duringRefresh(boolean url, boolean tagged) throws Exception {
        SessionSnapshot initial = install("A", "initial");
        SessionRequestTag tag = tagged ? SessionState.capture() : null;
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        MockResponse refreshed = rotation(tokens("A", "refreshed"));
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (request.getPath().equals("/auth/session/refresh")) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(500);
                    return refreshed;
                }
                return new MockResponse().setResponseCode(401).setBody("readable rejection");
            }
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = worker.submit(() -> {
                try { return read(url, tag, null); }
                catch (StaleSessionException stale) { if (tagged) return "stale"; throw stale; }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            SessionSnapshot replacement = install("B", "replacement");
            release.countDown();
            assertEquals(tagged ? "stale" : "readable rejection", result.get(5, TimeUnit.SECONDS));
            assertSame(replacement, SessionState.snapshot());
            assertEquals(2, server.getRequestCount());
            assertEquals("Bearer " + initial.getTokens().getAccessToken(), server.takeRequest().getHeader("Authorization"));
            assertEquals("Bearer " + initial.getTokens().getRefreshToken(), server.takeRequest().getHeader("Authorization"));
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    private String read(boolean url, SessionRequestTag tag,
                        SuperTokensHttpURLConnection.PreConnectCallback callback) throws Exception {
        if (url) {
            HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(server.url("/protected").url(), callback, tag);
            try (InputStream body = connection.getErrorStream()) {
                assertEquals(401, connection.getResponseCode());
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = body.read(buffer)) != -1) bytes.write(buffer, 0, count);
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            } finally { connection.disconnect(); }
        }
        Request.Builder request = new Request.Builder().url(server.url("/protected"));
        if (tag != null) request.tag(SessionRequestTag.class, tag);
        try (Response response = client.newCall(request.build()).execute()) {
            assertEquals(401, response.code());
            return response.body().string();
        }
    }
}
