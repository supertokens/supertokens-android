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
        }
    }
}
