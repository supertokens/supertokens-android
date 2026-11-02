package com.supertokens.session;

import org.junit.Test;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class LegacyEntryPointStateMatrixTest extends SessionTestFixture {
    private void recovery() throws Exception {
        assertTrue(preferenceStore.preferences.edit()
                .putString(SharedPreferencesSessionStorage.STATUS, "recovery").commit());
        restart(true);
        assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SessionState.snapshot().getStatus());
    }

    @Test public void r20RecoveryRefreshBehavesAsAbsent() throws Exception {
        recovery();
        assertFalse(SuperTokens.attemptRefreshingSession(context));
    }

    @Test public void r20RecoverySignOutBehavesAsAbsent() throws Exception {
        recovery();
        SuperTokens.signOut(context);
    }

    private void noSessionGetters() throws Exception {
        assertFalse(SuperTokens.doesSessionExist(context));
        assertNull(SuperTokens.getAccessToken(context));
        try {
            SuperTokens.getUserId(context);
            fail("getUserId without a session");
        } catch (IllegalStateException expected) { }
        try {
            SuperTokens.getAccessTokenPayloadSecurely(context);
            fail("payload without a session");
        } catch (IllegalStateException expected) { }
    }

    @Test public void absentGettersBehaveAsNoSession() throws Exception {
        noSessionGetters();
    }

    @Test public void recoveryGettersBehaveAsNoSession() throws Exception {
        recovery();
        noSessionGetters();
    }

    @Test public void absentRefreshAndSignOut() throws Exception {
        assertFalse(SuperTokens.attemptRefreshingSession(context));
        SuperTokens.signOut(context);
    }

    @Test public void availableGettersAndRefresh() throws Exception {
        SessionTokens initial = tokens("matrix", "before");
        install("matrix", "before");
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return rotation(initial);
            }
        });
        assertTrue(SuperTokens.doesSessionExist(context));
        assertEquals("primary-user", SuperTokens.getUserId(context));
        assertNotNull(SuperTokens.getAccessToken(context));
        assertNotNull(SuperTokens.getAccessTokenPayloadSecurely(context));
        assertTrue(SuperTokens.attemptRefreshingSession(context));
    }

    @Test public void availableSignOutSendsCurrentCredentialsAndClearsSession() throws Exception {
        SessionTokens initial = tokens("signout", "before");
        install("signout", "before");
        server.enqueue(new MockResponse().addHeader("front-token", "remove").setBody("{\"status\":\"OK\"}"));

        SuperTokens.signOut(context);

        RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(sent);
        assertEquals("POST", sent.getMethod());
        assertTrue(sent.getPath().contains("/signout"));
        assertEquals("Bearer " + initial.getAccessToken(), sent.getHeader("Authorization"));
        assertEquals(initial.getAntiCSRF(), sent.getHeader("anti-csrf"));
        assertFalse(SuperTokens.doesSessionExist(context));
        assertEquals(1, server.getRequestCount());
    }

    @Test public void expiredSignOutRefreshesBeforeSendingSignOut() throws Exception {
        seedLegacy(tokens("expired-signout", "primary-user", 4102444800000L, 1, 5));
        restart(false);
        SessionTokens fresh = tokens("expired-signout", "after");
        server.enqueue(rotation(fresh));
        server.enqueue(new MockResponse().addHeader("front-token", "remove").setBody("{\"status\":\"OK\"}"));

        SuperTokens.signOut(context);

        RecordedRequest refresh = server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest signOut = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(refresh);
        assertNotNull(signOut);
        assertTrue(refresh.getPath().contains("/session/refresh"));
        assertTrue(signOut.getPath().contains("/signout"));
        assertEquals("Bearer " + fresh.getAccessToken(), signOut.getHeader("Authorization"));
        assertFalse(SuperTokens.doesSessionExist(context));
        assertEquals(2, server.getRequestCount());
    }

    @Test public void terminalUnavailableGettersRefreshAndSignOut() throws Exception {
        install("terminal", "before");
        FrontToken.setToken(context, null);
        assertEquals(SessionSnapshot.Status.UNAVAILABLE, SuperTokens.getSessionSnapshot(context).getStatus());
        noSessionGetters();
        assertFalse(SuperTokens.attemptRefreshingSession(context));
        SuperTokens.signOut(context);
        assertEquals(0, server.getRequestCount());
    }

    @Test public void expiredSessionGettersRefreshAgainstCompleteResponse() throws Exception {
        SessionTokens expired = tokens("expired", "primary-user", 4102444800000L, 1, 5);
        seedLegacy(expired);
        restart(false);
        SessionTokens fresh = tokens("expired", "after");
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) { return rotation(fresh); }
        });
        assertTrue(SuperTokens.doesSessionExist(context));
        assertEquals("primary-user", SuperTokens.getUserId(context));
        assertEquals(fresh.getAccessToken(), SuperTokens.getAccessToken(context));
        assertNotNull(SuperTokens.getAccessTokenPayloadSecurely(context));
        assertEquals(1, server.getRequestCount());
    }

    @Test public void pendingReplacementLegacyEntrypointsCannotExportOrMutate() throws Exception {
        SessionSnapshot old = install("pending", "before");
        BlockingStorage storage = new BlockingStorage(new SharedPreferencesSessionStorage());
        SessionState.setStorageForTests(storage);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<SessionCommitResult> writer = worker.submit(() ->
                    SuperTokens.installSession(context, old.getOwner(), tokens("replacement", "after")));
            assertTrue(storage.entered.await(5, TimeUnit.SECONDS));
            assertEquals(SessionSnapshot.Status.UNAVAILABLE, SuperTokens.getSessionSnapshot(context).getStatus());
            noSessionGetters();
            assertFalse(SuperTokens.attemptRefreshingSession(context));
            SuperTokens.signOut(context);
            assertFalse(writer.isDone());
            assertEquals(0, server.getRequestCount());
            storage.release.countDown();
            assertTrue(writer.get(5, TimeUnit.SECONDS).isSuccess());
        } finally {
            storage.release.countDown();
            worker.shutdownNow();
            SessionState.setStorageForTests(new SharedPreferencesSessionStorage());
        }
    }

    @Test public void expiredExplicitRefreshAndSignOut() throws Exception {
        SessionTokens expired = tokens("expired", "primary-user", 4102444800000L, 1, 5);
        seedLegacy(expired);
        restart(false);
        SessionTokens fresh = tokens("expired", "after");
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().contains("/session/refresh")) return rotation(fresh);
                return new MockResponse().addHeader("front-token", "remove").setBody("{\"status\":\"OK\"}");
            }
        });
        assertTrue(SuperTokens.attemptRefreshingSession(context));
        SuperTokens.signOut(context);
        assertFalse(SuperTokens.doesSessionExist(context));
        assertEquals(2, server.getRequestCount());
    }

    @Test public void eachExpiredGetterStartsFromAnExpiredSession() throws Exception {
        for (int caller = 0; caller < 4; caller++) {
            preferenceStore.preferences.edit().clear().commit();
            SessionTokens expired = tokens("expired-" + caller, "primary-user", 4102444800000L, 1, 5);
            seedLegacy(expired);
            restart(false);
            SessionTokens fresh = tokens("expired-" + caller, "after");
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) { return rotation(fresh); }
            });
            int before = server.getRequestCount();
            switch (caller) {
                case 0: assertTrue(SuperTokens.doesSessionExist(context)); break;
                case 1: assertEquals(fresh.getAccessToken(), SuperTokens.getAccessToken(context)); break;
                case 2: assertNotNull(SuperTokens.getAccessTokenPayloadSecurely(context)); break;
                case 3: assertEquals("primary-user", SuperTokens.getUserId(context)); break;
                default: throw new AssertionError(caller);
            }
            assertEquals("caller=" + caller, caller == 3 ? 0 : 1, server.getRequestCount() - before);
            if (caller != 3) {
                RecordedRequest refresh = server.takeRequest(1, TimeUnit.SECONDS);
                assertNotNull(refresh);
                assertTrue(refresh.getPath().contains("/session/refresh"));
                assertEquals(fresh.getAccessToken(), SessionState.snapshot().getTokens().getAccessToken());
            } else {
                assertEquals(expired.getAccessToken(), SessionState.snapshot().getTokens().getAccessToken());
            }
        }
    }

    private enum MatrixState { ABSENT, AVAILABLE, EXPIRED, RECOVERY, PENDING, TERMINAL }
    private enum MatrixCaller { EXISTS, ACCESS, PAYLOAD, USER_ID, REFRESH, SIGN_OUT }

    private void runIndependentRow(MatrixState state, MatrixCaller caller) throws Exception {
        String row = state + "/" + caller;
        SuperTokens.resetForTests();
        assertTrue(preferenceStore.preferences.edit().clear().commit());
        SessionTokens initial = state == MatrixState.EXPIRED
                ? tokens("independent", "primary-user", 4102444800000L, 1, 5)
                : tokens("independent", "before");
        SessionTokens fresh = tokens("independent", "after");
        if (state == MatrixState.EXPIRED) {
            seedLegacy(initial);
        } else if (state == MatrixState.RECOVERY) {
            assertTrue(preferenceStore.preferences.edit()
                    .putString(SharedPreferencesSessionStorage.STATUS, "recovery").commit());
        }
        java.util.List<EventHandler.EventType> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        new SuperTokens.Builder(context, server.url("/").toString())
                .tokenTransferMethod("header").eventHandler(events::add).build();
        if (state == MatrixState.AVAILABLE || state == MatrixState.PENDING || state == MatrixState.TERMINAL) {
            install("independent", "before");
            if (state == MatrixState.TERMINAL) FrontToken.setToken(context, null);
        }
        events.clear();
        BlockingStorage storage = null;
        ExecutorService worker = null;
        Future<SessionCommitResult> writer = null;
        try {
            if (state == MatrixState.PENDING) {
                SessionSnapshot old = SessionState.snapshot();
                storage = new BlockingStorage(new SharedPreferencesSessionStorage());
                SessionState.setStorageForTests(storage);
                worker = Executors.newSingleThreadExecutor();
                writer = worker.submit(() -> SuperTokens.installSession(context, old.getOwner(),
                        tokens("replacement", "after")));
                assertTrue(row, storage.entered.await(5, TimeUnit.SECONDS));
            }
            SessionSnapshot.Status expectedState = state == MatrixState.ABSENT ? SessionSnapshot.Status.ABSENT
                    : state == MatrixState.RECOVERY ? SessionSnapshot.Status.RECOVERY_REQUIRED
                    : state == MatrixState.PENDING || state == MatrixState.TERMINAL
                    ? SessionSnapshot.Status.UNAVAILABLE : SessionSnapshot.Status.AVAILABLE;
            SessionSnapshot beforeCall = SessionState.snapshot();
            assertEquals(row, expectedState, beforeCall.getStatus());
            boolean available = state == MatrixState.AVAILABLE || state == MatrixState.EXPIRED;
            boolean refreshes = available && (caller == MatrixCaller.REFRESH
                    || state == MatrixState.EXPIRED && caller != MatrixCaller.USER_ID);
            boolean signsOut = available && caller == MatrixCaller.SIGN_OUT;
            int before = server.getRequestCount();
            if (refreshes) server.enqueue(rotation(fresh).addHeader("anti-csrf", fresh.getAntiCSRF()));
            if (signsOut) server.enqueue(new MockResponse().addHeader("front-token", "remove")
                    .setBody("{\"status\":\"OK\"}"));

            switch (caller) {
                case EXISTS:
                    assertEquals(row, available, SuperTokens.doesSessionExist(context));
                    break;
                case ACCESS:
                    assertEquals(row, !available ? null : refreshes ? fresh.getAccessToken() : initial.getAccessToken(),
                            SuperTokens.getAccessToken(context));
                    break;
                case PAYLOAD:
                    if (available) {
                        assertEquals(row, "primary-user", SuperTokens.getAccessTokenPayloadSecurely(context).getString("sub"));
                    } else {
                        try { SuperTokens.getAccessTokenPayloadSecurely(context); fail(row); }
                        catch (IllegalStateException expected) { }
                    }
                    break;
                case USER_ID:
                    if (available) assertEquals(row, "primary-user", SuperTokens.getUserId(context));
                    else {
                        try { SuperTokens.getUserId(context); fail(row); }
                        catch (IllegalStateException expected) { }
                    }
                    break;
                case REFRESH:
                    assertEquals(row, available, SuperTokens.attemptRefreshingSession(context));
                    break;
                case SIGN_OUT:
                    SuperTokens.signOut(context);
                    break;
            }

            assertEquals(row, (refreshes ? 1 : 0) + (signsOut ? 1 : 0), server.getRequestCount() - before);
            if (refreshes) {
                RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
                assertNotNull(row, sent);
                assertTrue(row, sent.getPath().contains("/session/refresh"));
                assertEquals(row, "Bearer " + initial.getRefreshToken(), sent.getHeader("Authorization"));
                assertEquals(row, initial.getAntiCSRF(), sent.getHeader("anti-csrf"));
            }
            if (signsOut) {
                RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
                assertNotNull(row, sent);
                assertTrue(row, sent.getPath().contains("/signout"));
                SessionTokens wire = refreshes ? fresh : initial;
                assertEquals(row, "Bearer " + wire.getAccessToken(), sent.getHeader("Authorization"));
                assertEquals(row, wire.getAntiCSRF(), sent.getHeader("anti-csrf"));
            }
            if (!available) {
                if (state != MatrixState.PENDING && caller == MatrixCaller.REFRESH)
                    assertEquals(row, java.util.Collections.singletonList(EventHandler.EventType.UNAUTHORISED), events);
                else if (state != MatrixState.PENDING && caller == MatrixCaller.SIGN_OUT)
                    assertEquals(row, java.util.Collections.singletonList(EventHandler.EventType.SIGN_OUT), events);
                else assertTrue(row, events.isEmpty());
            } else if (refreshes) {
                java.util.List<EventHandler.EventType> expected = new java.util.ArrayList<>();
                expected.add(EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED);
                expected.add(EventHandler.EventType.REFRESH_SESSION);
                if (signsOut) expected.add(EventHandler.EventType.SIGN_OUT);
                assertEquals(row, expected, events);
            } else if (signsOut) {
                assertEquals(row, java.util.Collections.singletonList(EventHandler.EventType.SIGN_OUT), events);
            } else {
                assertTrue(row, events.isEmpty());
            }

            SessionSnapshot after = SessionState.snapshot();
            if (available && !signsOut) {
                assertEquals(row, SessionSnapshot.Status.AVAILABLE, after.getStatus());
                assertEquals(row, (refreshes ? fresh : initial).getAccessToken(), after.getTokens().getAccessToken());
            } else if (signsOut || state == MatrixState.RECOVERY && caller == MatrixCaller.SIGN_OUT) {
                assertEquals(row, SessionSnapshot.Status.ABSENT, after.getStatus());
            } else if (state == MatrixState.PENDING) {
                assertEquals(row, SessionSnapshot.Status.UNAVAILABLE, after.getStatus());
                assertNull(row, after.getTokens());
                assertEquals(row, beforeCall.getOwner(), after.getOwner());
                assertEquals(row, beforeCall.getRevision(), after.getRevision());
                assertNull(row, Utils.getTokenForHeaderAuth(Utils.TokenType.ACCESS, context));
                assertNull(row, Utils.getAuthorizationHeaderIfExists(true, context));
                assertFalse(row, writer.isDone());
            }

            if (state != MatrixState.PENDING && !(state == MatrixState.EXPIRED && caller == MatrixCaller.USER_ID)) {
                server.enqueue(new MockResponse());
                assertEquals(row, 200, request(false, null, "/matrix-next"));
                RecordedRequest next = server.takeRequest(1, TimeUnit.SECONDS);
                assertNotNull(row, next);
                SessionTokens projected = available && !signsOut ? (refreshes ? fresh : initial) : null;
                assertEquals(row, projected == null ? null : "Bearer " + projected.getAccessToken(), next.getHeader("Authorization"));
                assertEquals(row, projected == null ? null : projected.getAntiCSRF(), next.getHeader("anti-csrf"));
            }
        } finally {
            if (storage != null) {
                storage.release.countDown();
                try { if (writer != null) assertTrue(row, writer.get(5, TimeUnit.SECONDS).isSuccess()); }
                finally {
                    if (worker != null) worker.shutdownNow();
                    SessionState.setStorageForTests(new SharedPreferencesSessionStorage());
                }
            }
        }
    }

    @Test public void independentlySeedEveryLegacyEntryPointForEachState() throws Exception {
        for (MatrixState state : MatrixState.values()) {
            for (MatrixCaller caller : MatrixCaller.values()) runIndependentRow(state, caller);
        }
    }
}
