package com.supertokens.session;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionFixtureHelpersTest extends SessionTestFixture {
    @Override protected boolean initializeOnSetup() { return false; }

    @Test public void seedBeforeInitAndRestartPreserveLegacyIdentity() throws Exception {
        assertFalse(SuperTokens.isInitCalled);
        assertEquals(0, preferenceStore.commits);
        seedLegacy(tokens("seed", "user", 4102444800000L, 4102444800000L, 5));
        assertFalse(SuperTokens.isInitCalled);
        initializeSession();
        assertEquals("user", SuperTokens.getUserId(context));
        restart(true);
        assertEquals("user", SuperTokens.getUserId(context));
    }

    @Test public void tokenVersionsHaveExpectedIdentityAndExpiryUnits() throws Exception {
        for (int version = 2; version <= 5; version++) {
            SessionIdentity identity = SessionIdentity.parse(tokens("handle", "uid", 4102444800000L, 4102444800000L, version), 0);
            assertEquals("handle", identity.sessionHandle);
            assertEquals("uid", identity.userId);
            assertEquals("public", identity.tenantId);
            assertEquals(4102444800000L, identity.accessTokenExpiry);
        }
    }

    @Test public void failedCommitMemoryAndDiskAreIndependent() {
        for (TestPreferences.CommitMode mode : TestPreferences.CommitMode.values()) {
            Map<String, Object> initial = new HashMap<>();
            initial.put("token", "old");
            seedLegacy(initial);
            failCommits(mode);
            boolean threw = false;
            boolean acknowledged = false;
            try { acknowledged = preferenceStore.preferences.edit().putString("token", "new").commit(); }
            catch (IllegalStateException expected) { threw = true; }
            assertEquals(mode == TestPreferences.CommitMode.THROW || mode == TestPreferences.CommitMode.THROW_AFTER_MEMORY, threw);
            assertEquals(mode == TestPreferences.CommitMode.ACKNOWLEDGED, acknowledged);
            boolean changed = mode == TestPreferences.CommitMode.ACKNOWLEDGED || mode == TestPreferences.CommitMode.RETURN_FALSE_AFTER_MEMORY || mode == TestPreferences.CommitMode.THROW_AFTER_MEMORY;
            assertEquals(changed ? "new" : "old", preferenceStore.preferences.getString("token", null));
            preferenceStore.restart();
            assertEquals(acknowledged ? "new" : "old", preferenceStore.preferences.getString("token", null));
        }
    }

    @Test public void failedEditorDoesNotReplayPendingClearOnLaterCommit() {
        for (TestPreferences.CommitMode mode : TestPreferences.CommitMode.values()) {
            Map<String, Object> initial = new HashMap<>();
            initial.put("original", "durable");
            seedLegacy(initial);
            android.content.SharedPreferences.Editor editor = preferenceStore.preferences.edit();
            failCommits(mode);
            try { editor.clear().putString("attempt", "first").commit(); }
            catch (IllegalStateException expected) {
                assertTrue(mode == TestPreferences.CommitMode.THROW || mode == TestPreferences.CommitMode.THROW_AFTER_MEMORY);
            }
            boolean changed = mode == TestPreferences.CommitMode.ACKNOWLEDGED
                    || mode == TestPreferences.CommitMode.RETURN_FALSE_AFTER_MEMORY
                    || mode == TestPreferences.CommitMode.THROW_AFTER_MEMORY;
            failCommits(TestPreferences.CommitMode.ACKNOWLEDGED);
            assertTrue(preferenceStore.preferences.edit().putString("intervening", "durable").commit());
            assertTrue(editor.putString("last", "durable").commit());
            preferenceStore.restart();
            assertEquals("durable", preferenceStore.preferences.getString("intervening", null));
            assertEquals("durable", preferenceStore.preferences.getString("last", null));
            assertEquals(changed ? null : "durable", preferenceStore.preferences.getString("original", null));
            assertEquals(changed ? "first" : null, preferenceStore.preferences.getString("attempt", null));
        }
    }

    @Test public void applyNeedsExplicitFlushAndRawTypesSurviveRestart() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("long", 123L);
        raw.put("int", 12);
        raw.put("boolean", true);
        raw.put("float", 1.5f);
        raw.put("string", "123");
        seedLegacy(raw);
        preferenceStore.preferences.edit().putString("string", "volatile").apply();
        assertEquals("volatile", preferenceStore.preferences.getString("string", null));
        preferenceStore.restart();
        assertEquals(raw, preferenceStore.preferences.getAll());
        preferenceStore.preferences.edit().putString("string", "durable").apply();
        preferenceStore.flush();
        preferenceStore.restart();
        assertEquals("durable", preferenceStore.preferences.getString("string", null));
        assertEquals(0, preferenceStore.commits);
        assertEquals(2, preferenceStore.applies);
    }

    @Test public void eventRecorderCapturesDeliveryThreadAndReturnBoundary() throws Exception {
        RecordingEventHandler handler = new RecordingEventHandler();
        handler.beginRequest();
        handler.handleEvent(EventHandler.EventType.SESSION_CREATED);
        handler.requestReturned();
        Thread worker = new Thread(() -> handler.handleEvent(EventHandler.EventType.REFRESH_SESSION));
        worker.start();
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertEquals(2, handler.deliveries().size());
        assertSame(Thread.currentThread(), handler.deliveries().get(0).thread);
        assertTrue(handler.deliveries().get(0).beforeReturn);
        assertSame(worker, handler.deliveries().get(1).thread);
        assertFalse(handler.deliveries().get(1).beforeReturn);
        assertEquals(1, handler.deliveries().get(1).order);
    }

    @Test public void blockedWriteDoesNotBlockStorageReader() throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        SessionStorage delegate = new SessionStorage() {
            @Override public void editLegacy(android.content.Context context, LegacyEdit edit) throws java.io.IOException {
                new SharedPreferencesSessionStorage().editLegacy(context, edit);
            }
            @Override public Record read(android.content.Context ignored) { return null; }
            @Override public void write(android.content.Context ignored, Record record) { written.countDown(); }
        };
        BlockingStorage storage = new BlockingStorage(delegate);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(() -> { storage.write(context, null); return null; });
            assertTrue(storage.entered.await(5, TimeUnit.SECONDS));
            assertNull(executor.submit(() -> storage.read(context)).get(5, TimeUnit.SECONDS));
            assertEquals(1, written.getCount());
            assertFalse(writer.isDone());
            storage.release.countDown();
            writer.get(5, TimeUnit.SECONDS);
            assertEquals(0, written.getCount());
        } finally { storage.release.countDown(); executor.shutdownNow(); }
    }

    @Test public void backendRotatesRejectsStaleAccessAndBoundsSyntheticParentGrace() throws Exception {
        SessionTokens initial = tokens("backend", "initial");
        FakeBackend backend = new FakeBackend(initial);
        backend.parentGraceUses(1);
        server.setDispatcher(backend);
        OkHttpClient raw = new OkHttpClient();
        try {
            assertEquals(200, wire(raw, "/protected", initial.getAccessToken()));
            assertEquals(200, wire(raw, "/auth/session/refresh", initial.getRefreshToken()));
            assertEquals(401, wire(raw, "/protected", initial.getAccessToken()));
            assertEquals(200, wire(raw, "/protected", backend.currentTokens().getAccessToken()));
            String rotated = backend.currentTokens().getRefreshToken();
            assertEquals(200, wire(raw, "/auth/session/refresh", initial.getRefreshToken()));
            assertEquals(rotated, backend.currentTokens().getRefreshToken());
            assertEquals(401, wire(raw, "/auth/session/refresh", initial.getRefreshToken()));
            assertEquals(1, backend.reuseRejections.get());
            assertEquals(6, backend.requests().size());
        } finally { close(raw); }
    }

    @Test public void backendRotationPreservesSuppliedIdentityVersionAndPayload() throws Exception {
        OkHttpClient raw = new OkHttpClient();
        try {
            for (int version = 2; version <= 5; version++) {
                SessionTokens built = tokens("custom-handle", "custom-user", 4102444800000L, 4102444800000L, version);
                String[] jwt = built.getAccessToken().split("\\.");
                JSONObject payload = new JSONObject(new String(Base64.getUrlDecoder().decode(jwt[1]), StandardCharsets.UTF_8));
                JSONObject custom = new JSONObject().put("roles", new org.json.JSONArray().put("editor")).put("enabled", true);
                if (version >= 4) payload.put("tId", "custom-tenant");
                if (version >= 5) payload.put("rsub", "custom-recipe-user");
                if (version == 2) payload.put("userData", custom);
                else payload.put("custom", custom);
                String access = jwt[0] + "." + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8)) + "." + jwt[2];
                String front = Base64.getEncoder().encodeToString(new JSONObject().put("uid", "custom-user")
                        .put("ate", 4102444800000L).put("up", version == 2 ? custom : payload)
                        .put("extra", "preserved").toString().getBytes(StandardCharsets.UTF_8));
                SessionTokens initial = new SessionTokens(access, built.getRefreshToken(), front, "custom-csrf");
                FakeBackend backend = new FakeBackend(initial);
                server.setDispatcher(backend);
                SessionTokens previous = initial;
                for (int rotation = 0; rotation < 2; rotation++) {
                    assertEquals(200, wire(raw, "/auth/session/refresh", previous.getRefreshToken()));
                    SessionTokens current = backend.currentTokens();
                    SessionIdentity identity = SessionIdentity.parse(current, 0);
                    assertEquals("custom-handle", identity.sessionHandle);
                    assertEquals("custom-user", identity.userId);
                    assertEquals(version >= 4 ? "custom-tenant" : "public", identity.tenantId);
                    assertEquals(4102444800000L, identity.accessTokenExpiry);
                    assertEquals(initial.getAccessToken().substring(0, initial.getAccessToken().lastIndexOf('.')),
                            current.getAccessToken().substring(0, current.getAccessToken().lastIndexOf('.')));
                    assertEquals(initial.getFrontToken(), current.getFrontToken());
                    assertEquals(initial.getAntiCSRF(), current.getAntiCSRF());
                    assertNotEquals(previous.getAccessToken(), current.getAccessToken());
                    assertNotEquals(previous.getRefreshToken(), current.getRefreshToken());
                    assertEquals(401, wire(raw, "/protected", previous.getAccessToken()));
                    assertEquals(200, wire(raw, "/protected", current.getAccessToken()));
                    previous = current;
                }
            }
        } finally { close(raw); }
    }

    @Test public void backendCountsOverlappingRefreshesWithBarrier() throws Exception {
        SessionTokens initial = tokens("overlap", "initial");
        FakeBackend backend = new FakeBackend(initial);
        backend.parentGraceUses(1);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        backend.gateRefreshes(entered, release);
        server.setDispatcher(backend);
        OkHttpClient raw = new OkHttpClient();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> wire(raw, "/auth/session/refresh", initial.getRefreshToken()));
            Future<Integer> second = executor.submit(() -> wire(raw, "/auth/session/refresh", initial.getRefreshToken()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(2, backend.maximumRefreshOverlap.get());
            release.countDown();
            assertEquals(200, (int) first.get(5, TimeUnit.SECONDS));
            assertEquals(200, (int) second.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); executor.shutdownNow(); close(raw); }
    }

    private int wire(OkHttpClient raw, String path, String token) throws Exception {
        try (Response response = raw.newCall(new Request.Builder().url(server.url(path)).header("Authorization", "Bearer " + token).build()).execute()) {
            return response.code();
        }
    }

    private static void close(OkHttpClient client) {
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
    }
}
