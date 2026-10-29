package com.supertokens.session;

import java.io.IOException;
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

public class SessionSignOutRefreshTest extends SessionTestFixture {
    @Test public void serverRejectedLocallyUsableAccessRefreshesBeforeSignOut() throws Exception {
        refreshAndSignOut(401);
    }

    @Test public void configuredExpiryStatusRefreshesBeforeSignOut() throws Exception {
        refreshAndSignOut(419);
    }

    private void refreshAndSignOut(int expiryStatus) throws Exception {
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .sessionExpiredStatusCode(expiryStatus).reconfigure().isSuccess());
        SessionSnapshot initial = install("A", "initial");
        SessionTokens refreshed = tokens("A", "refreshed");
        server.enqueue(new MockResponse().setResponseCode(expiryStatus));
        server.enqueue(rotation(refreshed));
        server.enqueue(signedOut());
        SuperTokens.signOut(context);
        assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        assertEquals(3, server.getRequestCount());
        assertRequest("/auth/signout", initial.getTokens().getAccessToken());
        assertRequest("/auth/session/refresh", initial.getTokens().getRefreshToken());
        assertRequest("/auth/signout", refreshed.getAccessToken());
    }

    @Test public void replacementDuringExpiryResponsePreventsRefresh() throws Exception { replacementAt(1); }
    @Test public void replacementDuringRefreshPreventsSignOutRetry() throws Exception { replacementAt(2); }
    @Test public void replacementDuringRetriedSignOutCannotClearNewOwner() throws Exception { replacementAt(3); }

    private void replacementAt(int blockedRequest) throws Exception {
        SessionSnapshot initial = install("A", "initial");
        SessionTokens refreshed = tokens("A", "refreshed");
        CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                int call = calls.incrementAndGet();
                if (call == blockedRequest) {
                    arrived.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(504);
                }
                return call == 1 ? new MockResponse().setResponseCode(401)
                        : call == 2 ? rotation(refreshed) : signedOut();
            }
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> pending = worker.submit(() -> {
                try { SuperTokens.signOut(context); fail("replacement owner accepted by signout"); }
                catch (StaleSessionException expected) { }
                catch (Exception error) { throw new AssertionError(error); }
            });
            assertTrue("signout did not reach expected wire", arrived.await(3, TimeUnit.SECONDS));
            SessionSnapshot replacement = install("B", "replacement");
            release.countDown();
            pending.get(5, TimeUnit.SECONDS);
            SessionSnapshot after = SuperTokens.getSessionSnapshot(context);
            assertSame(replacement.getOwner(), after.getOwner());
            assertEquals(replacement.getRevision(), after.getRevision());
            assertEquals(replacement.getTokens().getRefreshToken(), after.getTokens().getRefreshToken());
            assertEquals(blockedRequest, server.getRequestCount());
            assertRequest("/auth/signout", initial.getTokens().getAccessToken());
            if (blockedRequest >= 2) assertRequest("/auth/session/refresh", initial.getTokens().getRefreshToken());
            if (blockedRequest == 3) assertRequest("/auth/signout", refreshed.getAccessToken());
        } finally {
            release.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void repeatedExpiryHonorsConfiguredRetryBound() throws Exception {
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .maxRetryAttemptsForSessionRefresh(1).reconfigure().isSuccess());
        SessionSnapshot initial = install("A", "initial");
        SessionTokens refreshed = tokens("A", "refreshed");
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(rotation(refreshed));
        server.enqueue(new MockResponse().setResponseCode(401));
        try { SuperTokens.signOut(context); fail("unbounded expiry accepted"); }
        catch (IllegalAccessException expected) { assertTrue(expected.getMessage().contains("maximum session refresh limit")); }
        assertEquals(3, server.getRequestCount());
        assertSame(initial.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
        assertEquals(refreshed.getRefreshToken(), SuperTokens.getSessionSnapshot(context).getTokens().getRefreshToken());
    }

    @Test public void failedRefreshPreservesOwnerAndDoesNotRetrySignOut() throws Exception {
        SessionSnapshot initial = install("A", "initial");
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(500));
        try { SuperTokens.signOut(context); fail("refresh failure accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("500")); }
        assertEquals(2, server.getRequestCount());
        assertSame(initial.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
        assertEquals(initial.getRevision(), SuperTokens.getSessionSnapshot(context).getRevision());
    }

    @Test public void retryStillValidatesBodyBeforeAcceptingRemoval() throws Exception {
        SessionSnapshot initial = install("A", "initial");
        SessionTokens refreshed = tokens("A", "refreshed");
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(rotation(refreshed));
        server.enqueue(new MockResponse().addHeader("front-token", "remove")
                .setBody("{\"status\":\"GENERAL_ERROR\",\"message\":\"cannot sign out\"}"));
        try { SuperTokens.signOut(context); fail("invalid signout accepted"); }
        catch (SuperTokensGeneralError expected) { assertEquals("cannot sign out", expected.getMessage()); }
        assertEquals(3, server.getRequestCount());
        assertSame(initial.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
        assertEquals(refreshed.getRefreshToken(), SuperTokens.getSessionSnapshot(context).getTokens().getRefreshToken());
    }

    @Test public void expiryWithExplicitRemovalDoesNotRefresh() throws Exception {
        install("A", "initial");
        server.enqueue(new MockResponse().setResponseCode(401).addHeader("front-token", "remove"));
        SuperTokens.signOut(context);
        assertEquals(1, server.getRequestCount());
        assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
    }

    @Test public void confirmedDelayedSignOutClearsSameOwnerAfterRefresh() throws Exception {
        delayedSignOutAfterRefresh("{\"status\":\"OK\"}", 200, true);
    }

    @Test public void failedDelayedSignOutDoesNotClearRefreshedOwner() throws Exception {
        delayedSignOutAfterRefresh("{\"status\":\"GENERAL_ERROR\",\"message\":\"failed\"}", 200, false);
    }

    @Test public void delayedExpiryRemovalDoesNotClearRefreshedOwner() throws Exception {
        delayedSignOutAfterRefresh("", 401, false);
    }

    private void delayedSignOutAfterRefresh(String body, int code, boolean clears) throws Exception {
        SessionSnapshot initial = install("A", "initial");
        SessionTokens refreshed = tokens("A", "refreshed");
        CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (request.getPath().equals("/auth/session/refresh")) return rotation(refreshed);
                arrived.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(504);
                return new MockResponse().setResponseCode(code).addHeader("front-token", "remove").setBody(body);
            }
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> signout = worker.submit(() -> {
                try {
                    SuperTokens.signOut(context);
                    if (!clears) fail("failed or superseded signout accepted");
                } catch (SuperTokensGeneralError expected) {
                    if (clears || code != 200) throw new AssertionError(expected);
                } catch (IOException expected) {
                    if (code != 401 || !expected.getMessage().contains("superseded")) throw new AssertionError(expected);
                } catch (Exception error) { throw new AssertionError(error); }
            });
            assertTrue(arrived.await(3, TimeUnit.SECONDS));
            SessionResolution resolution = SuperTokens.resolveAccessToken(context, initial.getOwner(), true, null);
            assertTrue(resolution.isSuccess());
            SessionSnapshot afterRefresh = resolution.getSnapshot();
            assertSame(initial.getOwner(), afterRefresh.getOwner());
            assertTrue(afterRefresh.getRefreshTokenRevision() > initial.getRefreshTokenRevision());
            assertEquals(refreshed.getRefreshToken(), afterRefresh.getTokens().getRefreshToken());
            release.countDown();
            signout.get(5, TimeUnit.SECONDS);
            SessionSnapshot after = SuperTokens.getSessionSnapshot(context);
            if (clears) assertEquals(SessionSnapshot.Status.ABSENT, after.getStatus());
            else {
                assertSame(afterRefresh.getOwner(), after.getOwner());
                assertEquals(afterRefresh.getRevision(), after.getRevision());
                assertEquals(refreshed.getRefreshToken(), after.getTokens().getRefreshToken());
            }
            assertEquals(2, server.getRequestCount());
            assertRequest("/auth/signout", initial.getTokens().getAccessToken());
            assertRequest("/auth/session/refresh", initial.getTokens().getRefreshToken());
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void assertRequest(String path, String token) throws Exception {
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals(path, request.getPath());
        assertEquals("POST", request.getMethod());
        assertEquals("Bearer " + token, request.getHeader("Authorization"));
    }

    private static MockResponse signedOut() {
        return new MockResponse().addHeader("front-token", "remove").setBody("{\"status\":\"OK\"}");
    }
}
