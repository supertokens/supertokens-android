package com.example.example;

import android.content.Context;
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder;
import com.supertokens.session.EventHandler;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensHttpURLConnection;
import com.supertokens.session.SuperTokensInterceptor;
import com.supertokens.session.SuperTokensGeneralError;
import java.net.HttpURLConnection;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mockito;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class SessionTransportGenerationTest {
    @Parameterized.Parameters(name = "{0}") public static Collection<Object[]> transports() {
        return Arrays.asList(new Object[][] {{"okhttp"}, {"urlconnection"}});
    }

    private final String transport;
    private Context context;
    private MockWebServer server;
    private OkHttpClient client;
    private ExecutorService worker;
    private CountDownLatch entered, release;
    private CookieHandler previousCookies;
    private final List<EventHandler.EventType> events = new CopyOnWriteArrayList<>();

    public SessionTransportGenerationTest(String transport) { this.transport = transport; }

    @Before public void setup() throws Exception {
        SuperTokens.resetForTests();
        previousCookies = CookieHandler.getDefault();
        CookieHandler.setDefault(null);
        context = Mockito.mock(Context.class);
        Mockito.when(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(new SPMockBuilder().createSharedPreferences());
        server = new MockWebServer();
        server.start();
        new SuperTokens.Builder(context, server.url("/").toString())
                .eventHandler(events::add).build();
        client = new OkHttpClient.Builder().addInterceptor(new SuperTokensInterceptor())
                .readTimeout(5, TimeUnit.SECONDS).build();
        worker = Executors.newSingleThreadExecutor();
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        install("old");
    }

    @After public void cleanup() throws Exception {
        release.countDown();
        worker.shutdownNow();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
        server.shutdown();
        SuperTokens.resetForTests();
        CookieHandler.setDefault(previousCookies);
    }

    private static String front(String user) {
        String json = "{\"uid\":\"" + user + "\",\"ate\":4102444800000,\"up\":{}}";
        return java.util.Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private void install(String user) {
        assertTrue(SuperTokens.installSession(context, SuperTokens.getSessionSnapshot(context).generation,
                new SuperTokens.SessionTokens(user + "-access", user + "-refresh", front(user), user + "-csrf")));
    }

    private MockResponse tokens(String user) {
        return new MockResponse().setBody("{\"status\":\"OK\"}")
                .setHeader("st-access-token", user + "-access")
                .setHeader("st-refresh-token", user + "-refresh")
                .setHeader("front-token", front(user)).setHeader("anti-csrf", user + "-csrf");
    }

    private MockResponse gated(MockResponse response) throws InterruptedException {
        entered.countDown();
        assertTrue("test did not release response", release.await(5, TimeUnit.SECONDS));
        return response;
    }

    private int request(String path) throws Exception {
        if (transport.equals("okhttp")) {
            Request.Builder request = new Request.Builder().url(server.url(path));
            if (path.equals("/auth/signout")) request.post(okhttp3.RequestBody.create(null, new byte[0]));
            try (Response response = client.newCall(request.build()).execute()) { return response.code(); }
        }
        HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(server.url(path).url(), con -> {
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            if (path.equals("/auth/signout")) con.setRequestMethod("POST");
        });
        try { return connection.getResponseCode(); } finally { connection.disconnect(); }
    }

    private void assertNewSession() {
        SuperTokens.SessionSnapshot snapshot = SuperTokens.getSessionSnapshot(context);
        assertEquals("new-access", snapshot.accessToken);
        assertEquals("new-refresh", snapshot.refreshToken);
        assertEquals(front("new"), snapshot.frontToken);
        assertEquals("new-csrf", snapshot.antiCsrfToken);
        assertTrue("stale events: " + events, events.isEmpty());
    }

    @Test public void lateRefreshSuccessCannotOverwriteInstallAndRetryUsesNewHeaders() throws Exception {
        lateRefresh(false);
    }

    @Test public void lateRefresh401CannotClearInstallOrFireUnauthorised() throws Exception {
        lateRefresh(true);
    }

    @Test public void lateRefresh401WithoutFrontTokenCannotClearInstallOrFireUnauthorised() throws Exception {
        lateRefresh(true, false);
    }

    @Test public void lateRefresh401WithoutFrontTokenCannotRestoreClearedSession() throws Exception {
        lateRefreshAfterClear(false);
    }

    @Test public void lateRefresh401WithRemoveCannotRestoreClearedSession() throws Exception {
        lateRefreshAfterClear(true);
    }

    private void lateRefreshAfterClear(boolean includeFrontToken) throws Exception {
        AtomicInteger ordinary = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (request.getPath().equals("/auth/session/refresh")) {
                    MockResponse response = new MockResponse().setResponseCode(401);
                    if (includeFrontToken) response.setHeader("front-token", "remove");
                    return gated(response);
                }
                return new MockResponse().setResponseCode(ordinary.incrementAndGet() == 1 ? 401 : 200);
            }
        });
        Future<Integer> response = worker.submit(() -> request("/ordinary"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(SuperTokens.clearSession(context, SuperTokens.getSessionSnapshot(context).generation));
        long generation = SuperTokens.getSessionSnapshot(context).generation;
        release.countDown();
        assertEquals(200, (int) response.get(5, TimeUnit.SECONDS));
        SuperTokens.SessionSnapshot snapshot = SuperTokens.getSessionSnapshot(context);
        assertEquals(generation, snapshot.generation);
        assertNull(snapshot.accessToken);
        assertNull(snapshot.refreshToken);
        assertNull(snapshot.frontToken);
        assertNull(snapshot.antiCsrfToken);
        assertTrue(events.isEmpty());
    }

    private void lateRefresh(boolean unauthorised) throws Exception {
        lateRefresh(unauthorised, true);
    }

    private void lateRefresh(boolean unauthorised, boolean includeFrontToken) throws Exception {
        AtomicInteger ordinaryRequests = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (request.getPath().equals("/auth/session/refresh")) {
                    assertEquals("Bearer old-refresh", request.getHeader("Authorization"));
                    assertEquals("old-csrf", request.getHeader("anti-csrf"));
                    MockResponse response = unauthorised ? new MockResponse().setResponseCode(401) : tokens("stale");
                    if (unauthorised && includeFrontToken) response.setHeader("front-token", "remove");
                    return gated(response);
                }
                if (ordinaryRequests.incrementAndGet() == 1) return new MockResponse().setResponseCode(401);
                assertEquals("Bearer new-access", request.getHeader("Authorization"));
                assertEquals("new-csrf", request.getHeader("anti-csrf"));
                return new MockResponse().setBody("ok");
            }
        });
        Future<Integer> response = worker.submit(() -> request("/ordinary"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        install("new");
        release.countDown();
        assertEquals(200, (int) response.get(5, TimeUnit.SECONDS));
        assertEquals(2, ordinaryRequests.get());
        assertNewSession();
    }

    @Test public void lateOrdinaryResponseCannotRestoreClear() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                return gated(tokens("stale"));
            }
        });
        Future<Integer> response = worker.submit(() -> request("/ordinary"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(SuperTokens.clearSession(context, SuperTokens.getSessionSnapshot(context).generation));
        release.countDown();
        assertEquals(200, (int) response.get(5, TimeUnit.SECONDS));
        SuperTokens.SessionSnapshot snapshot = SuperTokens.getSessionSnapshot(context);
        assertNull(snapshot.accessToken);
        assertNull(snapshot.refreshToken);
        assertNull(snapshot.frontToken);
        assertNull(snapshot.antiCsrfToken);
        assertTrue(events.isEmpty());
    }

    @Test public void lateSignoutCannotClearInstallOrFireSignout() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                return gated(new MockResponse().setHeader("front-token", "remove").setBody("{\"status\":\"OK\"}"));
            }
        });
        Future<Integer> response = worker.submit(() -> request("/auth/signout"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        install("new");
        release.countDown();
        assertEquals(200, (int) response.get(5, TimeUnit.SECONDS));
        assertNewSession();
    }

    @Test public void sameGenerationRotationIsAccepted() throws Exception {
        long generation = SuperTokens.getSessionSnapshot(context).generation;
        server.enqueue(tokens("rotated").setHeader("front-token", front("old")));
        assertEquals(200, request("/ordinary"));
        SuperTokens.SessionSnapshot snapshot = SuperTokens.getSessionSnapshot(context);
        assertEquals(generation, snapshot.generation);
        assertEquals("rotated-access", snapshot.accessToken);
        assertEquals("rotated-refresh", snapshot.refreshToken);
        assertEquals("rotated-csrf", snapshot.antiCsrfToken);
    }

    @Test public void requestPreparedDuringSameGenerationRotationUsesCoherentCredentials() throws Exception {
        long generation = SuperTokens.getSessionSnapshot(context).generation;
        if (transport.equals("okhttp")) {
            client = client.newBuilder().addNetworkInterceptor(chain -> {
                if (chain.request().url().encodedPath().equals("/ordinary")) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("gate timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException(e);
                    }
                }
                return chain.proceed(chain.request());
            }).build();
        }
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/rotate")) return tokens("rotated").setHeader("front-token", front("old"));
                return new MockResponse().setBody("ok");
            }
        });
        Future<Integer> pending = worker.submit(() -> {
            if (transport.equals("okhttp")) return request("/ordinary");
            HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(server.url("/ordinary").url(), con -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("gate timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(e);
                }
            });
            try { return connection.getResponseCode(); } finally { connection.disconnect(); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(200, request("/rotate"));
        assertEquals(generation, SuperTokens.getSessionSnapshot(context).generation);
        release.countDown();
        assertEquals(200, (int) pending.get(5, TimeUnit.SECONDS));
        RecordedRequest rotated = server.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest ordinary = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(rotated);
        assertNotNull(ordinary);
        assertEquals("/rotate", rotated.getPath());
        assertEquals("/ordinary", ordinary.getPath());
        assertEquals("Bearer old-access", ordinary.getHeader("Authorization"));
        assertEquals("old-csrf", ordinary.getHeader("anti-csrf"));
        assertEquals("rotated-access", SuperTokens.getSessionSnapshot(context).accessToken);
        assertEquals("rotated-csrf", SuperTokens.getSessionSnapshot(context).antiCsrfToken);
    }

    @Test public void eventHandlerCanRefreshWithoutReadLockUpgradeDeadlock() throws Exception {
        SuperTokens.resetForTests();
        AtomicInteger refreshEvents = new AtomicInteger();
        new SuperTokens.Builder(context, server.url("/").toString()).eventHandler(event -> {
            if (event == EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED) {
                try { assertEquals(200, request("/reentrant")); }
                catch (Exception e) { throw new AssertionError(e); }
            }
            if (event == EventHandler.EventType.REFRESH_SESSION) refreshEvents.incrementAndGet();
        }).build();
        install("old");
        String updated = java.util.Base64.getEncoder().encodeToString(
                "{\"uid\":\"old\",\"ate\":4102444800000,\"up\":{\"changed\":true}}".getBytes(StandardCharsets.UTF_8));
        server.enqueue(tokens("old").setHeader("front-token", updated));
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(tokens("old").setHeader("front-token", updated));
        server.enqueue(new MockResponse().setBody("ok"));
        Future<Integer> response = worker.submit(() -> request("/ordinary"));
        assertEquals(200, (int) response.get(5, TimeUnit.SECONDS));
        assertEquals(1, refreshEvents.get());
    }

    @Test public void publicSignoutUsesTheGenerationFromItsExistenceCheck() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                assertEquals("/auth/signout", request.getPath());
                assertEquals("Bearer old-access", request.getHeader("Authorization"));
                return gated(new MockResponse().setHeader("front-token", "remove").setBody("{\"status\":\"OK\"}"));
            }
        });
        Future<?> response = worker.submit(() -> {
            SuperTokens.signOut(context);
            return null;
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        install("new");
        release.countDown();
        response.get(5, TimeUnit.SECONDS);
        assertNewSession();
    }

    @Test public void oldPublicSignoutErrorIsIgnoredAfterReplacementAndClear() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                return gated(new MockResponse().setHeader("front-token", "remove")
                        .setBody("{\"status\":\"GENERAL_ERROR\",\"message\":\"old error\"}"));
            }
        });
        Future<?> response = worker.submit(() -> { SuperTokens.signOut(context); return null; });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        install("new");
        assertTrue(SuperTokens.clearSession(context, SuperTokens.getSessionSnapshot(context).generation));
        release.countDown();
        response.get(5, TimeUnit.SECONDS);
        assertNull(SuperTokens.getSessionSnapshot(context).frontToken);
        assertTrue("stale events: " + events, events.isEmpty());
    }

    @Test public void successfulPublicSignoutFiresExactlyOneEvent() throws Exception {
        server.enqueue(new MockResponse().setHeader("front-token", "remove").setBody("{\"status\":\"OK\"}"));
        SuperTokens.signOut(context);
        assertNull(SuperTokens.getSessionSnapshot(context).frontToken);
        assertEquals(Arrays.asList(EventHandler.EventType.SIGN_OUT), events);
    }

    @Test public void stalePublicSignoutConnectionFailureIsSuppressed() throws Exception {
        Assume.assumeTrue(transport.equals("urlconnection"));
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                return gated(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST));
            }
        });
        Future<?> response = worker.submit(() -> { SuperTokens.signOut(context); return null; });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        install("new");
        release.countDown();
        response.get(5, TimeUnit.SECONDS);
        assertNewSession();
    }

    @Test public void currentPublicSignoutConnectionFailurePropagates() throws Exception {
        Assume.assumeTrue(transport.equals("urlconnection"));
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST);
            }
        });
        try {
            SuperTokens.signOut(context);
            fail("Expected an IOException");
        } catch (java.io.IOException expected) {
            assertEquals("old-access", SuperTokens.getSessionSnapshot(context).accessToken);
        }
    }

    @Test public void refreshListenersRunWithoutRefreshLockHeld() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(tokens("old"));
        server.enqueue(new MockResponse().setBody("ok"));
        server.enqueue(new MockResponse().setBody("ok"));
        ExecutorService nestedWorker = Executors.newSingleThreadExecutor();
        SuperTokens.SessionListener listener = snapshot -> {
            try { assertEquals(200, (int) nestedWorker.submit(() -> request("/nested")).get(2, TimeUnit.SECONDS)); }
            catch (Exception e) { throw new AssertionError("listener holds refresh lock", e); }
        };
        try {
            SuperTokens.addSessionListener(listener);
            assertEquals(200, (int) worker.submit(() -> request("/ordinary")).get(5, TimeUnit.SECONDS));
        } finally {
            SuperTokens.removeSessionListener(listener);
            nestedWorker.shutdownNow();
        }
    }

    @Test public void staleResponseDoesNotManuallySaveCookies() throws Exception {
        AtomicInteger manualWrites = new AtomicInteger();
        CookieHandler.setDefault(new CookieManager() {
            @Override public void put(URI uri, java.util.Map<String, java.util.List<String>> headers) {
                if (uri.getPath().equals("/auth")) manualWrites.incrementAndGet();
            }
        });
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                return gated(tokens("stale").setHeader("Set-Cookie", "sAccessToken=stale; Path=/auth"));
            }
        });
        Future<Integer> response = worker.submit(() -> request("/ordinary"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        install("new");
        release.countDown();
        assertEquals(200, (int) response.get(5, TimeUnit.SECONDS));
        assertEquals(0, manualWrites.get());
        assertNewSession();
    }

    @Test public void customConnectionCallbackUsesCapturedHeadersEvenIfSessionChanges() throws Exception {
        Assume.assumeTrue(transport.equals("urlconnection"));
        server.enqueue(new MockResponse().setBody("ok"));
        HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(server.url("/ordinary").url(), con -> {
            install("new");
            con.setRequestProperty("Authorization", "Bearer old-access");
        });
        connection.disconnect();
        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent);
        assertEquals("Bearer old-access", sent.getHeader("Authorization"));
        assertEquals("old-csrf", sent.getHeader("anti-csrf"));
        assertNewSession();
    }

    @Test public void ordinaryResponseListenerCanInstallOnAnotherThread() throws Exception {
        server.enqueue(tokens("old"));
        ExecutorService installer = Executors.newSingleThreadExecutor();
        SuperTokens.SessionListener listener = snapshot -> {
            if (!"old-access".equals(snapshot.accessToken)) return;
            try {
                assertTrue(installer.submit(() -> SuperTokens.installSession(context, snapshot.generation,
                        new SuperTokens.SessionTokens("new-access", "new-refresh", front("new"), "new-csrf")))
                        .get(2, TimeUnit.SECONDS));
            } catch (Exception e) { throw new AssertionError("listener cannot replace session", e); }
        };
        try {
            SuperTokens.addSessionListener(listener);
            assertEquals(200, (int) worker.submit(() -> request("/ordinary")).get(5, TimeUnit.SECONDS));
            assertNewSession();
        } finally {
            SuperTokens.removeSessionListener(listener);
            installer.shutdownNow();
        }
    }

    @Test public void concurrent401sShareOneSerializedRefreshAndKeepGeneration() throws Exception {
        long generation = SuperTokens.getSessionSnapshot(context).generation;
        CountDownLatch bothOrdinaryRequests = new CountDownLatch(2);
        AtomicInteger refreshes = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (request.getPath().equals("/auth/session/refresh")) {
                    refreshes.incrementAndGet();
                    assertEquals("Bearer old-refresh", request.getHeader("Authorization"));
                    assertEquals("old-csrf", request.getHeader("anti-csrf"));
                    return gated(tokens("rotated").setHeader("front-token", front("old")));
                }
                if ("Bearer old-access".equals(request.getHeader("Authorization"))) {
                    bothOrdinaryRequests.countDown();
                    assertTrue(bothOrdinaryRequests.await(5, TimeUnit.SECONDS));
                    return new MockResponse().setResponseCode(401);
                }
                assertEquals("Bearer rotated-access", request.getHeader("Authorization"));
                assertEquals("rotated-csrf", request.getHeader("anti-csrf"));
                return new MockResponse().setBody("ok");
            }
        });
        ExecutorService concurrent = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = concurrent.submit(() -> request("/ordinary"));
            Future<Integer> second = concurrent.submit(() -> request("/ordinary"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(200, (int) first.get(5, TimeUnit.SECONDS));
            assertEquals(200, (int) second.get(5, TimeUnit.SECONDS));
            assertEquals(1, refreshes.get());
            assertEquals(generation, SuperTokens.getSessionSnapshot(context).generation);
        } finally { concurrent.shutdownNow(); }
    }

    @Test public void currentRefreshDisconnectPropagatesAndLaterRequestRecovers() throws Exception {
        long generation = SuperTokens.getSessionSnapshot(context).generation;
        AtomicInteger refreshes = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean healthy = new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch bothOrdinary = new CountDownLatch(2);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (request.getPath().equals("/auth/session/refresh")) {
                    assertEquals("Bearer old-refresh", request.getHeader("Authorization"));
                    assertEquals("old-csrf", request.getHeader("anti-csrf"));
                    refreshes.incrementAndGet();
                    if (!healthy.get()) {
                        return gated(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST));
                    }
                    return tokens("rotated").setHeader("front-token", front("old"));
                }
                if ("Bearer old-access".equals(request.getHeader("Authorization"))) {
                    if (!healthy.get()) {
                        bothOrdinary.countDown();
                        assertTrue(bothOrdinary.await(5, TimeUnit.SECONDS));
                    }
                    return new MockResponse().setResponseCode(401);
                }
                assertEquals("Bearer rotated-access", request.getHeader("Authorization"));
                assertEquals("rotated-csrf", request.getHeader("anti-csrf"));
                return new MockResponse().setBody("ok");
            }
        });
        ExecutorService concurrent = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = concurrent.submit(() -> request("/ordinary"));
            Future<Integer> second = concurrent.submit(() -> request("/ordinary"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            release.countDown();
            for (Future<Integer> failed : Arrays.asList(first, second)) {
                try {
                    failed.get(5, TimeUnit.SECONDS);
                    fail("Expected a transport failure");
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof java.io.IOException);
                }
            }
        } finally {
            concurrent.shutdownNow();
            assertTrue(concurrent.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals("old-access", SuperTokens.getSessionSnapshot(context).accessToken);
        assertEquals("old-refresh", SuperTokens.getSessionSnapshot(context).refreshToken);
        assertEquals(front("old"), SuperTokens.getSessionSnapshot(context).frontToken);
        assertEquals("old-csrf", SuperTokens.getSessionSnapshot(context).antiCsrfToken);
        assertEquals(generation, SuperTokens.getSessionSnapshot(context).generation);
        assertFalse(events.contains(EventHandler.EventType.UNAUTHORISED));
        assertFalse(events.contains(EventHandler.EventType.REFRESH_SESSION));
        assertFalse(events.contains(EventHandler.EventType.SIGN_OUT));
        healthy.set(true);
        assertEquals(200, (int) worker.submit(() -> request("/ordinary")).get(5, TimeUnit.SECONDS));
        assertTrue(refreshes.get() >= 2);
        assertEquals(1, java.util.Collections.frequency(events, EventHandler.EventType.REFRESH_SESSION));
    }

    @Test public void refreshReadTimeoutPreservesSessionAndLaterRecovers() throws Exception {
        Assume.assumeTrue(transport.equals("okhttp") || "500".equals(System.getProperty("sun.net.client.defaultReadTimeout")));
        client = client.newBuilder().readTimeout(500, TimeUnit.MILLISECONDS).build();
        AtomicInteger refreshes = new AtomicInteger();
        long generation = SuperTokens.getSessionSnapshot(context).generation;
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (request.getPath().equals("/auth/session/refresh")) {
                    if (refreshes.incrementAndGet() == 1) return gated(tokens("old"));
                    return tokens("rotated").setHeader("front-token", front("old"));
                }
                if ("Bearer old-access".equals(request.getHeader("Authorization"))) {
                    return new MockResponse().setResponseCode(401);
                }
                assertEquals("Bearer rotated-access", request.getHeader("Authorization"));
                assertEquals("rotated-csrf", request.getHeader("anti-csrf"));
                return new MockResponse().setBody("ok");
            }
        });
        Future<Integer> failed = worker.submit(() -> request("/ordinary"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            failed.get(5, TimeUnit.SECONDS);
            fail("Expected a refresh read timeout");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof java.net.SocketTimeoutException);
        } finally {
            release.countDown();
        }
        SuperTokens.SessionSnapshot snapshot = SuperTokens.getSessionSnapshot(context);
        assertEquals(generation, snapshot.generation);
        assertEquals("old-access", snapshot.accessToken);
        assertEquals("old-refresh", snapshot.refreshToken);
        assertEquals(front("old"), snapshot.frontToken);
        assertEquals("old-csrf", snapshot.antiCsrfToken);
        assertTrue(events.isEmpty());
        assertEquals(200, (int) worker.submit(() -> request("/ordinary")).get(5, TimeUnit.SECONDS));
        assertEquals(1, java.util.Collections.frequency(events, EventHandler.EventType.REFRESH_SESSION));
    }

    @Test public void currentRefresh401ClearsSessionAndCompletesWaitingRequests() throws Exception {
        CountDownLatch bothOrdinary = new CountDownLatch(2);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (request.getPath().equals("/auth/session/refresh")) {
                    return gated(new MockResponse().setResponseCode(401).setHeader("front-token", "remove"));
                }
                if ("Bearer old-access".equals(request.getHeader("Authorization"))) {
                    bothOrdinary.countDown();
                    assertTrue(bothOrdinary.await(5, TimeUnit.SECONDS));
                }
                return new MockResponse().setResponseCode(401);
            }
        });
        ExecutorService concurrent = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = concurrent.submit(() -> request("/ordinary"));
            Future<Integer> second = concurrent.submit(() -> request("/ordinary"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(401, (int) first.get(5, TimeUnit.SECONDS));
            assertEquals(401, (int) second.get(5, TimeUnit.SECONDS));
            assertNull(SuperTokens.getSessionSnapshot(context).frontToken);
            assertNull(SuperTokens.getSessionSnapshot(context).accessToken);
            assertNull(SuperTokens.getSessionSnapshot(context).refreshToken);
            assertNull(SuperTokens.getSessionSnapshot(context).antiCsrfToken);
            assertTrue(events.contains(EventHandler.EventType.UNAUTHORISED));
            assertFalse(events.contains(EventHandler.EventType.REFRESH_SESSION));
        } finally {
            concurrent.shutdownNow();
            assertTrue(concurrent.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void currentRefresh401WithoutFrontTokenPreservesSessionAndGeneration() throws Exception {
        SuperTokens.SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
        List<SuperTokens.SessionEvent> notifications = new CopyOnWriteArrayList<>();
        SuperTokens.addSessionEventListener(notifications::add);
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(401));
        boolean threwIOException = false;
        try {
            request("/ordinary");
        } catch (java.io.IOException expected) {
            threwIOException = true;
        }
        SuperTokens.SessionSnapshot after = SuperTokens.getSessionSnapshot(context);
        assertEquals(before.generation, after.generation);
        assertEquals(before.accessToken, after.accessToken);
        assertEquals(before.refreshToken, after.refreshToken);
        assertEquals(before.frontToken, after.frontToken);
        assertEquals(before.antiCsrfToken, after.antiCsrfToken);
        assertEquals(java.util.Collections.singletonList(EventHandler.EventType.UNAUTHORISED), events);
        assertEquals(1, notifications.size());
        assertEquals(EventHandler.EventType.UNAUTHORISED, notifications.get(0).type);
        assertEquals(before.generation, notifications.get(0).generation);
        assertEquals(before.accessToken, notifications.get(0).snapshot.accessToken);
        assertEquals(before.refreshToken, notifications.get(0).snapshot.refreshToken);
        assertEquals(before.frontToken, notifications.get(0).snapshot.frontToken);
        assertEquals(before.antiCsrfToken, notifications.get(0).snapshot.antiCsrfToken);
        assertTrue("refresh 401 without front-token must propagate IOException", threwIOException);
    }

    @Test public void signoutDoesNotAdoptReplacementInstalledDuringExistenceRefresh() throws Exception {
        assertTrue(SuperTokens.installSession(context, SuperTokens.getSessionSnapshot(context).generation,
                new SuperTokens.SessionTokens("old-access", "old-refresh",
                        java.util.Base64.getEncoder().encodeToString(
                                "{\"uid\":\"old\",\"ate\":0,\"up\":{}}".getBytes(StandardCharsets.UTF_8)), "old-csrf")));
        server.enqueue(tokens("old"));
        server.enqueue(new MockResponse().setHeader("front-token", "remove").setBody("{\"status\":\"OK\"}"));
        SuperTokens.SessionListener listener = snapshot -> {
            if ("old-access".equals(snapshot.accessToken)) install("new");
        };
        try {
            SuperTokens.addSessionListener(listener);
            worker.submit(() -> { SuperTokens.signOut(context); return null; }).get(5, TimeUnit.SECONDS);
            assertEquals(1, server.getRequestCount());
            assertEquals("/auth/session/refresh", server.takeRequest(5, TimeUnit.SECONDS).getPath());
            assertNewSession();
        } finally { SuperTokens.removeSessionListener(listener); }
    }

    @Test public void refreshEventCanMakeRequestOnAnotherThread() throws Exception {
        SuperTokens.resetForTests();
        ExecutorService nested = Executors.newSingleThreadExecutor();
        AtomicInteger calls = new AtomicInteger();
        try {
            new SuperTokens.Builder(context, server.url("/").toString()).eventHandler(event -> {
                if (event == EventHandler.EventType.REFRESH_SESSION) {
                    try {
                        assertEquals(200, (int) nested.submit(() -> request("/nested")).get(2, TimeUnit.SECONDS));
                        calls.incrementAndGet();
                    } catch (Exception e) { throw new AssertionError("event handler holds refresh lock", e); }
                }
            }).build();
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(tokens("old"));
            server.enqueue(new MockResponse().setBody("ok"));
            server.enqueue(new MockResponse().setBody("ok"));
            assertEquals(200, (int) worker.submit(() -> request("/ordinary")).get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally { nested.shutdownNow(); }
    }

    @Test public void signoutStillChecksGeneralErrorAfterItsOwnRemovalHeader() throws Exception {
        server.enqueue(new MockResponse().setHeader("front-token", "remove")
                .setBody("{\"status\":\"GENERAL_ERROR\",\"message\":\"expected failure\"}"));
        try {
            SuperTokens.signOut(context);
            fail("Expected sign-out error");
        } catch (SuperTokensGeneralError expected) {
            assertEquals("expected failure", expected.getMessage());
        }
    }

    @Test public void installInsidePreconnectDefersListenerUntilRefreshLockReleased() throws Exception {
        Assume.assumeTrue(transport.equals("urlconnection"));
        server.enqueue(new MockResponse().setBody("ok"));
        server.enqueue(new MockResponse().setBody("ok"));
        ExecutorService nested = Executors.newSingleThreadExecutor();
        AtomicInteger notified = new AtomicInteger();
        SuperTokens.SessionListener listener = snapshot -> {
            try {
                assertEquals(200, (int) nested.submit(() -> request("/nested")).get(2, TimeUnit.SECONDS));
                notified.incrementAndGet();
            } catch (Exception e) { throw new AssertionError("listener invoked under refresh lock", e); }
        };
        try {
            SuperTokens.addSessionListener(listener);
            Future<Integer> result = worker.submit(() -> {
                HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(server.url("/ordinary").url(), con -> install("new"));
                try { return connection.getResponseCode(); } finally { connection.disconnect(); }
            });
            assertEquals(200, (int) result.get(5, TimeUnit.SECONDS));
            assertEquals(1, notified.get());
        } finally {
            SuperTokens.removeSessionListener(listener);
            nested.shutdownNow();
        }
    }

    @Test public void installInsideRefreshHeaderProviderDefersListener() throws Exception {
        SuperTokens.resetForTests();
        ExecutorService nested = Executors.newSingleThreadExecutor();
        AtomicInteger notified = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        try {
            new SuperTokens.Builder(context, server.url("/").toString()).customHeaderProvider(type -> {
                install("new");
                return null;
            }).build();
            install("old");
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().equals("/auth/session/refresh")) return tokens("old");
                    if (request.getPath().equals("/nested")) return new MockResponse().setBody("ok");
                    return requests.incrementAndGet() == 1 ? new MockResponse().setResponseCode(401) : new MockResponse().setBody("ok");
                }
            });
            SuperTokens.SessionListener listener = snapshot -> {
                try {
                    assertEquals(200, (int) nested.submit(() -> request("/nested")).get(2, TimeUnit.SECONDS));
                    notified.incrementAndGet();
                } catch (Exception e) { throw new AssertionError("listener invoked under refresh lock", e); }
            };
            SuperTokens.addSessionListener(listener);
            assertEquals(200, (int) worker.submit(() -> request("/ordinary")).get(5, TimeUnit.SECONDS));
            assertEquals(1, notified.get());
            assertEquals("new-access", SuperTokens.getSessionSnapshot(context).accessToken);
            SuperTokens.removeSessionListener(listener);
        } finally { nested.shutdownNow(); }
    }
}
