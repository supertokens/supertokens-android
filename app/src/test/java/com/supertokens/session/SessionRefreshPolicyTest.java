package com.supertokens.session;

import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionRefreshPolicyTest extends SessionTestFixture {
    @Test public void ordinaryFirstDuringRefreshPreservesRotatedLineage() throws Exception { overlap(true); }
    @Test public void refreshFirstThenOrdinaryPreservesRotatedLineage() throws Exception { overlap(false); }

    private void overlap(boolean ordinaryFirst) throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            SessionSnapshot a = install("A", "initial");
            SessionTokens promoted = tokens("A", "ordinary"), rotated = tokens("A", "rotated");
            CountDownLatch ordinaryArrived = new CountDownLatch(1), refreshArrived = new CountDownLatch(1);
            CountDownLatch releaseOrdinary = new CountDownLatch(1), releaseRefresh = new CountDownLatch(1);
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    if (request.getPath().equals("/auth/session/refresh")) {
                        refreshArrived.countDown();
                        return releaseRefresh.await(8, TimeUnit.SECONDS) ? rotation(rotated) : new MockResponse().setResponseCode(504);
                    }
                    ordinaryArrived.countDown();
                    return releaseOrdinary.await(8, TimeUnit.SECONDS) ? promotion(promoted) : new MockResponse().setResponseCode(504);
                }
            });
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> ordinary = workers.submit(() -> request(url, null, "/ordinary"));
                assertTrue(ordinaryArrived.await(5, TimeUnit.SECONDS));
                Future<SessionResolution> refresh = workers.submit(() -> SuperTokens.resolveAccessToken(context, a.getOwner(), true, null));
                assertTrue(refreshArrived.await(5, TimeUnit.SECONDS));
                if (ordinaryFirst) {
                    releaseOrdinary.countDown();
                    assertEquals(Integer.valueOf(200), ordinary.get(5, TimeUnit.SECONDS));
                    assertEquals("active refresh excludes ordinary promotion", a.getRevision(), SuperTokens.getSessionSnapshot(context).getRevision());
                    releaseRefresh.countDown();
                    assertTrue(refresh.get(5, TimeUnit.SECONDS).isSuccess());
                } else {
                    releaseRefresh.countDown();
                    assertTrue(refresh.get(5, TimeUnit.SECONDS).isSuccess());
                    releaseOrdinary.countDown();
                    assertEquals(Integer.valueOf(200), ordinary.get(5, TimeUnit.SECONDS));
                }
                SessionSnapshot current = SuperTokens.getSessionSnapshot(context);
                assertSame(a.getOwner(), current.getOwner());
                assertEquals(a.getAdoptionId(), current.getAdoptionId());
                assertEquals(rotated.getAccessToken(), current.getTokens().getAccessToken());
                assertEquals(rotated.getRefreshToken(), current.getTokens().getRefreshToken());
                RecordedRequest ordinaryWire = server.takeRequest(1, TimeUnit.SECONDS);
                RecordedRequest refreshWire = server.takeRequest(1, TimeUnit.SECONDS);
                assertEquals("Bearer " + a.getTokens().getAccessToken(), ordinaryWire.getHeader("Authorization"));
                assertEquals("Bearer " + a.getTokens().getRefreshToken(), refreshWire.getHeader("Authorization"));
            } finally {
                releaseOrdinary.countDown(); releaseRefresh.countDown();
                workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test public void current401RemovalClearsWithoutRefreshBothTransports() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            SessionSnapshot a = install("A", "initial");
            int before = server.getRequestCount();
            server.enqueue(new MockResponse().setResponseCode(401).addHeader("front-token", "remove"));
            assertEquals(401, request(url, null, "/current-remove"));
            SessionSnapshot absent = SuperTokens.getSessionSnapshot(context);
            assertEquals(SessionSnapshot.Status.ABSENT, absent.getStatus());
            assertNull(absent.getTokens());
            assertNotSame(a.getOwner(), absent.getOwner());
            assertEquals(before + 1, server.getRequestCount());
        }
    }

    @Test public void obsolete401RemovalCannotClearNewerSameOwnerAndRetriesWithNewAccess() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            SessionSnapshot a = install("A", "initial");
            SessionTokens promoted = tokens("A", "newer");
            CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger refreshes = new AtomicInteger();
            AtomicReference<String> retryAuth = new AtomicReference<>();
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    if (request.getPath().equals("/auth/session/refresh")) { refreshes.incrementAndGet(); return new MockResponse().setResponseCode(500); }
                    if (request.getPath().equals("/promote")) return promotion(promoted);
                    if (("Bearer " + promoted.getAccessToken()).equals(request.getHeader("Authorization"))) {
                        retryAuth.set(request.getHeader("Authorization")); return new MockResponse().setBody("new revision");
                    }
                    arrived.countDown();
                    return release.await(8, TimeUnit.SECONDS)
                            ? new MockResponse().setResponseCode(401).addHeader("front-token", "remove") : new MockResponse().setResponseCode(504);
                }
            });
            ExecutorService workers = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> held = workers.submit(() -> request(url, null, "/old-remove"));
                assertTrue(arrived.await(5, TimeUnit.SECONDS));
                assertEquals(200, request(!url, null, "/promote"));
                SessionSnapshot newer = SuperTokens.getSessionSnapshot(context);
                assertSame(a.getOwner(), newer.getOwner());
                release.countDown();
                assertEquals(Integer.valueOf(200), held.get(5, TimeUnit.SECONDS));
                assertEquals(newer.getRevision(), SuperTokens.getSessionSnapshot(context).getRevision());
                assertEquals("Bearer " + promoted.getAccessToken(), retryAuth.get());
                assertEquals(0, refreshes.get());
            } finally { release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
        }
    }

    @Test public void interruptedFollowerDoesNotReleaseTheSharedFlight() throws Exception {
        SessionSnapshot a = install("A", "initial");
        SessionTokens rotated = tokens("A", "rotated");
        CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger refreshes = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                refreshes.incrementAndGet(); arrived.countDown();
                return release.await(8, TimeUnit.SECONDS) ? rotation(rotated) : new MockResponse().setResponseCode(504);
            }
        });
        ExecutorService leaderWorker = Executors.newSingleThreadExecutor();
        AtomicReference<SessionResolution> cancelled = new AtomicReference<>(), surviving = new AtomicReference<>();
        Thread follower = new Thread(() -> cancelled.set(SuperTokens.resolveAccessToken(context, a.getOwner(), true, null)), "cancelled-refresh-follower");
        Thread next = new Thread(() -> surviving.set(SuperTokens.resolveAccessToken(context, a.getOwner(), true, null)), "surviving-refresh-follower");
        try {
            Future<SessionResolution> leader = leaderWorker.submit(() -> SuperTokens.resolveAccessToken(context, a.getOwner(), true, null));
            assertTrue(arrived.await(5, TimeUnit.SECONDS));
            follower.start();
            awaitSharedFlightWait(follower);
            follower.interrupt(); follower.join(5000);
            assertFalse(follower.isAlive());
            assertNotNull(cancelled.get());
            assertEquals(SessionResolution.Status.REFRESH_FAILED, cancelled.get().getStatus());
            assertTrue(cancelled.get().getError().getCause() instanceof InterruptedException);
            next.start(); awaitSharedFlightWait(next);
            assertEquals(1, refreshes.get());
            release.countDown();
            assertTrue(leader.get(5, TimeUnit.SECONDS).isSuccess());
            next.join(5000); assertFalse(next.isAlive());
            assertTrue(surviving.get().isSuccess());
            assertEquals(rotated.getAccessToken(), surviving.get().getAccessToken());
            assertEquals(1, refreshes.get());
        } finally {
            release.countDown(); follower.interrupt(); next.interrupt();
            follower.join(5000); next.join(5000);
            leaderWorker.shutdownNow(); assertTrue(leaderWorker.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(follower.isAlive()); assertFalse(next.isAlive());
        }
    }

    @Test public void obsoleteRefresh401CannotClearReplacementButCurrentRefresh401DoesClear() throws Exception {
        SessionSnapshot a = install("A", "initial");
        CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (("Bearer " + a.getTokens().getRefreshToken()).equals(request.getHeader("Authorization"))) {
                    arrived.countDown();
                    if (!release.await(8, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(504);
                }
                return new MockResponse().setResponseCode(401).addHeader("front-token", "remove");
            }
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<SessionResolution> old = worker.submit(() -> SuperTokens.resolveAccessToken(context, a.getOwner(), true, null));
            assertTrue(arrived.await(5, TimeUnit.SECONDS));
            SessionSnapshot b = install("B", "replacement");
            release.countDown();
            assertEquals(SessionResolution.Status.STALE_OWNER, old.get(5, TimeUnit.SECONDS).getStatus());
            assertSame(b.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            assertEquals(b.getRevision(), SuperTokens.getSessionSnapshot(context).getRevision());
            SessionResolution current = SuperTokens.resolveAccessToken(context, b.getOwner(), true, null);
            assertEquals(SessionResolution.Status.NO_SESSION, current.getStatus());
            assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
            assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
            assertEquals(2, server.getRequestCount());
        } finally { release.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    private static void awaitSharedFlightWait(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            for (StackTraceElement frame : thread.getStackTrace()) {
                if (frame.getClassName().equals(RefreshCoordinator.Flight.class.getName()) && frame.getMethodName().equals("await")
                        && thread.getState() == Thread.State.TIMED_WAITING) return;
            }
            if (!thread.isAlive()) fail("follower exited before joining held flight");
            Thread.yield();
        }
        fail("follower never waited on held flight");
    }

    @Test public void missingSchemaAndPartialVersionedMetadataFailClosed() throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, 0);
        for (String missing : new String[]{SharedPreferencesSessionStorage.SCHEMA, SharedPreferencesSessionStorage.ADOPTION,
                SharedPreferencesSessionStorage.ACCESS_REVISION, SharedPreferencesSessionStorage.CONFIGURATION}) {
            install("A", "stored");
            assertTrue(preferences.edit().remove(missing).commit());
            restart();
            assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SuperTokens.getSessionSnapshot(context).getStatus());
            assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
        }
    }

    @Test public void expiredPersistedTupleRemainsRefreshable() throws Exception {
        SessionSnapshot a = install("A", "stored");
        SessionTokens expired = withExpiry(a.getTokens(), 1700000000L);
        SharedPreferences preferences = context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, 0);
        assertTrue(preferences.edit().putString("st-storage-item-" + Constants.ACCESS_TOKEN_PREFS_KEY, expired.getAccessToken())
                .putString(Constants.FRONT_TOKEN_PREFS_KEY, expired.getFrontToken())
                .putString(SharedPreferencesSessionStorage.EXPIRY, "1700000000000").commit());
        restart();
        SessionSnapshot hydrated = SuperTokens.getSessionSnapshot(context);
        assertEquals(SessionSnapshot.Status.AVAILABLE, hydrated.getStatus());
        assertEquals(a.getAdoptionId(), hydrated.getAdoptionId());
        SessionTokens rotated = tokens("A", "fresh");
        server.enqueue(rotation(rotated));
        assertEquals(rotated.getAccessToken(), SuperTokens.getAccessToken(context));
        assertEquals("Bearer " + a.getTokens().getRefreshToken(), server.takeRequest(1, TimeUnit.SECONDS).getHeader("Authorization"));
        assertSame(hydrated.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
    }

    @Test public void legacySecurePayloadGetterThrowsAfterAuthoritativeRefreshRemoval() throws Exception {
        SessionSnapshot before = install("A", "stored");
        SessionTokens expired = withExpiry(before.getTokens(), 1700000000L);
        SharedPreferences preferences = context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, 0);
        assertTrue(preferences.edit().putString("st-storage-item-" + Constants.ACCESS_TOKEN_PREFS_KEY, expired.getAccessToken())
                .putString(Constants.FRONT_TOKEN_PREFS_KEY, expired.getFrontToken())
                .putString(SharedPreferencesSessionStorage.EXPIRY, "1700000000000").commit());
        restart();
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("front-token", "remove"));
        try { SuperTokens.getAccessTokenPayloadSecurely(context); fail("Legacy error contract"); }
        catch (java.io.IOException expected) { assertEquals("Could not refresh session", expected.getMessage()); }
        assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        assertNull(SuperTokens.getAccessToken(context));
    }

    @Test public void invalidPersistedUidExpiryAndVersionFailClosed() throws Exception {
        for (String corruption : new String[]{"uid", "expiry", "version"}) {
            SessionSnapshot a = install("A", "stored");
            SessionTokens original = a.getTokens();
            JSONObject front = new JSONObject(new String(Base64.getDecoder().decode(original.getFrontToken()), StandardCharsets.UTF_8));
            String access = original.getAccessToken();
            if (corruption.equals("uid")) front.put("uid", "wrong-primary");
            if (corruption.equals("expiry")) front.put("ate", -1);
            if (corruption.equals("version")) {
                String[] parts = access.split("\\.");
                access = encode(new JSONObject().put("alg", "RS256").put("version", "99").toString(), true) + "." + parts[1] + "." + parts[2];
            }
            SharedPreferences preferences = context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, 0);
            assertTrue(preferences.edit().putString("st-storage-item-" + Constants.ACCESS_TOKEN_PREFS_KEY, access)
                    .putString(Constants.FRONT_TOKEN_PREFS_KEY, encode(front.toString(), false)).commit());
            restart();
            assertEquals(corruption, SessionSnapshot.Status.RECOVERY_REQUIRED, SuperTokens.getSessionSnapshot(context).getStatus());
            assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
        }
    }

    @Test public void versionedStoredFrontExpiryMustMatchAccessExpiry() throws Exception {
        SessionSnapshot a = install("A", "stored");
        JSONObject front = new JSONObject(new String(Base64.getDecoder().decode(a.getTokens().getFrontToken()), StandardCharsets.UTF_8));
        front.put("ate", a.getAccessTokenExpiry() + 1000);
        assertTrue(context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, 0).edit()
                .putString(Constants.FRONT_TOKEN_PREFS_KEY, encode(front.toString(), false)).commit());
        restart();
        assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SuperTokens.getSessionSnapshot(context).getStatus());
        assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
    }

    @Test public void versionedStoredFrontPayloadPrimaryMustMatchAccessPrimary() throws Exception {
        SessionSnapshot a = install("A", "stored");
        JSONObject front = new JSONObject(new String(Base64.getDecoder().decode(a.getTokens().getFrontToken()), StandardCharsets.UTF_8));
        front.getJSONObject("up").put("sub", "different-primary");
        assertTrue(context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, 0).edit()
                .putString(Constants.FRONT_TOKEN_PREFS_KEY, encode(front.toString(), false)).commit());
        restart();
        assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SuperTokens.getSessionSnapshot(context).getStatus());
        assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
    }

    @Test public void versionedStoredEmptyRefreshMustNotExportAvailableTuple() throws Exception {
        install("A", "stored");
        assertTrue(context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, 0).edit()
                .putString("st-storage-item-" + Constants.REFRESH_TOKEN_PREFS_KEY, "").commit());
        restart();
        assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SuperTokens.getSessionSnapshot(context).getStatus());
        assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
    }

    private void restart() throws Exception {
        SuperTokens.resetForTests();
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header").build();
    }

    private static SessionTokens withExpiry(SessionTokens original, long seconds) throws Exception {
        String[] parts = original.getAccessToken().split("\\.");
        JSONObject payload = new JSONObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        payload.put("exp", seconds);
        JSONObject front = new JSONObject(new String(Base64.getDecoder().decode(original.getFrontToken()), StandardCharsets.UTF_8));
        front.put("ate", seconds * 1000).put("up", payload);
        return new SessionTokens(parts[0] + "." + encode(payload.toString(), true) + "." + parts[2],
                original.getRefreshToken(), encode(front.toString(), false), original.getAntiCSRF());
    }

    private static String encode(String value, boolean url) {
        return (url ? Base64.getUrlEncoder().withoutPadding() : Base64.getEncoder()).encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
