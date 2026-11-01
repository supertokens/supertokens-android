package com.supertokens.session;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionRefreshFailureModeTest extends SessionTestFixture {
    public enum Fault { HTML, MISSING_HEADERS, HTTP_500, HTTP_502, HTTP_503, RETRY_AFTER, RESET, TIMEOUT, FRONT_ONLY }
    private final AtomicInteger preparations = new AtomicInteger();

    @Override protected void initializeSession() throws Exception {
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                .customHeaderProvider(type -> {
                    if (type == CustomHeaderProvider.RequestType.REFRESH) preparations.incrementAndGet();
                    return null;
                }).build();
    }

    @Test public void failedRefreshesBoundSdkDispatchAndRetainLegacyWireObservations() throws Exception {
        AtomicInteger sdkDispatches = new AtomicInteger();
        client = client.newBuilder().addInterceptor(chain -> {
            if (chain.request().url().encodedPath().equals("/auth/session/refresh")) sdkDispatches.incrementAndGet();
            return chain.proceed(chain.request());
        }).build();
        for (boolean url : new boolean[]{false, true}) {
            for (Fault fault : Fault.values()) {
                preparations.set(0);
                sdkDispatches.set(0);
                String handle = "fault-" + url + "-" + fault;
                SessionSnapshot before = install(handle, "before");
                AtomicInteger protectedRequests = new AtomicInteger(), refreshes = new AtomicInteger();
                SessionTokens ignored = tokens(handle, "must-not-adopt");
                server.setDispatcher(new Dispatcher() {
                    @Override public MockResponse dispatch(RecordedRequest request) {
                        if (request.getPath().equals("/protected")) {
                            protectedRequests.incrementAndGet();
                            return new MockResponse().setResponseCode(401).setBody("original-401");
                        }
                        assertEquals("/auth/session/refresh", request.getPath());
                        refreshes.incrementAndGet();
                        assertEquals("Bearer " + before.getTokens().getRefreshToken(), request.getHeader("Authorization"));
                        return response(fault, ignored);
                    }
                });
                // The legacy OkHttp chain inherits this native read timeout; URLConnection refresh has its SDK 10s bound.
                client = client.newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build();
                ExecutorService worker = Executors.newSingleThreadExecutor();
                try {
                    Future<IOException> result = worker.submit(() -> {
                        try { request(url, null, "/protected"); return null; }
                        catch (IOException expected) { return expected; }
                    });
                    IOException error = result.get(20, TimeUnit.SECONDS);
                    assertNotNull(fault + " must not turn failed refresh into success", error);
                    assertFalse(error instanceof StaleSessionException);
                    if (fault == Fault.HTML || fault == Fault.MISSING_HEADERS || fault == Fault.FRONT_ONLY) {
                        assertEquals("Malformed refresh response", error.getMessage());
                    }
                    assertEquals(fault.toString(), 1, protectedRequests.get());
                    assertEquals(fault + " SDK preparations", 1, preparations.get());
                    assertEquals(fault + " caller-chain SDK dispatches", url ? 0 : 1, sdkDispatches.get());
                    // Approved 2026-10-30: these are pinned fixture observations, not a legacy wire guarantee.
                    boolean historicalReset = !url && fault == Fault.RESET
                            && "3.12.13".equals(System.getProperty("session.matrix.okhttp"));
                    assertEquals(fault + " observed HTTP exchanges", historicalReset ? 3 : 1, refreshes.get());
                    assertSame(before, SuperTokens.getSessionSnapshot(context));
                    assertEquals(SessionSnapshot.Status.AVAILABLE, before.getStatus());
                    if (fault == Fault.RETRY_AFTER) {
                        try { request(url, null, "/protected"); fail("Cooldown must preserve failure"); }
                        catch (IOException expected) { assertFalse(expected instanceof StaleSessionException); }
                        assertEquals(2, protectedRequests.get());
                        assertEquals("Retry-After must suppress an immediate second refresh", 1, refreshes.get());
                        assertEquals(1, preparations.get());
                        assertEquals(url ? 0 : 1, sdkDispatches.get());
                        assertSame(before, SuperTokens.getSessionSnapshot(context));
                    }
                    System.out.println("FAULT transport=" + (url ? "url" : "okhttp") + " fault=" + fault
                            + " exception=" + error.getClass().getName() + " protected=" + protectedRequests
                            + " sdkPreparations=" + preparations + " callerChainDispatches=" + sdkDispatches
                            + " httpExchanges=" + refreshes);
                } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS)); }

                // Fresh-owner refresh must succeed after each failure; repeated probes also detect leaked flight slots.
                SessionTokens healthy = tokens("healthy-" + handle, "before");
                FakeBackend backend = new FakeBackend(healthy);
                server.setDispatcher(backend);
                SessionCommitResult installed = SuperTokens.installSession(context, SuperTokens.getSessionSnapshot(context).getOwner(), healthy);
                assertTrue(installed.isSuccess());
                assertTrue(SuperTokens.resolveAccessToken(context, installed.getSnapshot().getOwner(), true, null).isSuccess());
                assertEquals(0, backend.reuseRejections.get());
                assertEquals(1, backend.refreshAttempts.get());
            }
        }
    }

    @Test public void ownedResetNeverReplaysWithinExecution() throws Exception { ownedLostResponse(false); }

    @Test public void ownedConsumedLostResponseNeverReplaysWithinExecution() throws Exception { ownedLostResponse(true); }

    private void ownedLostResponse(boolean consume) throws Exception {
        if (!ownedRefreshAvailable()) return; // Unsupported constructor rejection is asserted by the fixture.
        // Keep an alternate route available so a replayable mutant cannot pass just by exhausting routes.
        java.net.InetAddress loopback = java.net.InetAddress.getByName("127.0.0.1");
        okhttp3.OkHttpClient base = client.newBuilder().retryOnConnectionFailure(true)
                .dns(host -> java.util.Arrays.asList(loopback, loopback)).build();
        okhttp3.OkHttpClient.Builder builder = base.newBuilder();
        builder.interceptors().clear();
        okhttp3.OkHttpClient owned = builder.addInterceptor(new SuperTokensInterceptor(base)).build();
        try {
            SessionSnapshot before = install("owned-lost-" + consume, "before");
            FakeBackend backend = new FakeBackend(before.getTokens());
            AtomicInteger exchanges = new AtomicInteger(), protectedRequests = new AtomicInteger();
            preparations.set(0);
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    if (request.getPath().equals("/protected")) {
                        protectedRequests.incrementAndGet();
                        return new MockResponse().setResponseCode(401);
                    }
                    assertEquals("/auth/session/refresh", request.getPath());
                    exchanges.incrementAndGet();
                    assertEquals("Bearer " + before.getTokens().getRefreshToken(), request.getHeader("Authorization"));
                    MockResponse result = consume ? backend.dispatch(request) : new MockResponse();
                    return result.setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
                }
            });
            try {
                request(owned, SessionState.capture(), "/protected");
                fail("Lost response cannot succeed");
            } catch (IOException expected) {
                assertFalse(expected instanceof StaleSessionException);
            }
            assertEquals(1, protectedRequests.get());
            assertEquals(1, preparations.get());
            assertEquals("Owned execution must not replay even when caller retries are enabled", 1, exchanges.get());
            assertSame(before, SuperTokens.getSessionSnapshot(context));
            assertEquals(consume ? 1 : 0, backend.refreshAttempts.get());
            assertEquals(0, backend.reuseRejections.get());
            assertEquals(consume, !before.getTokens().getRefreshToken().equals(backend.currentTokens().getRefreshToken()));
            assertTrue(base.retryOnConnectionFailure());
            System.out.println("OWNED lostResponse consumed=" + consume + " sdkPreparations=" + preparations
                    + " httpExchanges=" + exchanges + " snapshotRetained=true");
        } finally {
            owned.connectionPool().evictAll();
        }
    }

    public static MockResponse response(Fault fault, SessionTokens ignored) {
        switch (fault) {
            case HTML: return new MockResponse().setHeader("Content-Type", "text/html").setBody("<html>captive portal</html>");
            case MISSING_HEADERS: return new MockResponse().setBody("{\"status\":\"OK\"}");
            case HTTP_500: return new MockResponse().setResponseCode(500);
            case HTTP_502: return new MockResponse().setResponseCode(502);
            case HTTP_503: return new MockResponse().setResponseCode(503);
            case RETRY_AFTER: return new MockResponse().setResponseCode(429).setHeader("Retry-After", "3600");
            case RESET: return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
            case TIMEOUT: return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);
            case FRONT_ONLY: return new MockResponse().addHeader("front-token", ignored.getFrontToken());
            default: throw new AssertionError(fault);
        }
    }

    @Test public void syntheticParentGraceAndReplayDetectionPreserveClaimsAndExpiry() throws Exception {
        SessionTokens initial = tokens("grace", "user", 4102444700000L, 4102444800000L, 4);
        FakeBackend backend = new FakeBackend(initial);
        backend.parentGraceUses(1);
        server.setDispatcher(backend);
        okhttp3.OkHttpClient raw = new okhttp3.OkHttpClient();
        try {
            okhttp3.Request request = new okhttp3.Request.Builder().url(server.url("/auth/session/refresh"))
                    .header("Authorization", "Bearer " + initial.getRefreshToken()).post(okhttp3.RequestBody.create(null, new byte[0])).build();
            try (okhttp3.Response first = raw.newCall(request).execute()) { assertEquals(200, first.code()); }
            SessionTokens rotated = backend.currentTokens();
            assertEquals(initial.getFrontToken(), rotated.getFrontToken());
            assertEquals(initial.getAntiCSRF(), rotated.getAntiCSRF());
            assertEquals(initial.getAccessToken().substring(0, initial.getAccessToken().lastIndexOf('.')),
                    rotated.getAccessToken().substring(0, rotated.getAccessToken().lastIndexOf('.')));
            try (okhttp3.Response parent = raw.newCall(request).execute()) { assertEquals(200, parent.code()); }
            try (okhttp3.Response replay = raw.newCall(request).execute()) { assertEquals(401, replay.code()); }
            assertEquals(1, backend.reuseRejections.get());
            assertEquals(3, backend.refreshAttempts.get());
        } finally { raw.connectionPool().evictAll(); raw.dispatcher().executorService().shutdownNow(); }
    }
}
