package com.supertokens.session;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExposedCookieDirectSignOutTest extends SessionTestFixture {
    @Test public void refreshThenSignOutDiscardsRegeneratedAccessBeforeRemoval() throws Exception {
        CookieHandler.setDefault(new CookieManager());
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .tokenTransferMethod("cookie").reconfigure().isSuccess());
        server.enqueue(exposed("login"));
        assertEquals(200, request(true, null, "/login"));
        SessionSnapshot login = SuperTokens.getSessionSnapshot(context);
        server.enqueue(exposed("refresh"));
        assertTrue(SuperTokens.attemptRefreshingSession(context));
        SessionSnapshot refreshed = SuperTokens.getSessionSnapshot(context);
        assertSame(login.getOwner(), refreshed.getOwner());
        assertTrue(refreshed.getRevision() > login.getRevision());
        server.enqueue(new MockResponse().addHeader("front-token", "remove")
                .addHeader("st-access-token", tokens("cookie", "signout-regenerated").getAccessToken())
                .addHeader("Set-Cookie", "sAccessToken=; Max-Age=0; Path=/")
                .addHeader("Set-Cookie", "sRefreshToken=; Max-Age=0; Path=/")
                .setBody("{\"status\":\"OK\"}"));
        SuperTokens.signOut(context);
        assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        assertNull(SuperTokens.getAccessToken(context));
        assertNull(preferenceStore.preferences.getString("st-storage-item-st-access-token", null));
        assertEquals(3, server.getRequestCount());
        for (String path : new String[] {"/login", "/auth/session/refresh", "/auth/signout"}) {
            okhttp3.mockwebserver.RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals(path, request.getPath());
            assertNull(request.getHeader("Authorization"));
        }
    }

    @Test public void ordinaryResponseStillRejectsRemovalWithAccess() throws Exception {
        SessionSnapshot before = install("A", "before");
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("front-token", Collections.singletonList("remove"));
        headers.put("st-access-token", Collections.singletonList(tokens("A", "regenerated").getAccessToken()));
        SessionCommitResult result = SessionState.applyOrdinaryResponse(context, SessionState.capture(),
                new SessionResponse(200, "OK", headers));
        assertEquals(SessionCommitResult.Status.INVALID_SESSION, result.getStatus());
        assertSame(before, SuperTokens.getSessionSnapshot(context));
    }

    @Test public void removalCommitsBeforeMalformedBodyAndPreservesListenerReplacement() throws Exception {
        java.util.concurrent.atomic.AtomicReference<SessionSnapshot> replacement = new java.util.concurrent.atomic.AtomicReference<>();
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString()).eventHandler(event -> {
            if (event != EventHandler.EventType.SIGN_OUT) return;
            assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
            try { replacement.set(install("B", "listener")); }
            catch (Exception error) { throw new AssertionError(error); }
        }).reconfigure().isSuccess());
        install("A", "before");
        server.enqueue(removal().setBody("invalid-json"));
        try { SuperTokens.signOut(context); fail("Malformed body accepted"); }
        catch (IllegalStateException expected) { }
        assertNotNull(replacement.get());
        assertSame(replacement.get(), SuperTokens.getSessionSnapshot(context));
        assertEquals(1, server.getRequestCount());
    }

    @Test public void throwingSignOutListenerKeepsCommittedRemoval() throws Exception {
        RuntimeException marker = new RuntimeException("listener marker");
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString()).eventHandler(event -> {
            if (event == EventHandler.EventType.SIGN_OUT) throw marker;
        }).reconfigure().isSuccess());
        install("A", "before");
        server.enqueue(removal());
        try { SuperTokens.signOut(context); fail("Listener exception swallowed"); }
        catch (RuntimeException expected) { assertSame(marker, expected); }
        assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        assertNull(SuperTokens.getAccessToken(context));
    }

    @Test public void delayedRemovalCannotClearReplacement() throws Exception {
        install("A", "before");
        java.util.concurrent.CountDownLatch arrived = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        MockResponse response = removal();
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) throws InterruptedException {
                arrived.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return response;
            }
        });
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> logout = worker.submit(() -> {
                try { SuperTokens.signOut(context); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            assertTrue(arrived.await(3, TimeUnit.SECONDS));
            SessionSnapshot replacement = install("B", "replacement");
            release.countDown();
            logout.get(3, TimeUnit.SECONDS);
            assertSame(replacement, SuperTokens.getSessionSnapshot(context));
            assertEquals(1, server.getRequestCount());
        } finally {
            release.countDown(); worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void signOutStillRejectsDuplicateAccessAndOtherCredentialConflicts() throws Exception {
        for (String conflict : new String[] {"st-access-token", "st-refresh-token", "anti-csrf", "front-token"}) {
            SessionSnapshot before = install("A", conflict);
            server.enqueue(removal().addHeader(conflict, "conflict"));
            try { SuperTokens.signOut(context); fail("Conflicting " + conflict + " accepted"); }
            catch (java.io.IOException expected) { }
            assertSame(before, SuperTokens.getSessionSnapshot(context));
        }
    }

    private MockResponse removal() throws Exception {
        return new MockResponse().addHeader("front-token", "remove")
                .addHeader("st-access-token", tokens("A", "regenerated").getAccessToken())
                .setBody("{\"status\":\"OK\"}");
    }

    private MockResponse exposed(String phase) throws Exception {
        return promotion(tokens("cookie", phase))
                .addHeader("Set-Cookie", "sAccessToken=" + phase + "; Path=/")
                .addHeader("Set-Cookie", "sRefreshToken=" + phase + "; Path=/");
    }
}
