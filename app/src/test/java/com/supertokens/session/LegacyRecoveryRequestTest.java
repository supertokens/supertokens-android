package com.supertokens.session;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyRecoveryRequestTest extends SessionTestFixture {
    private boolean cookieMode;
    private EventHandler handler = new EventHandler.DefaultEventHandler();

    @Override protected void initializeSession() throws Exception {
        new SuperTokens.Builder(context, server.url("/").toString())
                .tokenTransferMethod(cookieMode ? "cookie" : "header").eventHandler(handler).build();
    }

    private void recover(boolean unavailable) throws Exception {
        failCommits(TestPreferences.CommitMode.ACKNOWLEDGED);
        assertTrue(preferenceStore.preferences.edit().clear().commit());
        restart(false);
        if (unavailable) {
            Utils.setToken(Utils.TokenType.ACCESS, "partial", context);
        } else {
            assertTrue(preferenceStore.preferences.edit()
                    .putString(SharedPreferencesSessionStorage.STATUS, "recovery").commit());
            restart(true);
        }
        assertEquals(unavailable ? SessionSnapshot.Status.UNAVAILABLE : SessionSnapshot.Status.RECOVERY_REQUIRED,
                SessionState.snapshot().getStatus());
    }

    private void assertCredentialFree() throws Exception {
        RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(sent);
        assertNull(sent.getHeader("Authorization"));
        assertNull(sent.getHeader("anti-csrf"));
    }

    @Test public void ordinaryResponsesPassThroughWithoutRefreshOrCredentials() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            for (boolean unavailable : new boolean[]{false, true}) {
                recover(unavailable);
                SessionSnapshot before = SessionState.snapshot();
                int requests = server.getRequestCount();
                int commits = preferenceStore.commits;
                for (int code : new int[]{200, 401}) {
                    server.enqueue(new MockResponse().setResponseCode(code));
                    assertEquals(code, request(url, null, "/ordinary"));
                    assertCredentialFree();
                    assertSame(before, SessionState.snapshot());
                }
                assertEquals(requests + 2, server.getRequestCount());
                assertEquals(commits, preferenceStore.commits);
            }
        }
    }

    @Test public void completeLoginReplacesRecoveryAndSurvivesRestart() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            for (boolean unavailable : new boolean[]{false, true}) {
                recover(unavailable);
                SessionSnapshot before = SessionState.snapshot();
                SessionTokens login = tokens("login", "new");
                server.enqueue(rotation(login));
                assertEquals(200, request(url, null, "/login"));
                assertCredentialFree();
                assertFalse(before.isCurrent());
                assertEquals(login.getAccessToken(), SuperTokens.getAccessToken(context));
                restart(true);
                assertEquals(SessionSnapshot.Status.AVAILABLE, SessionState.snapshot().getStatus());
                assertEquals(login.getRefreshToken(), SessionState.snapshot().getTokens().getRefreshToken());
            }
        }
    }

    @Test public void okhttpCookieLoginRecoversRequiredState() throws Exception { cookieLogin(false, false); }
    @Test public void okhttpCookieLoginRecoversUnavailableState() throws Exception { cookieLogin(false, true); }
    @Test public void urlConnectionCookieLoginRecoversRequiredState() throws Exception { cookieLogin(true, false); }
    @Test public void urlConnectionCookieLoginRecoversUnavailableState() throws Exception { cookieLogin(true, true); }

    private void cookieLogin(boolean url, boolean unavailable) throws Exception {
        CookieHandler previousCookies = CookieHandler.getDefault();
        try {
            CookieHandler.setDefault(new CookieManager());
            client = client.newBuilder().cookieJar(new CookieJar() {
                private final List<Cookie> cookies = new ArrayList<>();

                @Override public void saveFromResponse(HttpUrl url, List<Cookie> received) {
                    cookies.clear();
                    cookies.addAll(received);
                }

                @Override public List<Cookie> loadForRequest(HttpUrl url) {
                    return new ArrayList<>(cookies);
                }
            }).build();
            cookieMode = true;
            recover(unavailable);
            SessionSnapshot before = SessionState.snapshot();
            SessionTokens login = tokens("cookie-login", "new");
            server.enqueue(new MockResponse().addHeader("front-token", login.getFrontToken())
                    .addHeader("anti-csrf", login.getAntiCSRF())
                    .addHeader("Set-Cookie", "sAccessToken=cookie-access; Path=/; HttpOnly")
                    .addHeader("Set-Cookie", "sRefreshToken=cookie-refresh; Path=/; HttpOnly"));
            assertEquals(200, request(url, null, "/login"));
            assertCredentialFree();
            assertFalse(before.isCurrent());
            assertEquals(SessionSnapshot.Status.AVAILABLE, SessionState.snapshot().getStatus());
            assertNull(SuperTokens.getAccessToken(context));
            restart(true);
            SessionSnapshot restored = SessionState.snapshot();
            assertEquals(SessionSnapshot.Status.AVAILABLE, restored.getStatus());
            assertEquals("primary-user", restored.getUserId());
            assertEquals(login.getFrontToken(), restored.getInternalTokens().getFrontToken());
            assertEquals(login.getAntiCSRF(), restored.getInternalTokens().getAntiCSRF());
            assertNull(restored.getInternalTokens().getAccessToken());
            assertNull(restored.getInternalTokens().getRefreshToken());
            server.enqueue(new MockResponse());
            assertEquals(200, request(url, null, "/protected"));
            RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(sent);
            assertNull(sent.getHeader("Authorization"));
            assertEquals(login.getAntiCSRF(), sent.getHeader("anti-csrf"));
            assertTrue(sent.getHeader("Cookie").contains("sAccessToken=cookie-access"));
            assertTrue(sent.getHeader("Cookie").contains("sRefreshToken=cookie-refresh"));
        } finally {
            cookieMode = false;
            CookieHandler.setDefault(previousCookies);
        }
    }

    @Test public void explicitAndTaggedRecoveryRemainErrorsAndPartialLoginCanAdopt() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            for (boolean unavailable : new boolean[]{false, true}) {
                recover(unavailable);
                SessionSnapshot before = SessionState.snapshot();
                SessionResolution result = SuperTokens.resolveAccessToken(context, before.getOwner(), false, null);
                assertEquals(SessionResolution.Status.RECOVERY_REQUIRED, result.getStatus());
                assertNotNull(result.getError());
                SessionTokens incomplete = tokens("partial", "new");
                assertEquals(SessionCommitResult.Status.INVALID_SESSION,
                        SuperTokens.installSession(context, before.getOwner(),
                                new SessionTokens(incomplete.getAccessToken(), null,
                                        incomplete.getFrontToken(), null)).getStatus());
                server.enqueue(rotation(tokens("tagged", "new")));
                try {
                    request(url, SessionState.capture(), "/tagged");
                    fail("Tagged recovery must remain an error");
                } catch (IOException expected) { }
                assertCredentialFree();
                assertSame(before, SessionState.snapshot());
                server.enqueue(promotion(tokens("partial", "new")));
                assertEquals(200, request(url, null, "/partial"));
                assertCredentialFree();
                assertEquals(SessionSnapshot.Status.AVAILABLE, SessionState.snapshot().getStatus());
                assertNull(SessionState.snapshot().getTokens());
                assertFalse(before.isCurrent());
            }
        }
    }

    @Test public void failedLoginCommitCannotExportAndNextLoginRecovers() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            for (TestPreferences.CommitMode mode : TestPreferences.CommitMode.values()) {
                if (mode == TestPreferences.CommitMode.ACKNOWLEDGED) continue;
                recover(false);
                failCommits(mode);
                server.enqueue(rotation(tokens("failed", "new")));
                try {
                    request(url, null, "/login");
                    fail("New persistence failure must propagate");
                } catch (IOException expected) { }
                assertCredentialFree();
                assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SessionState.snapshot().getStatus());
                assertNull(SessionState.snapshot().getInternalTokens());
                assertNull(SuperTokens.getAccessToken(context));
                failCommits(TestPreferences.CommitMode.ACKNOWLEDGED);
                SessionTokens login = tokens("recovered", "new");
                server.enqueue(rotation(login));
                assertEquals(200, request(url, null, "/login"));
                assertCredentialFree();
                restart(true);
                assertEquals(login.getAccessToken(), SuperTokens.getAccessToken(context));
            }
        }
    }

    @Test public void recoveryLegacyEventsAndSignOutClear() throws Exception {
        for (boolean unavailable : new boolean[]{false, true}) {
            List<EventHandler.EventType> events = new ArrayList<>();
            handler = events::add;
            recover(unavailable);
            events.clear();
            assertFalse(SuperTokens.doesSessionExist(context));
            assertNull(SuperTokens.getAccessToken(context));
            try { SuperTokens.getUserId(context); fail("No user expected"); }
            catch (IllegalStateException expected) { }
            Utils.LocalSessionState projection = Utils.getLocalSessionState(context);
            assertEquals(Utils.LocalSessionStateStatus.NOT_EXISTS, projection.status);
            assertNull(projection.lastAccessTokenUpdate);
            int requests = server.getRequestCount();
            assertFalse(SuperTokens.attemptRefreshingSession(context));
            assertEquals(Arrays.asList(EventHandler.EventType.UNAUTHORISED), events);
            events.clear();
            for (boolean url : new boolean[]{false, true}) {
                for (boolean removal : new boolean[]{false, true}) {
                    MockResponse response = new MockResponse().setResponseCode(401);
                    if (removal) response.addHeader("front-token", "remove");
                    server.enqueue(response);
                    assertEquals(401, request(url, null, "/recovery"));
                    assertCredentialFree();
                    assertEquals(Arrays.asList(EventHandler.EventType.UNAUTHORISED), events);
                    events.clear();
                }
            }
            SuperTokens.signOut(context);
            assertEquals(Arrays.asList(EventHandler.EventType.SIGN_OUT), events);
            assertEquals(requests + 4, server.getRequestCount());
            assertEquals(SessionSnapshot.Status.ABSENT, SessionState.snapshot().getStatus());
            restart(true);
            assertEquals(SessionSnapshot.Status.ABSENT, SessionState.snapshot().getStatus());
        }
    }

    @Test public void recoverySignOutStillNotifiesWhenClearFails() throws Exception {
        List<EventHandler.EventType> events = new ArrayList<>();
        handler = events::add;
        recover(false);
        events.clear();
        failCommits(TestPreferences.CommitMode.RETURN_FALSE);
        SuperTokens.signOut(context);
        assertEquals(Arrays.asList(EventHandler.EventType.SIGN_OUT), events);
        assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SessionState.snapshot().getStatus());
    }

    @Test public void failedRefreshCommitPropagatesWithoutExportingCredentials() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            failCommits(TestPreferences.CommitMode.ACKNOWLEDGED);
            recover(false);
            install("refresh", "before");
            failCommits(TestPreferences.CommitMode.RETURN_FALSE);
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(rotation(tokens("refresh", "after")));
            try {
                request(url, null, "/protected");
                fail("Refresh persistence failure must propagate");
            } catch (IOException expected) { }
            assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SessionState.snapshot().getStatus());
            assertNull(SessionState.snapshot().getInternalTokens());
            assertNull(SuperTokens.getAccessToken(context));
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS));
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS));
        }
    }

    @Test public void delayedRecoveryLoginCannotOverwriteReplacement() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            recover(false);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            MockResponse login = rotation(tokens("late", "new"));
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(500);
                    return login;
                }
            });
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> response = worker.submit(() -> request(url, null, "/login"));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                SessionSnapshot replacement = install("B", "current");
                release.countDown();
                assertEquals(Integer.valueOf(200), response.get(5, TimeUnit.SECONDS));
                assertCredentialFree();
                assertSame(replacement, SessionState.snapshot());
            } finally {
                release.countDown();
                worker.shutdownNow();
            }
        }
    }
}
