package com.supertokens.session;

import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import okhttp3.mockwebserver.MockResponse;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionBackpressureContractTest extends SessionTestFixture {
    @Test public void cancellingOriginalCallDoesNotReleaseStrictRefreshForFollower() throws Exception {
        SessionSnapshot initial = install("cancelled", "before");
        SessionTokens rotated = tokens("cancelled", "after");
        java.util.concurrent.CountDownLatch refreshArrived = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger refreshes = new java.util.concurrent.atomic.AtomicInteger();
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) throws InterruptedException {
                if (request.getPath().equals("/auth/session/refresh")) {
                    refreshes.incrementAndGet(); refreshArrived.countDown();
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) return new MockResponse().setResponseCode(500);
                    return rotation(rotated);
                }
                return new MockResponse().setResponseCode(401);
            }
        });
        okhttp3.OkHttpClient base = new okhttp3.OkHttpClient();
        okhttp3.OkHttpClient strict = base.newBuilder().addInterceptor(new SuperTokensInterceptor(base)).build();
        okhttp3.Call call = strict.newCall(new okhttp3.Request.Builder().url(server.url("/profile")).build());
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Boolean> original = workers.submit(() -> {
                try (okhttp3.Response ignored = call.execute()) { return false; }
                catch (java.io.IOException expected) { return true; }
            });
            assertTrue(refreshArrived.await(2, java.util.concurrent.TimeUnit.SECONDS));
            call.cancel();
            RefreshCoordinator.Acquisition stillJoined = SessionState.acquireRefresh(initial.getOwner(), true, null,
                    (ctx, config, snapshot) -> { throw new AssertionError("Cancelled caller freed live flight"); },
                    initial.getRefreshTokenRevision());
            assertFalse(stillJoined.leader);
            assertFalse(stillJoined.flight.isDone());
            java.util.concurrent.Future<SessionResolution> follower = workers.submit(() ->
                    SuperTokens.resolveAccessToken(context, initial.getOwner(), false, initial.getTokens().getAccessToken()));
            release.countDown();
            assertTrue(follower.get(3, java.util.concurrent.TimeUnit.SECONDS).isSuccess());
            assertTrue(original.get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, refreshes.get());
            assertEquals(rotated.getRefreshToken(), SuperTokens.getSessionSnapshot(context).getTokens().getRefreshToken());
        } finally {
            release.countDown(); call.cancel(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            strict.dispatcher().cancelAll(); strict.connectionPool().evictAll();
            strict.dispatcher().executorService().shutdownNow();
        }
    }

    @Test public void retryAfterBackpressureIsBoundToOwnerForSecondsAndHttpDate() throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        String[] values = {"60", format.format(new Date(System.currentTimeMillis() + 60000L))};
        for (int i = 0; i < values.length; i++) {
            SessionSnapshot original = install("rate-limited-" + i, "before");
            int requests = server.getRequestCount();
            server.enqueue(new MockResponse().setResponseCode(i == 0 ? 429 : 503).addHeader("Retry-After", values[i]));
            SessionResolution failure = SuperTokens.resolveAccessToken(context, original.getOwner(), true, null);
            assertEquals(SessionResolution.Status.REFRESH_FAILED, failure.getStatus());
            for (int repeat = 0; repeat < 10; repeat++) {
                SessionResolution repeated = SuperTokens.resolveAccessToken(context, original.getOwner(), true, null);
                assertEquals(SessionResolution.Status.REFRESH_FAILED, repeated.getStatus());
                assertSame(failure.getError(), repeated.getError());
            }
            assertEquals(requests + 1, server.getRequestCount());
            assertSame(original, SuperTokens.getSessionSnapshot(context));
            SessionSnapshot replacement = install("replacement-" + i, "before");
            server.enqueue(rotation(tokens("replacement-" + i, "after")));
            assertTrue(SuperTokens.resolveAccessToken(context, replacement.getOwner(), true, null).isSuccess());
            assertEquals(requests + 2, server.getRequestCount());
        }
    }

    @Test public void equalLegacyClockMarkersCannotAuthorizeOldRevisionRemoval() throws Exception {
        install("equal-marker", "before");
        SharedPreferences disk = context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, 0);
        assertTrue(disk.edit().putString("st-storage-item-" + Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY,
                Long.toString(Long.MAX_VALUE)).commit());
        SuperTokens.resetForTests();
        new SuperTokens.Builder(context, server.url("/").toString()).build();
        SessionRequestTag old = SuperTokens.captureRequestTag(context);
        String oldMarker = SessionState.lastAccessTokenUpdate(old.getOwner());
        SessionTokens promoted = tokens("equal-marker", "after");
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("st-access-token", Collections.singletonList(promoted.getAccessToken()));
        headers.put("front-token", Collections.singletonList(promoted.getFrontToken()));
        assertTrue(SessionState.applyOrdinaryResponse(context, old, new SessionResponse(200, "OK", headers)).isSuccess());
        SessionSnapshot current = SuperTokens.getSessionSnapshot(context);
        assertEquals(oldMarker, SessionState.lastAccessTokenUpdate(current.getOwner()));
        assertTrue(current.getAccessTokenRevision() > old.getSnapshot().getAccessTokenRevision());
        SessionState.applyOrdinaryResponse(context, old, new SessionResponse(401, "expired",
                Collections.singletonMap("front-token", Collections.singletonList("remove"))));
        assertSame(current, SuperTokens.getSessionSnapshot(context));
        assertEquals(promoted.getAccessToken(), current.getTokens().getAccessToken());
    }
}
