package com.supertokens.session;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
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

public class SessionStressTest extends SessionTestFixture {
    private final ThreadLocal<String> operation = new ThreadLocal<>();
    private final Map<String, LogicalRequest> logical = new ConcurrentHashMap<>();
    private final List<String> violations = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();

    private static final class LogicalRequest {
        final boolean tagged;
        String handle;
        LogicalRequest(boolean tagged, String expectedHandle) { this.tagged = tagged; handle = expectedHandle; }
        synchronized void observe(String sentHandle) {
            if (tagged) assertNotNull("An absent tagged request exported credentials", handle);
            if (handle == null) handle = sentHandle;
            else assertEquals("A logical request borrowed another session's credential", handle, sentHandle);
        }
    }

    private final class Backend extends Dispatcher {
        final Map<String, FakeBackend> sessions = new ConcurrentHashMap<>();
        final Map<String, String> credentials = new ConcurrentHashMap<>();
        final Map<String, Boolean> rejected = new ConcurrentHashMap<>();
        final AtomicInteger requests = new AtomicInteger();

        SessionTokens register(String handle) throws Exception {
            SessionTokens tokens = tokens(handle, "primary-user", 4102444800000L, 4102444800000L, 5);
            FakeBackend backend = new FakeBackend(tokens);
            backend.parentGraceUses(0);
            sessions.put(handle, backend);
            index(handle, tokens);
            return tokens;
        }

        void index(String handle, SessionTokens tokens) {
            credentials.put("Bearer " + tokens.getAccessToken(), handle);
            credentials.put("Bearer " + tokens.getRefreshToken(), handle);
        }

        @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            requests.incrementAndGet();
            String auth = request.getHeader("Authorization");
            if ("".equals(auth)) auth = null;
            String handle = auth == null ? null : credentials.get(auth);
            String id = request.getHeader("x-stress-op");
            if (auth != null) {
                if (handle == null) violations.add("unknown credential " + auth + " on " + request.getPath());
                else if (id == null || !logical.containsKey(id)) violations.add("untracked credential-bearing wire " + request.getPath());
                else try { logical.get(id).observe(handle); }
                catch (AssertionError mismatch) { violations.add(id + ": " + mismatch.getMessage()); }
            }
            if (request.getPath().startsWith("/login?")) {
                String target = request.getRequestUrl().queryParameter("session");
                return rotation(sessions.get(target).currentTokens()).setBody("{\"status\":\"OK\"}");
            }
            if (handle == null) return new MockResponse().setResponseCode(401);
            if (request.getPath().equals("/reject") && rejected.putIfAbsent(id, Boolean.TRUE) == null) {
                return new MockResponse().setResponseCode(401);
            }
            FakeBackend backend = sessions.get(handle);
            MockResponse response = backend.dispatch(request);
            String access = response.getHeaders().get("st-access-token");
            String refresh = response.getHeaders().get("st-refresh-token");
            if (access != null) credentials.put("Bearer " + access, handle);
            if (refresh != null) credentials.put("Bearer " + refresh, handle);
            index(handle, backend.currentTokens());
            return response;
        }

        void assertInvariants() {
            int rotations = 0;
            for (Map.Entry<String, FakeBackend> entry : sessions.entrySet()) {
                FakeBackend backend = entry.getValue();
                assertTrue("refresh overlap for synthetic lineage " + entry.getKey(), backend.maximumRefreshOverlap.get() <= 1);
                assertEquals("rotated refresh reuse for " + entry.getKey(), 0, backend.reuseRejections.get());
                rotations += backend.refreshAttempts.get();
            }
            System.out.println("STRESS wire=" + requests + " refresh=" + rotations + " sessions=" + sessions.size() + " outcomes=" + outcomes);
        }
    }

    private Backend configureBackend() throws Exception {
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                .customHeaderProvider(type -> operation.get() == null ? Collections.emptyMap()
                        : Collections.singletonMap("x-stress-op", operation.get())).reconfigure().isSuccess());
        Backend backend = new Backend();
        server.setDispatcher(backend);
        SessionTokens initial = backend.register("initial");
        assertTrue(SuperTokens.installSession(context, SuperTokens.getSessionSnapshot(context).getOwner(), initial).isSuccess());
        return backend;
    }

    private String begin(boolean tagged, SessionSnapshot snapshot) {
        String id = "op-" + ids.incrementAndGet();
        logical.put(id, new LogicalRequest(tagged, tagged ? snapshot.getSessionHandle() : null));
        operation.set(id);
        return id;
    }

    private int wire(boolean url, SessionRequestTag tag, String path, String id) throws Exception {
        if (url) {
            HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(server.url(path).url(), con -> {
                con.setConnectTimeout(8000); con.setReadTimeout(8000); con.setRequestProperty("x-stress-op", id);
            }, tag);
            try { return connection.getResponseCode(); } finally { connection.disconnect(); }
        }
        Request.Builder builder = new Request.Builder().url(server.url(path)).header("x-stress-op", id);
        if (tag != null) builder.tag(SessionRequestTag.class, tag);
        try (Response response = client.newCall(builder.build()).execute()) { return response.code(); }
    }

    @Test public void seededPublicOperationMix() throws Exception {
        long seed = Long.getLong("session.stress.seed", 0x5eed2026L);
        int threads = Integer.getInteger("session.stress.threads", 16);
        int operations = Integer.getInteger("session.stress.operations", 40);
        int watchdogSeconds = Integer.getInteger("session.stress.watchdogSeconds", 60);
        assertTrue(threads > 0 && operations > 0 && watchdogSeconds > 0);
        System.out.println("STRESS seed=" + seed + " threads=" + threads + " operationsPerThread=" + operations);
        Backend backend = configureBackend();
        CountDownLatch ready = new CountDownLatch(threads), start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(threads);
        List<Future<?>> pending = new ArrayList<>();
        try {
            for (int worker = 0; worker < threads; worker++) {
                final int workerId = worker;
                pending.add(workers.submit(() -> {
                    Random random = new Random(seed ^ (0x9e3779b97f4a7c15L * (workerId + 1)));
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    for (int step = 0; step < operations; step++) {
                        int kind = random.nextInt(8);
                        boolean tagged = (kind <= 2 || kind == 7) && random.nextBoolean();
                        SessionRequestTag tag = tagged ? SuperTokens.captureRequestTag(context) : null;
                        SessionSnapshot snapshot = tag == null ? SuperTokens.getSessionSnapshot(context) : tag.getSnapshot();
                        String id = begin(tagged, snapshot);
                        try {
                            switch (kind) {
                                case 0: case 1: case 2:
                                    int code = wire(kind == 1, tag, kind == 2 ? "/reject" : "/protected", id);
                                    assertTrue(code == 200 || code == 401);
                                    break;
                                case 3:
                                    SuperTokens.doesSessionExist(context);
                                    SuperTokens.getAccessToken(context);
                                    break;
                                case 4: SuperTokens.attemptRefreshingSession(context); break;
                                case 5: SuperTokens.signOut(context); break;
                                case 6:
                                    SessionCommitResult installed = SuperTokens.installSession(context, snapshot.getOwner(), backend.register(id));
                                    assertTrue(installed.isSuccess() || installed.getStatus() == SessionCommitResult.Status.STALE_OWNER);
                                    break;
                                case 7: backend.register(id); assertEquals(200, wire(random.nextBoolean(), tag, "/login?session=" + id, id)); break;
                                default: throw new AssertionError();
                            }
                            outcomes.computeIfAbsent("operation-" + kind, key -> new AtomicInteger()).incrementAndGet();
                        } catch (StaleSessionException stale) {
                            if (tagged && (kind <= 2 || kind == 7)) outcomes.computeIfAbsent("tagged-stale", key -> new AtomicInteger()).incrementAndGet();
                            else violations.add("legacy stale kind=" + kind + " id=" + id);
                        } catch (IOException | IllegalAccessException expected) {
                            String message = expected.getMessage();
                            if (message != null && (message.startsWith("Received a 401 response from ")
                                    || message.equals("Too many unfinished refresh transports"))) {
                                outcomes.computeIfAbsent("bounded-legacy-io", key -> new AtomicInteger()).incrementAndGet();
                            } else violations.add("unexpected " + expected.getClass().getName() + " kind=" + kind + " message=" + message);
                        } finally { operation.remove(); }
                        assertNotEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SuperTokens.getSessionSnapshot(context).getStatus());
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(watchdogSeconds);
            for (Future<?> future : pending) future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            backend.assertInvariants();
            // Ten subsequent public refreshes exercise the eight-flight cap's release path without private inspection.
            SessionTokens healthy = backend.register("post-stress");
            assertTrue(SuperTokens.installSession(context, SuperTokens.getSessionSnapshot(context).getOwner(), healthy).isSuccess());
            for (int i = 0; i < 10; i++) {
                begin(false, SuperTokens.getSessionSnapshot(context));
                assertTrue(SuperTokens.attemptRefreshingSession(context));
                operation.remove();
            }
            backend.assertInvariants();
            System.out.println("STRESS violations=" + violations);
            assertTrue("seed=" + seed + " violations=" + violations.size() + " first="
                    + violations.subList(0, Math.min(20, violations.size())), violations.isEmpty());
        } finally {
            start.countDown(); workers.shutdownNow();
            assertTrue("Stress workers did not terminate", workers.awaitTermination(15, TimeUnit.SECONDS));
            operation.remove();
        }
    }

    @Test public void publicRefreshCapacityIsReleasedAfterEightRetiredLeaders() throws Exception {
        Backend backend = configureBackend();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<SessionResolution>> pending = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                String handle = "retired-" + i;
                SessionTokens tokens = backend.register(handle);
                SessionCommitResult installed = SuperTokens.installSession(context, SuperTokens.getSessionSnapshot(context).getOwner(), tokens);
                assertTrue(installed.isSuccess());
                CountDownLatch arrived = new CountDownLatch(1);
                backend.sessions.get(handle).gateRefreshes(arrived, release);
                SessionSnapshot captured = installed.getSnapshot();
                pending.add(workers.submit(() -> {
                    begin(true, captured);
                    try { return SuperTokens.resolveAccessToken(context, captured.getOwner(), true, null); }
                    finally { operation.remove(); }
                }));
                assertTrue(arrived.await(5, TimeUnit.SECONDS));
            }
            SessionCommitResult latest = SuperTokens.installSession(context, SuperTokens.getSessionSnapshot(context).getOwner(), backend.register("latest"));
            assertTrue(latest.isSuccess());
            SessionResolution bounded = SuperTokens.resolveAccessToken(context, latest.getSnapshot().getOwner(), true, null);
            assertEquals(SessionResolution.Status.REFRESH_FAILED, bounded.getStatus());
            assertEquals("Too many unfinished refresh transports", bounded.getError().getMessage());
            release.countDown();
            for (Future<SessionResolution> future : pending) assertEquals(SessionResolution.Status.STALE_OWNER, future.get(5, TimeUnit.SECONDS).getStatus());
            begin(true, latest.getSnapshot());
            assertTrue(SuperTokens.resolveAccessToken(context, latest.getSnapshot().getOwner(), true, null).isSuccess());
            backend.assertInvariants();
            assertTrue(violations.toString(), violations.isEmpty());
        } finally { release.countDown(); operation.remove(); workers.shutdownNow(); assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS)); }
    }
}
