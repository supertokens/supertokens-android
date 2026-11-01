package com.supertokens.session;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionResponseCompatibilityTest extends SessionTestFixture {
    private void configure(String mode, int retries) throws Exception {
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod(mode)
                .maxRetryAttemptsForSessionRefresh(retries).reconfigure().isSuccess());
    }

    private static MockResponse session(SessionTokens tokens, boolean cookie) {
        return new MockResponse().setBody("ok").addHeader("front-token", tokens.getFrontToken())
                .addHeader("st-access-token", tokens.getAccessToken())
                .addHeader(cookie ? "Set-Cookie" : "st-refresh-token", cookie ? "sRefreshToken=cookie-refresh; Path=/" : tokens.getRefreshToken())
                .addHeader("anti-csrf", tokens.getAntiCSRF());
    }

    private RecordedRequest wire() throws Exception {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        return request;
    }

    private void cookieJar(CookieManager manager) {
        CookieHandler.setDefault(manager);
        client = client.newBuilder().cookieJar(new CookieJar() {
            @Override public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                List<String> headers = new ArrayList<>();
                for (Cookie cookie : cookies) headers.add(cookie.toString());
                try { manager.put(url.uri(), Collections.singletonMap("Set-Cookie", headers)); }
                catch (IOException error) { throw new AssertionError(error); }
            }
            @Override public List<Cookie> loadForRequest(HttpUrl url) {
                List<Cookie> cookies = new ArrayList<>();
                for (HttpCookie cookie : manager.getCookieStore().get(url.uri())) {
                    Cookie parsed = Cookie.parse(url, cookie.toString());
                    if (parsed != null) cookies.add(parsed);
                }
                return cookies;
            }
        }).build();
    }

    @Test public void cookieExposedLoginRefreshRetryAndSignoutNeverAttachBearerBothTransports() throws Exception {
        for (boolean url : new boolean[]{false, true}) for (boolean recovering : new boolean[]{false, true}) {
            configure("cookie", 2);
            cookieJar(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
            SessionTokens login = tokens("cookie", "login"), refreshed = tokens("cookie", "refreshed");
            if (recovering) {
                failCommits(TestPreferences.CommitMode.RETURN_FALSE);
                assertEquals(SessionCommitResult.Status.PERSISTENCE_FAILED, SuperTokens.installSession(context,
                        SuperTokens.getSessionSnapshot(context).getOwner(), login).getStatus());
                failCommits(TestPreferences.CommitMode.ACKNOWLEDGED);
            }
            server.enqueue(session(login, true).addHeader("Set-Cookie", "sAccessToken=cookie-access; Path=/"));
            assertEquals(200, request(url, null, "/login"));
            assertNull(wire().getHeader("Authorization"));
            SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
            assertEquals(SessionSnapshot.Status.AVAILABLE, before.getStatus());
            assertNull(before.getTokens());
            assertEquals(login.getAccessToken(), SuperTokens.getAccessToken(context));
            assertNull(SuperTokens.resolveAccessToken(context, before.getOwner(), false, null).getAccessToken());
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(session(refreshed, true));
            server.enqueue(new MockResponse().setBody("protected"));
            assertEquals(200, request(url, null, "/protected"));
            for (String path : new String[]{"/protected", "/auth/session/refresh", "/protected"}) {
                RecordedRequest request = wire();
                assertEquals(path, request.getPath());
                assertNull(request.getHeader("Authorization"));
                assertNotNull(request.getHeader("Cookie"));
                assertTrue(request.getHeader("Cookie").contains("sRefreshToken=cookie-refresh"));
            }
            assertSame(before.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
            assertEquals(refreshed.getAccessToken(), SuperTokens.getAccessToken(context));
            server.enqueue(new MockResponse().setBody("{\"status\":\"OK\"}").addHeader("front-token", "remove"));
            SuperTokens.signOut(context);
            RecordedRequest signout = wire();
            assertEquals("/auth/signout", signout.getPath());
            assertNull(signout.getHeader("Authorization"));
            assertTrue(signout.getHeader("Cookie").contains("sRefreshToken=cookie-refresh"));
            assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        }
    }

    @Test public void expiredLegacyResponsesIgnoreDateAndRetriesStayBoundedBothTransports() throws Exception {
        for (boolean url : new boolean[]{false, true}) for (boolean cookie : new boolean[]{false, true}) {
            for (int date = 0; date < 4; date++) {
                configure(cookie ? "cookie" : "header", 1);
                if (cookie) cookieJar(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
                SessionTokens expired = tokens("clock", "user", 1000, 1000, 5);
                MockResponse login = session(expired, cookie), refresh = session(expired, cookie);
                for (MockResponse response : new MockResponse[]{login, refresh}) {
                    if (date == 1) response.addHeader("Date", "Thu, 29 Oct 2026 12:00:00 GMT");
                    if (date == 2) response.addHeader("Date", "not-a-date");
                    if (date == 3) response.addHeader("Date", "Thu, 29 Oct 2026 12:00:00 GMT").addHeader("Date", "Fri, 30 Oct 2026 12:00:00 GMT");
                }
                server.enqueue(login);
                assertEquals(200, request(url, null, "/login"));
                SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
                assertEquals(SessionSnapshot.Status.AVAILABLE, before.getStatus());
                assertEquals(1000, before.getAccessTokenExpiry());
                assertEquals(SessionCommitResult.Status.INVALID_SESSION, SuperTokens.installSession(context, before.getOwner(), expired).getStatus());
                int requests = server.getRequestCount();
                server.enqueue(new MockResponse().setResponseCode(401));
                server.enqueue(refresh);
                server.enqueue(new MockResponse().setResponseCode(401));
                try { request(url, null, "/protected"); fail("retry limit"); }
                catch (IOException | IllegalAccessException expected) { assertTrue(expected.getMessage().contains("maximum session refresh limit")); }
                assertEquals(requests + 3, server.getRequestCount());
                assertSame(before.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
                assertEquals(SessionSnapshot.Status.AVAILABLE, SuperTokens.getSessionSnapshot(context).getStatus());
                assertEquals(1000, SuperTokens.getSessionSnapshot(context).getAccessTokenExpiry());
            }
        }
    }

    @Test public void taggedAdmissionAndExplicitRefreshKeepStrictExpiryValidation() throws Exception {
        SessionTokens expired = tokens("strict", "user", 1000, 1000, 5);
        for (boolean url : new boolean[]{false, true}) {
            configure("header", 1);
            SessionSnapshot absent = SuperTokens.getSessionSnapshot(context);
            server.enqueue(session(expired, false));
            assertEquals(200, request(url, SuperTokens.captureRequestTag(context), "/login"));
            assertSame(absent, SuperTokens.getSessionSnapshot(context));
        }
        SessionSnapshot before = install("strict", "before");
        server.enqueue(session(expired, false));
        assertEquals(SessionResolution.Status.REFRESH_FAILED, SuperTokens.resolveAccessToken(context, before.getOwner(), true, null).getStatus());
        assertSame(before, SuperTokens.getSessionSnapshot(context));
    }

    @Test public void refreshUserChangeWithKnownLineageKeepsOwnerAndEventOrderBothTransports() throws Exception {
        for (boolean url : new boolean[]{false, true}) for (boolean cookie : new boolean[]{false, true}) {
            List<EventHandler.EventType> events = new ArrayList<>();
            assertTrue(new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod(cookie ? "cookie" : "header")
                    .eventHandler(events::add).reconfigure().isSuccess());
            if (cookie) {
                cookieJar(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
                server.enqueue(session(tokens("lineage", "before"), true));
                assertEquals(200, request(url, null, "/login"));
            } else install("lineage", "before");
            SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
            SessionTokens changed = tokens("lineage", "linked-user", 4102444800000L, 4102444800000L, 5);
            events.clear();
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(session(changed, cookie));
            server.enqueue(new MockResponse().setBody("ok"));
            assertEquals(200, request(url, null, "/protected"));
            assertSame(before.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            assertEquals(before.getAdoptionId(), SuperTokens.getSessionSnapshot(context).getAdoptionId());
            assertEquals("linked-user", SuperTokens.getUserId(context));
            assertEquals(Arrays.asList(EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED, EventHandler.EventType.REFRESH_SESSION), events);
        }
    }

    @Test public void malformedAndDifferentLineageRefreshResponsesRetainSessionBothTransports() throws Exception {
        for (boolean url : new boolean[]{false, true}) for (int bad = 0; bad < 6; bad++) {
            SessionSnapshot before = install("lineage", "before");
            SessionTokens other = bad == 2 ? tenant(tokens("lineage", "next"), "other") : tokens("other-handle", "next");
            MockResponse response = bad == 0 ? new MockResponse().setBody("<html>portal</html>")
                    : bad == 1 ? new MockResponse().addHeader("front-token", other.getFrontToken()) : session(other, false);
            if (bad == 4) response = session(tokens("lineage", "after"), false).setHeader("front-token", "A");
            if (bad == 5) response = session(tokens("lineage", "after"), false).addHeader("front-token", other.getFrontToken());
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(response);
            int requests = server.getRequestCount();
            try { request(url, null, "/protected"); fail("malformed refresh must fail"); }
            catch (IOException expected) { }
            assertSame(before, SuperTokens.getSessionSnapshot(context));
            assertEquals(requests + 2, server.getRequestCount());
        }
    }

    @Test public void legacyGetterProactivelyRefreshesExpiredAcceptedSession() throws Exception {
        for (boolean url : new boolean[]{false, true}) for (boolean cookie : new boolean[]{false, true}) {
            configure(cookie ? "cookie" : "header", 1);
            if (cookie) cookieJar(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
            SessionTokens expired = tokens("clock", "user", 1000, 1000, 5);
            SessionTokens fresh = tokens("clock", "user", 4102444800000L, 4102444800000L, 5);
            server.enqueue(session(expired, cookie));
            assertEquals(200, request(url, null, "/login"));
            wire();
            SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
            int requests = server.getRequestCount();
            server.enqueue(session(fresh, cookie));
            assertEquals(fresh.getAccessToken(), SuperTokens.getAccessToken(context));
            assertEquals(requests + 1, server.getRequestCount());
            RecordedRequest refresh = wire();
            assertEquals("/auth/session/refresh", refresh.getPath());
            assertEquals(cookie ? null : "Bearer " + expired.getRefreshToken(), refresh.getHeader("Authorization"));
            assertSame(before.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
            if (cookie) assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
        }
    }

    @Test public void sharedLegacyAndExplicitRefreshNeverExportsExpiredResultToExplicitCaller() throws Exception {
        for (boolean legacyLeads : new boolean[]{false, true}) {
            SessionSnapshot before = install("shared", "before");
            SessionTokens expired = tokens("shared", "primary-user", 1000, 1000, 5);
            CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    arrived.countDown();
                    return release.await(8, TimeUnit.SECONDS) ? session(expired, false) : new MockResponse().setResponseCode(504);
                }
            });
            AtomicReference<SessionResolution> strict = new AtomicReference<>();
            AtomicReference<Boolean> legacy = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            Runnable legacyCall = () -> {
                try { legacy.set(SuperTokens.attemptRefreshingSession(context)); }
                catch (Throwable failure) { error.set(failure); }
            };
            Runnable strictCall = () -> strict.set(SuperTokens.resolveAccessToken(context, before.getOwner(), true, null));
            ExecutorService worker = Executors.newSingleThreadExecutor();
            Thread follower = new Thread(legacyLeads ? strictCall : legacyCall, "mixed-refresh-follower");
            int requests = server.getRequestCount();
            try {
                Future<?> leader = worker.submit(legacyLeads ? legacyCall : strictCall);
                assertTrue(arrived.await(5, TimeUnit.SECONDS));
                follower.start();
                awaitFlight(follower);
                release.countDown();
                leader.get(5, TimeUnit.SECONDS);
                follower.join(5000);
                assertFalse(follower.isAlive());
                assertNull(error.get());
                assertEquals(Boolean.TRUE, legacy.get());
                assertEquals(SessionResolution.Status.REFRESH_FAILED, strict.get().getStatus());
                assertNull(strict.get().getAccessToken());
                assertSame(before.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
                assertEquals(SessionSnapshot.Status.AVAILABLE, SuperTokens.getSessionSnapshot(context).getStatus());
                assertEquals(1000, SuperTokens.getSessionSnapshot(context).getAccessTokenExpiry());
                assertEquals(requests + 1, server.getRequestCount());
            } finally {
                release.countDown();
                follower.interrupt();
                follower.join(5000);
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private static void awaitFlight(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            for (StackTraceElement frame : thread.getStackTrace()) {
                if (frame.getClassName().equals(RefreshCoordinator.Flight.class.getName()) && frame.getMethodName().equals("await")) return;
            }
            if (!thread.isAlive()) fail("follower exited before joining refresh");
            Thread.yield();
        }
        fail("follower never joined refresh");
    }

    @Test public void unknownImportedHandleOnlyEnrichesWithMatchingUserAndTenant() throws Exception {
        SessionTokens original = tokens("unknown", "before");
        SuperTokens.resetForTests();
        seedLegacy(new SessionTokens("opaque", "refresh", original.getFrontToken(), original.getAntiCSRF()));
        initializeSession();
        SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
        assertNull(before.getSessionHandle());
        server.enqueue(session(tokens("known", "different-user", 4102444800000L, 4102444800000L, 5), false));
        try { SuperTokens.attemptRefreshingSession(context); fail("unknown lineage cannot authorize a user change"); }
        catch (IOException expected) { }
        assertSame(before, SuperTokens.getSessionSnapshot(context));
        server.enqueue(session(tokens("known", "after"), false));
        assertTrue(SuperTokens.attemptRefreshingSession(context));
        assertSame(before.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
        assertEquals("known", SuperTokens.getSessionSnapshot(context).getSessionHandle());
    }

    private static SessionTokens tenant(SessionTokens source, String tenant) throws Exception {
        String[] access = source.getAccessToken().split("\\.");
        JSONObject payload = new JSONObject(new String(Base64.getUrlDecoder().decode(access[1]), StandardCharsets.UTF_8)).put("tId", tenant);
        JSONObject front = SessionTokens.decodeFrontToken(source.getFrontToken());
        front.getJSONObject("up").put("tId", tenant);
        return new SessionTokens(access[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8)) + "." + access[2],
                source.getRefreshToken(), Base64.getEncoder().encodeToString(front.toString().getBytes(StandardCharsets.UTF_8)), source.getAntiCSRF());
    }
}
