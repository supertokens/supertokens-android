package com.supertokens.session;

import android.content.Context;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.URI;
import java.util.Map;
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.*;
import org.mockito.Mockito;
import static org.junit.Assert.*;

public class SessionEventDeliveryTest {
    private Context context;
    private MockWebServer server;
    private ExecutorService worker;
    private CookieHandler previousCookies;
    private final List<EventHandler.EventType> legacy = new CopyOnWriteArrayList<>();
    private static final String OLD = front("old", "{}");

    private static String front(String user, String payload) {
        return Base64.getEncoder().encodeToString(("{\"uid\":\"" + user + "\",\"ate\":4102444800000,\"up\":" + payload + "}").getBytes(StandardCharsets.UTF_8));
    }

    @Before public void setUp() throws Exception {
        previousCookies = CookieHandler.getDefault();
        SuperTokens.resetForTests();
        context = Mockito.mock(Context.class);
        Mockito.when(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(new SPMockBuilder().createSharedPreferences());
        server = new MockWebServer();
        server.start();
        new SuperTokens.Builder(context, server.url("/").toString()).eventHandler(legacy::add).build();
        worker = Executors.newSingleThreadExecutor();
        install("old", OLD);
    }

    @After public void tearDown() throws Exception {
        CookieHandler.setDefault(previousCookies);
        SessionStore.setBeforeEventDeliveryForTests(null);
        worker.shutdownNow();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        server.shutdown();
        SuperTokens.resetForTests();
    }

    private void failManualCookieWrite() {
        CookieHandler.setDefault(new CookieManager() {
            @Override public void put(URI uri, Map<String, List<String>> headers) throws IOException {
                for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                    if (frame.getMethodName().equals("manuallySetCookiesFromResponse")) throw new IOException("manual cookie failure");
                }
                super.put(uri, headers);
            }
        });
    }

    @Test public void cookieFailureStillNotifiesAcceptedPayload() throws Exception {
        failManualCookieWrite();
        List<SuperTokens.SessionSnapshot> snapshots = new CopyOnWriteArrayList<>();
        List<SuperTokens.SessionEvent> events = new CopyOnWriteArrayList<>();
        SuperTokens.addSessionListener(snapshots::add);
        SuperTokens.addSessionEventListener(events::add);
        String updated = front("old", "{\"changed\":true}");
        server.enqueue(new MockResponse().setHeader("front-token", updated).setHeader("Set-Cookie", "test=value; Path=/"));
        try { request(); fail("expected cookie failure"); }
        catch (IOException expected) { assertEquals("manual cookie failure", expected.getMessage()); }
        assertEquals(updated, SuperTokens.getSessionSnapshot(context).frontToken);
        assertEquals(1, snapshots.size());
        assertEquals(updated, snapshots.get(0).frontToken);
        assertEquals(1, events.size());
        assertEquals(EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED, events.get(0).type);
        assertEquals(updated, events.get(0).snapshot.frontToken);
        assertEquals(java.util.Collections.singletonList(EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED), legacy);
    }

    @Test public void refreshCookieFailureDoesNotReportRefreshSuccess() throws Exception {
        failManualCookieWrite();
        List<SuperTokens.SessionEvent> events = new CopyOnWriteArrayList<>();
        SuperTokens.addSessionEventListener(events::add);
        server.enqueue(new MockResponse().setHeader("front-token", OLD).setHeader("Set-Cookie", "test=value; Path=/"));
        try { SuperTokens.attemptRefreshingSession(context); fail("expected cookie failure"); }
        catch (IOException expected) { assertEquals("manual cookie failure", expected.getMessage()); }
        assertFalse(legacy.contains(EventHandler.EventType.REFRESH_SESSION));
        for (SuperTokens.SessionEvent event : events) assertNotEquals(EventHandler.EventType.REFRESH_SESSION, event.type);
    }

    @Test public void cookieFailureNotifiesClearedStateButNotSignOut() throws Exception {
        failManualCookieWrite();
        List<SuperTokens.SessionSnapshot> snapshots = new CopyOnWriteArrayList<>();
        List<SuperTokens.SessionEvent> events = new CopyOnWriteArrayList<>();
        SuperTokens.addSessionListener(snapshots::add);
        SuperTokens.addSessionEventListener(events::add);
        server.enqueue(new MockResponse().setHeader("front-token", "remove").setHeader("Set-Cookie", "test=value; Path=/"));
        try { request(); fail("expected cookie failure"); }
        catch (IOException expected) { assertEquals("manual cookie failure", expected.getMessage()); }
        assertNull(SuperTokens.getSessionSnapshot(context).frontToken);
        assertEquals(1, snapshots.size());
        assertNull(snapshots.get(0).frontToken);
        assertTrue(events.isEmpty());
        assertTrue(legacy.isEmpty());
    }

    @Test public void cookieFailureSuppressesUnauthorisedLifecycle() throws Exception {
        failManualCookieWrite();
        List<SuperTokens.SessionEvent> events = new CopyOnWriteArrayList<>();
        SuperTokens.addSessionEventListener(events::add);
        server.enqueue(new MockResponse().setResponseCode(401).setHeader("front-token", "remove").setHeader("Set-Cookie", "test=value; Path=/"));
        try { request(); fail("expected cookie failure"); }
        catch (IOException expected) { assertEquals("manual cookie failure", expected.getMessage()); }
        assertNull(SuperTokens.getSessionSnapshot(context).frontToken);
        assertTrue(events.isEmpty());
        assertTrue(legacy.isEmpty());
    }

    @Test public void cookieFailureSuppressesSessionCreatedLifecycle() throws Exception {
        assertTrue(SuperTokens.clearSession(context, SuperTokens.getSessionSnapshot(context).generation));
        failManualCookieWrite();
        List<SuperTokens.SessionEvent> events = new CopyOnWriteArrayList<>();
        List<SuperTokens.SessionSnapshot> snapshots = new CopyOnWriteArrayList<>();
        SuperTokens.addSessionEventListener(events::add);
        SuperTokens.addSessionListener(snapshots::add);
        server.enqueue(new MockResponse().setHeader("front-token", OLD).setHeader("Set-Cookie", "test=value; Path=/"));
        try { request(); fail("expected cookie failure"); }
        catch (IOException expected) { assertEquals("manual cookie failure", expected.getMessage()); }
        assertEquals(OLD, SuperTokens.getSessionSnapshot(context).frontToken);
        assertEquals(1, snapshots.size());
        assertTrue(events.isEmpty());
        assertTrue(legacy.isEmpty());
    }

    private void install(String user, String front) {
        assertTrue(SuperTokens.installSession(context, SuperTokens.getSessionSnapshot(context).generation,
                new SuperTokens.SessionTokens(user + "-access", user + "-refresh", front, user + "-csrf")));
    }

    private int request() throws Exception {
        HttpURLConnection c = SuperTokensHttpURLConnection.newRequest(server.url("/ordinary").url(), null);
        try { return c.getResponseCode(); } finally { c.disconnect(); }
    }

    private Runnable gate(CountDownLatch entered, CountDownLatch release) {
        return () -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        };
    }

    @Test public void cookieWriteCanWaitForAnotherThreadReadingToken() throws Exception {
        AtomicInteger manualWrites = new AtomicInteger();
        CookieHandler.setDefault(new CookieManager() {
            @Override public void put(URI uri, Map<String, List<String>> headers) throws IOException {
                for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                    if (!frame.getMethodName().equals("manuallySetCookiesFromResponse")) continue;
                    manualWrites.incrementAndGet();
                    Future<String> token = worker.submit(() -> Utils.getTokenForHeaderAuth(Utils.TokenType.ACCESS, context));
                    try { assertEquals("old-access", token.get(1, TimeUnit.SECONDS)); }
                    catch (TimeoutException e) { throw new AssertionError("token read blocked by cookie write", e); }
                    catch (InterruptedException | ExecutionException e) { throw new AssertionError(e); }
                    return;
                }
                super.put(uri, headers);
            }
        });
        server.enqueue(new MockResponse().setHeader("Set-Cookie", "test=value; Path=/"));
        assertEquals(200, request());
        assertEquals(1, manualWrites.get());
    }

    @Test public void admittedLifecycleEventKeepsClearGenerationAndCannotClearReplacement() throws Exception {
        CountDownLatch admitted = new CountDownLatch(1), release = new CountDownLatch(1);
        SessionStore.setBeforeEventDeliveryForTests(gate(admitted, release));
        List<SuperTokens.SessionEvent> received = new CopyOnWriteArrayList<>();
        SuperTokens.addSessionEventListener(event -> {
            received.add(event);
            assertFalse(SuperTokens.clearSession(context, event.generation));
        });
        server.enqueue(new MockResponse().setHeader("front-token", "remove"));
        Future<Integer> response = worker.submit(this::request);
        try {
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            assertEquals(2, SuperTokens.getSessionSnapshot(context).generation);
            install("new", front("new", "{}"));
        } finally { release.countDown(); }
        assertEquals(200, (int) response.get(5, TimeUnit.SECONDS));
        assertEquals(1, received.size());
        assertEquals(EventHandler.EventType.SIGN_OUT, received.get(0).type);
        assertEquals(2, received.get(0).generation);
        assertNull(received.get(0).snapshot.frontToken);
        assertEquals("new-access", SuperTokens.getSessionSnapshot(context).accessToken);
        assertTrue(legacy.isEmpty());
    }

    @Test public void admittedLegacyCallbackMayBeginAfterReplacement() throws Exception {
        CountDownLatch admitted = new CountDownLatch(1), release = new CountDownLatch(1);
        SessionStore.setBeforeEventDeliveryForTests(gate(admitted, release));
        server.enqueue(new MockResponse().setHeader("front-token", "remove"));
        Future<Integer> response = worker.submit(this::request);
        try {
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            install("new", front("new", "{}"));
        } finally { release.countDown(); }
        assertEquals(200, (int) response.get(5, TimeUnit.SECONDS));
        assertEquals(1, legacy.size());
        assertEquals(EventHandler.EventType.SIGN_OUT, legacy.get(0));
        assertEquals("new-access", SuperTokens.getSessionSnapshot(context).accessToken);
    }

    @Test public void normalEventAndRemovalDeliverExactlyOnce() throws Exception {
        List<SuperTokens.SessionEvent> events = new CopyOnWriteArrayList<>();
        SuperTokens.SessionEventListener listener = events::add;
        SuperTokens.addSessionEventListener(listener);
        server.enqueue(new MockResponse().setHeader("front-token", "remove"));
        assertEquals(200, request());
        assertEquals(1, events.size());
        assertEquals(EventHandler.EventType.SIGN_OUT, events.get(0).type);
        assertEquals(2, events.get(0).generation);
        assertEquals(2, events.get(0).snapshot.generation);
        assertNull(events.get(0).snapshot.accessToken);
        SuperTokens.removeSessionEventListener(listener);
        install("another", front("another", "{}"));
        server.enqueue(new MockResponse().setHeader("front-token", "remove"));
        assertEquals(200, request());
        assertEquals(1, events.size());
        assertEquals(2, legacy.size());
    }

    @Test public void unauthorisedClearCarriesPostClearGeneration() throws Exception {
        List<SuperTokens.SessionEvent> events = new CopyOnWriteArrayList<>();
        SuperTokens.addSessionEventListener(events::add);
        server.enqueue(new MockResponse().setResponseCode(401).setHeader("front-token", "remove"));
        server.enqueue(new MockResponse().setBody("ok"));
        assertEquals(200, request());
        assertEquals(1, events.size());
        assertEquals(EventHandler.EventType.UNAUTHORISED, events.get(0).type);
        assertEquals(2, events.get(0).generation);
        assertNull(events.get(0).snapshot.frontToken);
        assertEquals(2, SuperTokens.getSessionSnapshot(context).generation);
    }

    @Test public void okhttpDeliversGenerationBearingSignoutEvent() throws Exception {
        List<SuperTokens.SessionEvent> events = new CopyOnWriteArrayList<>();
        SuperTokens.addSessionEventListener(events::add);
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(new SuperTokensInterceptor()).build();
        try {
            server.enqueue(new MockResponse().setHeader("front-token", "remove"));
            try (Response response = client.newCall(new Request.Builder().url(server.url("/ordinary")).build()).execute()) {
                assertEquals(200, response.code());
            }
            assertEquals(1, events.size());
            assertEquals(EventHandler.EventType.SIGN_OUT, events.get(0).type);
            assertEquals(2, events.get(0).generation);
            assertNull(events.get(0).snapshot.frontToken);
        } finally {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
        }
    }

    @Test public void ownershipIsRecheckedBetweenEventListeners() throws Exception {
        AtomicInteger second = new AtomicInteger();
        SuperTokens.addSessionEventListener(event -> install("replacement", front("new", "{}")));
        SuperTokens.addSessionEventListener(event -> second.incrementAndGet());
        server.enqueue(new MockResponse().setHeader("front-token", "remove"));
        assertEquals(200, request());
        assertEquals(0, second.get());
        assertTrue(legacy.isEmpty());
        assertEquals("replacement-access", SuperTokens.getSessionSnapshot(context).accessToken);
    }

    @Test public void eventListenerCanRefreshSynchronouslyAndInstallThenClear() throws Exception {
        AtomicInteger called = new AtomicInteger();
        SuperTokens.addSessionEventListener(event -> {
            if (event.type != EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED) return;
            try {
                assertEquals(event.generation, SuperTokens.getSessionSnapshot(context).generation);
                assertTrue(SuperTokens.attemptRefreshingSession(context));
                assertTrue(SuperTokens.installSession(context, event.generation,
                        new SuperTokens.SessionTokens("replacement", "r", front("new", "{}"), "c")));
                assertTrue(SuperTokens.clearSession(context, SuperTokens.getSessionSnapshot(context).generation));
                called.incrementAndGet();
            } catch (Exception e) { throw new AssertionError(e); }
        });
        String updated = front("old", "{\"changed\":true}");
        server.enqueue(new MockResponse().setHeader("front-token", updated));
        server.enqueue(new MockResponse().setHeader("front-token", updated));
        Future<Integer> response = worker.submit(this::request);
        assertEquals(200, (int) response.get(5, TimeUnit.SECONDS));
        assertEquals(1, called.get());
        assertNull(SuperTokens.getSessionSnapshot(context).frontToken);
    }
}
