package com.supertokens.session;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConfigurationUpgradeTest extends SessionTestFixture {
    @Test public void transferHookRetiresBlockedRefreshWithoutClearingSession() throws Exception {
        SessionSnapshot before = install("hook", "before");
        SessionRequestTag oldTag = SuperTokens.captureRequestTag(context);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        SessionTokens obsolete = tokens("hook", "obsolete");
        RefreshCoordinator.Transport blocked = (ctx, config, snapshot) -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Refresh not released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(e);
            }
            return new SessionResponse(200, "OK", rotation(obsolete).getHeaders().toMultimap());
        };
        try {
            Future<SessionResolution> leader = executor.submit(() -> RefreshCoordinator.resolve(
                    context, before.getOwner(), true, null, blocked));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            RefreshCoordinator.Acquisition follower = SessionState.acquireRefresh(before.getOwner(), true,
                    null, blocked, before.getRefreshTokenRevision(), false);
            assertFalse(follower.leader);
            assertNull(follower.immediate);
            Future<SessionResolution> waiter = executor.submit(() -> follower.flight.await());
            int commits = preferenceStore.commits;
            for (String method : new String[]{"cookie", "header", "cookie"}) {
                SuperTokens.setTokenTransferMethod(method);
                assertPreserved(before, SuperTokens.getSessionSnapshot(context));
            }
            assertEquals(SessionResolution.Status.STALE_OWNER, waiter.get(2, TimeUnit.SECONDS).getStatus());
            release.countDown();
            assertEquals(SessionResolution.Status.STALE_OWNER, leader.get(2, TimeUnit.SECONDS).getStatus());
            assertPreserved(before, SuperTokens.getSessionSnapshot(context));
            assertEquals(commits, preferenceStore.commits);
            for (boolean url : new boolean[]{false, true}) {
                try { request(url, oldTag, "/stale"); fail("Old configuration dispatched"); }
                catch (StaleSessionException expected) { }
            }
            assertEquals(0, server.getRequestCount());
            CookieHandler.setDefault(new CookieManager());
            server.enqueue(rotation(tokens("hook", "fresh")));
            assertTrue(SuperTokens.resolveAccessToken(context, before.getOwner(), true, null).isSuccess());
            SessionSnapshot after = SuperTokens.getSessionSnapshot(context);
            assertSame(before.getOwner(), after.getOwner());
            assertEquals(before.getAdoptionId(), after.getAdoptionId());
            assertTrue(after.getRevision() > before.getRevision());
            assertEquals(tokens("hook", "fresh").getAccessToken(), SuperTokens.getAccessToken(context));
            assertEquals("cookie", server.takeRequest(2, TimeUnit.SECONDS).getHeader("st-auth-mode"));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void assertPreserved(SessionSnapshot before, SessionSnapshot after) {
        assertSame(before.getOwner(), after.getOwner());
        assertEquals(before.getStatus(), after.getStatus());
        assertEquals(before.getAdoptionId(), after.getAdoptionId());
        assertEquals(before.getAdoptionCorrelation(), after.getAdoptionCorrelation());
        assertEquals(before.getRevision(), after.getRevision());
        assertEquals(before.getAccessTokenRevision(), after.getAccessTokenRevision());
        assertEquals(before.getRefreshTokenRevision(), after.getRefreshTokenRevision());
        assertSame(before.getInternalTokens(), after.getInternalTokens());
    }

    @Test public void eachStartupSettingPreservesDurableSessionWithoutMetadataOnlyCommit() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            for (String field : new String[]{"retries", "status", "basePath", "eventHandler", "headers",
                    "transfer", "apiHost", "backendHost"}) {
                SuperTokens.resetForTests();
                new SuperTokens.Builder(context, server.url("/").toString()).build();
                SessionSnapshot before = install("migration", field);
                int commits = preferenceStore.commits;
                SuperTokens.resetForTests();
                String host = field.equals("apiHost")
                        ? server.url("/").newBuilder().host("127.0.0.1").build().toString()
                        : server.url("/").toString();
                SuperTokens.Builder builder = new SuperTokens.Builder(context, host);
                if (field.equals("retries")) builder.maxRetryAttemptsForSessionRefresh(3);
                if (field.equals("status")) builder.sessionExpiredStatusCode(403);
                if (field.equals("basePath")) builder.apiBasePath("/changed-auth");
                if (field.equals("eventHandler")) builder.eventHandler(event -> {});
                if (field.equals("headers")) builder.customHeaderProvider(type -> null);
                if (field.equals("transfer")) builder.tokenTransferMethod("cookie");
                if (field.equals("backendHost")) builder.sessionTokenBackendDomain("127.0.0.1");
                builder.build();
                SessionSnapshot after = SuperTokens.getSessionSnapshot(context);
                assertEquals(field, before.getAdoptionId(), after.getAdoptionId());
                assertEquals(field, before.getRevision(), after.getRevision());
                assertEquals(field, before.getRefreshTokenRevision(), after.getRefreshTokenRevision());
                assertEquals(field, before.getTokens().getAccessToken(), after.getTokens().getAccessToken());
                assertEquals(field, commits, preferenceStore.commits);
                CookieHandler.setDefault(new CookieManager());
                server.enqueue(new MockResponse());
                String destination = field.equals("backendHost")
                        ? server.url("/profile").newBuilder().host("127.0.0.1").build().toString()
                        : host + "profile";
                if (url) {
                    java.net.HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(
                            new java.net.URL(destination), c -> {});
                    assertEquals(200, connection.getResponseCode());
                    connection.disconnect();
                } else {
                    try (okhttp3.Response response = client.newCall(new okhttp3.Request.Builder()
                            .url(destination).build()).execute()) { assertEquals(200, response.code()); }
                }
                assertEquals(field, "Bearer " + before.getTokens().getAccessToken(),
                        server.takeRequest(2, TimeUnit.SECONDS).getHeader("Authorization"));
                SuperTokens.resetForTests();
                builder.build();
                assertEquals(field, before.getAdoptionId(), SuperTokens.getSessionSnapshot(context).getAdoptionId());
                server.enqueue(rotation(tokens("migration", field + "-refreshed")));
                SessionSnapshot current = SuperTokens.getSessionSnapshot(context);
                assertTrue(field, SuperTokens.resolveAccessToken(context, current.getOwner(), true, null).isSuccess());
                assertEquals(field, current.getConfigurationId(),
                        new SharedPreferencesSessionStorage().read(context).configurationId);
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test public void cookieToHeaderStartupAndHistoricalTestHookKeepTheSession() throws Exception {
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .tokenTransferMethod("cookie").reconfigure().isSuccess());
        SessionSnapshot before = install("cookie", "migration");
        SuperTokens.resetForTests();
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header").build();
        SessionSnapshot restored = SuperTokens.getSessionSnapshot(context);
        assertEquals(before.getAdoptionId(), restored.getAdoptionId());
        SuperTokens.setTokenTransferMethod("cookie");
        assertSame(restored.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
        assertEquals(restored.getAdoptionId(), SuperTokens.getSessionSnapshot(context).getAdoptionId());
        SuperTokens.setTokenTransferMethod("header");
        assertSame(restored.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
        assertEquals(restored.getConfigurationId(), SuperTokens.getSessionSnapshot(context).getConfigurationId());
        // The hook must not leave a stale ID that makes real reconfigure a no-op.
        SuperTokens.setTokenTransferMethod("cookie");
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                .reconfigure().isSuccess());
        assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
    }

    @Test public void cookieOnlySessionSurvivesHeaderPreferenceOnRestart() throws Exception {
        CookieHandler.setDefault(new CookieManager());
        for (boolean url : new boolean[]{false, true}) {
            assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                    .tokenTransferMethod("cookie").reconfigure().isSuccess());
            SessionTokens cookie = tokens("cookie-only", "before");
            server.enqueue(new MockResponse().addHeader("front-token", cookie.getFrontToken())
                    .addHeader("anti-csrf", cookie.getAntiCSRF()));
            assertEquals(200, request(url, null, "/login"));
            server.takeRequest(2, TimeUnit.SECONDS);
            SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
            assertEquals(SessionSnapshot.Status.AVAILABLE, before.getStatus());
            SuperTokens.resetForTests();
            new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header").build();
            assertEquals(before.getAdoptionId(), SuperTokens.getSessionSnapshot(context).getAdoptionId());
            assertTrue(SuperTokens.doesSessionExist(context));
            server.enqueue(new MockResponse());
            assertEquals(200, request(url, null, "/profile"));
            okhttp3.mockwebserver.RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertNull(sent.getHeader("Authorization"));
            assertEquals(cookie.getAntiCSRF(), sent.getHeader("anti-csrf"));
        }
    }
}
