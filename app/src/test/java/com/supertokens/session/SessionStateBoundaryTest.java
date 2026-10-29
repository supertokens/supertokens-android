package com.supertokens.session;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionStateBoundaryTest extends SessionTestFixture {
    @Test public void confirmedSignOutAfterRefreshClearsOnlyCapturedOwner() throws Exception {
        SessionSnapshot before = install("A", "before");
        SessionRequestTag signOut = SuperTokens.captureRequestTag(context);
        server.enqueue(rotation(tokens("A", "refreshed")));
        assertTrue(SuperTokens.resolveAccessToken(context, before.getOwner(), true, null).isSuccess());
        SessionResponse removal = new SessionResponse(200, "", java.util.Collections.singletonMap(
                "front-token", java.util.Collections.singletonList("remove")));
        assertTrue(SessionState.applyConfirmedSignOutResponse(context, signOut, removal).isSuccess());
        assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        SessionSnapshot replacement = install("B", "new");
        assertEquals(SessionCommitResult.Status.STALE_OWNER,
                SessionState.applyConfirmedSignOutResponse(context, signOut, removal).getStatus());
        assertSame(replacement, SuperTokens.getSessionSnapshot(context));
    }

    @Test public void cookieSwitchDuringRefreshIsNotSpeculativelyAdopted() throws Exception {
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .tokenTransferMethod("cookie").reconfigure().isSuccess());
        server.enqueue(new MockResponse().addHeader("front-token", cookieFront("alice")));
        assertEquals(200, request(false, null, "/login"));
        SessionSnapshot original = SuperTokens.getSessionSnapshot(context);
        SessionRequestTag tag = SuperTokens.captureRequestTag(context);
        RefreshCoordinator.Acquisition refresh = SessionState.acquireRefresh(original.getOwner(), true,
                null, SuperTokensHttpURLConnection.refreshTransport(), original.getRefreshTokenRevision());
        assertTrue(refresh.leader);
        assertTrue(SessionState.startRefresh(refresh.flight));
        SessionResponse other = new SessionResponse(200, "", java.util.Collections.singletonMap(
                "front-token", java.util.Collections.singletonList(cookieFront("bob"))));
        SessionState.applyOrdinaryResponse(context, tag, other);
        assertSame(original, SuperTokens.getSessionSnapshot(context));
        assertNull(tag.getAcceptedSnapshot());
        SessionState.completeRefresh(context, refresh.flight, null, new java.io.IOException("transport ended"));
    }

    @Test public void unconfirmedRemovalAfterRefreshRemainsRevisionFenced() throws Exception {
        SessionSnapshot before = install("A", "before");
        SessionRequestTag ordinary = SuperTokens.captureRequestTag(context);
        SessionRequestTag failedSignOut = SuperTokens.captureRequestTag(context);
        server.enqueue(rotation(tokens("A", "refreshed")));
        assertTrue(SuperTokens.resolveAccessToken(context, before.getOwner(), true, null).isSuccess());
        SessionSnapshot refreshed = SuperTokens.getSessionSnapshot(context);
        java.util.Map<String, java.util.List<String>> headers = java.util.Collections.singletonMap(
                "front-token", java.util.Collections.singletonList("remove"));
        assertTrue(SessionState.applyOrdinaryResponse(context, ordinary,
                new SessionResponse(200, "", headers)).isSuccess());
        assertTrue(SessionState.applyConfirmedSignOutResponse(context, failedSignOut,
                new SessionResponse(401, "", headers)).isSuccess());
        assertSame(refreshed, SuperTokens.getSessionSnapshot(context));
        assertNull(ordinary.getAcceptedSnapshot());
        assertNull(failedSignOut.getAcceptedSnapshot());
    }

    @Test public void currentRemovalRetiresInFlightRefresh() throws Exception {
        SessionSnapshot before = install("A", "before");
        SessionRequestTag tag = SuperTokens.captureRequestTag(context);
        RefreshCoordinator.Acquisition refresh = SessionState.acquireRefresh(before.getOwner(), true,
                null, SuperTokensHttpURLConnection.refreshTransport(), before.getRefreshTokenRevision());
        assertTrue(refresh.leader);
        assertTrue(SessionState.startRefresh(refresh.flight));
        server.enqueue(new MockResponse().addHeader("front-token", "remove"));
        assertEquals(200, request(false, tag, "/logout"));
        assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        assertEquals(SessionResolution.Status.STALE_OWNER, refresh.flight.await().getStatus());
        SessionSnapshot replacement = install("B", "new");
        SessionTokens lateTokens = tokens("A", "late");
        java.util.Map<String, java.util.List<String>> headers = new java.util.HashMap<>();
        headers.put("front-token", java.util.Collections.singletonList(lateTokens.getFrontToken()));
        headers.put("st-access-token", java.util.Collections.singletonList(lateTokens.getAccessToken()));
        headers.put("st-refresh-token", java.util.Collections.singletonList(lateTokens.getRefreshToken()));
        SessionResponse late = new SessionResponse(200, "", headers);
        SessionState.completeRefresh(context, refresh.flight, late, null);
        assertSame(replacement, SuperTokens.getSessionSnapshot(context));
    }

    @Test public void cookieUnauthorizedDeliveredAfterRefreshReusesCompletedLineage() throws Exception {
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .tokenTransferMethod("cookie").reconfigure().isSuccess());
        String front = tokens("cookie", "before").getFrontToken();
        server.enqueue(new MockResponse().addHeader("front-token", front));
        assertEquals(200, request(false, null, "/login"));
        SessionSnapshot initial = SuperTokens.getSessionSnapshot(context);
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger profiles = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (request.getPath().equals("/auth/session/refresh")) {
                    refreshes.incrementAndGet();
                    return new MockResponse().addHeader("front-token", front);
                }
                if (profiles.incrementAndGet() == 1) {
                    arrived.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(500);
                    return new MockResponse().setResponseCode(401);
                }
                return new MockResponse().setResponseCode(200);
            }
        });
        ExecutorService workers = Executors.newSingleThreadExecutor();
        CookieHandler previous = CookieHandler.getDefault();
        CookieHandler.setDefault(new CookieManager());
        try {
            Future<Integer> held = workers.submit(() -> request(false, null, "/profile"));
            assertTrue(arrived.await(5, TimeUnit.SECONDS));
            assertTrue(SuperTokens.resolveAccessToken(context, initial.getOwner(), true, null).isSuccess());
            release.countDown();
            assertEquals(200, (int) held.get(5, TimeUnit.SECONDS));
            assertEquals("Late cookie 401 must reuse completed refresh", 1, refreshes.get());
        } finally {
            release.countDown(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            CookieHandler.setDefault(previous);
        }
    }

    @Test public void forceAcquisitionCoalescesOnlyRefreshLineageNotOrdinaryPromotion() throws Exception {
        SessionSnapshot original = install("A", "before");
        long captured = original.getRefreshTokenRevision();
        server.enqueue(promotion(tokens("A", "promotion")));
        assertEquals(200, request(false, null, "/profile"));
        RefreshCoordinator.Acquisition acquired = SessionState.acquireRefresh(original.getOwner(), true,
                null, SuperTokensHttpURLConnection.refreshTransport(), captured);
        assertTrue("ordinary promotion must not satisfy force refresh", acquired.leader);
        assertTrue(SessionState.startRefresh(acquired.flight));
        server.enqueue(rotation(tokens("A", "refreshed")));
        SessionResponse response = SuperTokensHttpURLConnection.refresh(context,
                acquired.flight.configuration, acquired.flight.captured);
        SessionState.completeRefresh(context, acquired.flight, response, null);
        assertTrue(acquired.flight.await().isSuccess());
        RefreshCoordinator.Acquisition completed = SessionState.acquireRefresh(original.getOwner(), true,
                null, SuperTokensHttpURLConnection.refreshTransport(), captured);
        assertFalse(completed.leader);
        assertTrue(completed.immediate.isSuccess());
        assertEquals(2, server.getRequestCount());
    }

    @Test public void callbackPreparationPastDeadlineNeverDispatchesRefresh() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
            SuperTokens.Builder builder = new SuperTokens.Builder(context, server.url("/").toString())
                    .customHeaderProvider(type -> { clock.set(TimeUnit.SECONDS.toNanos(11)); return null; });
            assertTrue(builder.reconfigure().isSuccess());
            SessionSnapshot before = install("deadline", "before-" + mode);
            RefreshDeadline.setClockForTests(clock::get);
            int count = server.getRequestCount();
            try {
                if (mode == 0) {
                    SessionResolution result = SuperTokens.resolveAccessToken(context, before.getOwner(), true, null);
                    assertEquals(SessionResolution.Status.REFRESH_FAILED, result.getStatus());
                    assertTrue(result.getError() instanceof java.net.SocketTimeoutException);
                    assertEquals(count, server.getRequestCount());
                } else {
                    server.enqueue(new MockResponse().setResponseCode(401));
                    okhttp3.OkHttpClient base = new okhttp3.OkHttpClient.Builder().build();
                    okhttp3.OkHttpClient transport = base.newBuilder().addInterceptor(mode == 1
                            ? new SuperTokensInterceptor(base) : new SuperTokensInterceptor()).build();
                    try { request(transport, null, "/profile"); fail("callback deadline"); }
                    catch (java.net.SocketTimeoutException expected) { }
                    finally { transport.connectionPool().evictAll(); transport.dispatcher().executorService().shutdownNow(); }
                    assertEquals(count + 1, server.getRequestCount());
                }
                assertSame(before.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            } finally { RefreshDeadline.setClockForTests(null); }
        }
    }

    @Test public void resolverBeforeInitReturnsTypedNotConfigured() {
        SuperTokens.resetForTests();
        assertEquals(SessionResolution.Status.NOT_CONFIGURED,
                SuperTokens.resolveAccessToken(context, null, false, null).getStatus());
    }

    @Test public void invalidatedBeforeTransportStartDoesNotLeakFlightCapacity() throws Exception {
        for (int i = 0; i < 16; i++) {
            SessionSnapshot original = install("A", "before-" + i);
            RefreshCoordinator.Acquisition acquired = SessionState.acquireRefresh(original.getOwner(), true,
                    null, (ctx, config, snapshot) -> { throw new AssertionError("must not dispatch"); },
                    original.getRevision());
            assertTrue(acquired.leader);
            install("B", "replacement-" + i);
            assertFalse(SessionState.startRefresh(acquired.flight));
            assertEquals(SessionResolution.Status.STALE_OWNER, acquired.flight.await().getStatus());
        }
        SessionSnapshot live = install("final", "before");
        server.enqueue(rotation(tokens("final", "after")));
        assertTrue(SuperTokens.resolveAccessToken(context, live.getOwner(), true, null).isSuccess());
        assertEquals(1, server.getRequestCount());
    }

    @Test public void defaultCallbackSubclassesAreNotIdempotentAndReconfigureRetiresOwner() throws Exception {
        CustomHeaderProvider first = new CustomHeaderProvider.DefaultCustomHeaderMapper() {};
        CustomHeaderProvider second = new CustomHeaderProvider.DefaultCustomHeaderMapper() {};
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .customHeaderProvider(first).reconfigure().isSuccess());
        SessionSnapshot prior = install("A", "before");
        try {
            new SuperTokens.Builder(context, server.url("/").toString()).customHeaderProvider(second).build();
            fail("Different callback must not be silently ignored");
        } catch (IllegalStateException expected) { }
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .customHeaderProvider(second).reconfigure().isSuccess());
        assertFalse(prior.isCurrent());
        assertEquals(SessionResolution.Status.STALE_OWNER,
                SuperTokens.resolveAccessToken(context, prior.getOwner(), false, null).getStatus());
        assertEquals(SessionCommitResult.Status.STALE_OWNER,
                SuperTokens.installSession(context, prior.getOwner(), tokens("B", "after")).getStatus());
        assertEquals(SessionCommitResult.Status.STALE_OWNER,
                SuperTokens.clearSession(context, prior.getOwner()).getStatus());
    }

    @Test public void oldFullIdentityReplacementCannotBypassNewerRevision() throws Exception {
        SessionSnapshot before = install("A", "before");
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SessionTokens other = tokens("B", "late-login");
        SessionTokens promoted = tokens("A", "promoted");
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (request.getPath().equals("/old-login")) {
                    arrived.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(500);
                    return rotation(other);
                }
                return promotion(promoted);
            }
        });
        ExecutorService workers = Executors.newSingleThreadExecutor();
        SessionRequestTag oldTag = SuperTokens.captureRequestTag(context);
        try {
            Future<Integer> old = workers.submit(() -> request(false, oldTag, "/old-login"));
            assertTrue(arrived.await(5, TimeUnit.SECONDS));
            assertEquals(200, request(false, null, "/promotion"));
            release.countDown();
            assertEquals(200, (int) old.get(5, TimeUnit.SECONDS));
            SessionSnapshot actual = SuperTokens.getSessionSnapshot(context);
            assertSame(before.getOwner(), actual.getOwner());
            assertEquals(promoted.getAccessToken(), actual.getTokens().getAccessToken());
            assertNull(oldTag.getAcceptedSnapshot());
        } finally {
            release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void absentSignOutEmitsLegacyEventOnlyForCurrentAbsentOwner() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .eventHandler(event -> { if (event == EventHandler.EventType.SIGN_OUT) calls.incrementAndGet(); })
                .reconfigure().isSuccess());
        SessionRequestTag absent = SuperTokens.captureRequestTag(context);
        SuperTokens.signOut(context);
        assertEquals(1, calls.get());
        install("B", "new");
        try { SessionState.notifyAbsentSignOut(absent); fail("stale absent observation"); }
        catch (StaleSessionException expected) { }
        assertEquals(1, calls.get());
        assertEquals(0, server.getRequestCount());
    }

    @Test public void cookieFrontOnlyEstablishmentRefreshAndRemovalRemainSupported() throws Exception {
        CookieHandler old = CookieHandler.getDefault();
        try {
            CookieHandler.setDefault(new CookieManager());
            assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                    .tokenTransferMethod("cookie").reconfigure().isSuccess());
            SessionTokens metadata = tokens("cookie", "before");
            server.enqueue(new MockResponse().setBody("login").addHeader("front-token", metadata.getFrontToken())
                    .addHeader("Set-Cookie", "sAccessToken=cookie-before; Path=/"));
            assertEquals(200, request(true, null, "/login"));
            SessionSnapshot cookie = SuperTokens.getSessionSnapshot(context);
            assertEquals(SessionSnapshot.Status.AVAILABLE, cookie.getStatus());
            assertNull(cookie.getTokens());
            server.enqueue(new MockResponse().setBody("refreshed").addHeader("front-token", tokens("cookie", "after").getFrontToken()));
            assertTrue(SuperTokens.attemptRefreshingSession(context));
            assertSame(cookie.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            server.enqueue(new MockResponse().setBody("logout").addHeader("front-token", "remove"));
            assertEquals(200, request(true, null, "/logout"));
            assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        } finally { CookieHandler.setDefault(old); }
    }

    @Test public void cookieAccountSwitchReplacesOwnerAndFencesOldResponse() throws Exception {
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .tokenTransferMethod("cookie").reconfigure().isSuccess());
        String first = cookieFront("alice");
        String second = cookieFront("bob");
        server.enqueue(new MockResponse().addHeader("front-token", first));
        assertEquals(200, request(false, null, "/login"));
        SessionSnapshot original = SuperTokens.getSessionSnapshot(context);
        SessionRequestTag stale = SuperTokens.captureRequestTag(context);
        SessionRequestTag switchTag = SuperTokens.captureRequestTag(context);
        server.enqueue(new MockResponse().addHeader("front-token", second));
        assertEquals(200, request(false, switchTag, "/login"));
        SessionSnapshot switched = SuperTokens.getSessionSnapshot(context);
        assertEquals("bob", switched.getUserId());
        assertFalse(original.isCurrent());
        assertSame(switched, switchTag.getAcceptedSnapshot());
        SessionResponse old = new SessionResponse(200, "", java.util.Collections.singletonMap(
                "front-token", java.util.Collections.singletonList("remove")));
        assertEquals(SessionCommitResult.Status.STALE_OWNER,
                SessionState.applyOrdinaryResponse(context, stale, old).getStatus());
        assertSame(switched, SuperTokens.getSessionSnapshot(context));
    }

    @Test public void staleCookieSwitchAndRemovalCannotOverwriteNewerPromotion() throws Exception {
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .tokenTransferMethod("cookie").reconfigure().isSuccess());
        server.enqueue(new MockResponse().addHeader("front-token", cookieFront("alice")));
        assertEquals(200, request(false, null, "/login"));
        SessionRequestTag staleSwitch = SuperTokens.captureRequestTag(context);
        SessionRequestTag staleRemoval = SuperTokens.captureRequestTag(context);
        server.enqueue(new MockResponse().addHeader("front-token", cookieFront("alice")));
        assertEquals(200, request(false, null, "/profile"));
        SessionSnapshot promoted = SuperTokens.getSessionSnapshot(context);
        SessionResponse other = new SessionResponse(200, "", java.util.Collections.singletonMap(
                "front-token", java.util.Collections.singletonList(cookieFront("bob"))));
        SessionResponse removal = new SessionResponse(200, "", java.util.Collections.singletonMap(
                "front-token", java.util.Collections.singletonList("remove")));
        assertTrue(SessionState.applyOrdinaryResponse(context, staleSwitch, other).isSuccess());
        assertTrue(SessionState.applyOrdinaryResponse(context, staleRemoval, removal).isSuccess());
        assertSame(promoted, SuperTokens.getSessionSnapshot(context));
        assertNull(staleSwitch.getAcceptedSnapshot());
        assertNull(staleRemoval.getAcceptedSnapshot());
    }

    private static String cookieFront(String user) throws Exception {
        String value = new org.json.JSONObject().put("uid", user).put("ate", 4102444800000L)
                .put("up", new org.json.JSONObject()).toString();
        return java.util.Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test public void duplicateCredentialHeadersNeverPartiallyMutateTuple() throws Exception {
        SessionSnapshot before = install("A", "before");
        SessionTokens after = tokens("A", "after");
        server.enqueue(promotion(after).addHeader("st-access-token", after.getAccessToken()));
        assertEquals(200, request(false, null, "/profile"));
        assertSame(before, SuperTokens.getSessionSnapshot(context));
    }

    @Test public void configurationScopeIncludesSchemePortAndDomainPath() throws Exception {
        assertFalse(Utils.shouldDoInterceptionBasedOnUrl("https://example.com/profile", "http://example.com", null));
        assertFalse(Utils.shouldDoInterceptionBasedOnUrl("https://example.com:444/profile", "https://example.com", null));
        assertFalse(Utils.shouldDoInterceptionBasedOnUrl("https://example.com/other", "https://example.com/base", null));
        assertTrue(Utils.shouldDoInterceptionBasedOnUrl("https://example.com/base/profile", "https://example.com/base", null));
        new SuperTokens.Builder(context, "https://example.com/base").reconfigure();
        String first = SuperTokens.getSessionSnapshot(context).getConfigurationId();
        new SuperTokens.Builder(context, "https://example.com/other").reconfigure();
        assertNotEquals(first, SuperTokens.getSessionSnapshot(context).getConfigurationId());
    }
}
