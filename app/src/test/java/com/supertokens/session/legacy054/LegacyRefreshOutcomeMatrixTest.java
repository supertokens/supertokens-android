package com.supertokens.session.legacy054;

import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.SessionTestFixture;
import com.supertokens.session.SuperTokens;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LegacyRefreshOutcomeMatrixTest extends SessionTestFixture {
    @Override protected boolean initializeOnSetup() { return false; }

    private String front() throws Exception {
        return Base64.getEncoder().encodeToString("{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{}}".getBytes("UTF-8"));
    }

    private MockResponse login(String front) {
        return new MockResponse().addHeader("front-token", front)
                .addHeader("st-access-token", "access").addHeader("st-refresh-token", "refresh");
    }

    private String run(boolean legacy, int failure, boolean interceptor) throws Exception {
        String front = front();
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger protectedRequests = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/login")) return login(front);
                if (request.getPath().contains("/session/refresh")) {
                    return refreshes.incrementAndGet() == 1
                            ? new MockResponse().setResponseCode(failure).addHeader("Retry-After", "3600")
                            : login(front);
                }
                return new MockResponse().setResponseCode(protectedRequests.incrementAndGet() <= 2 ? 401 : 200);
            }
        });
        try (Legacy054Harness old = legacy ? new Legacy054Harness(context) : null) {
            if (legacy) {
                old.build(server.url("/").toString(), "header");
                old.request(server.url("/login").toString());
            } else {
                new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header").build();
                request(false, null, "/login");
            }
            String first = interceptor ? requestOutcome(old) : refreshOutcome(old);
            boolean retained = legacy ? (Boolean) old.invoke("doesSessionExist") : SuperTokens.doesSessionExist(context);
            String second = interceptor ? requestOutcome(old) : refreshOutcome(old);
            return first + ":" + retained + ":" + second + ":" + refreshes.get();
        }
    }

    private String requestOutcome(Legacy054Harness old) {
        try {
            return String.valueOf(old == null ? request(false, null, "/protected")
                    : old.request(server.url("/protected").toString()).get("status"));
        } catch (Exception error) {
            return error.getClass().getName();
        }
    }

    private String refreshOutcome(Legacy054Harness old) {
        try {
            return String.valueOf(old == null ? SuperTokens.attemptRefreshingSession(context) : old.invoke("refresh"));
        } catch (Exception error) {
            return error.getClass().getName();
        }
    }

    private void compare(int status, boolean interceptor) throws Exception {
        String expected = run(true, status, interceptor);
        context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
        SuperTokens.resetForTests();
        assertEquals(expected, run(false, status, interceptor));
    }

    @Test public void r19Bare401ThenRefresh() throws Exception { compare(401, false); }
    @Test public void r21ServerFailureThenRefresh() throws Exception { compare(429, true); }

    @Test public void disconnectAtStartReturnedByDispatcherIsAnEmpty200() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START);
            }
        });
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) server.url("/reset").url().openConnection();
        try {
            connection.setRequestMethod("POST");
            assertEquals(200, connection.getResponseCode());
            assertEquals(null, connection.getHeaderField("front-token"));
        } finally {
            connection.disconnect();
        }
    }

    @Test public void resetReplaysLegacyRefresh() throws Exception {
        String expected = matrixRow(true, Caller.REFRESH, Outcome.RESET);
        context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
        SuperTokens.resetForTests();
        String actual = matrixRow(false, Caller.REFRESH, Outcome.RESET);
        assertTrue(expected, expected.startsWith("true:") && expected.contains(":refreshes=2:"));
        assertEquals(expected, actual);
    }

    @Test public void processedRefreshThenDisconnectReusesToken() throws Exception {
        for (boolean interceptor : new boolean[]{false, true}) {
            String expected = consumedRefresh(true, interceptor);
            context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
            SuperTokens.resetForTests();
            String actual = consumedRefresh(false, interceptor);
            assertEquals(expected, actual);
            assertTrue(actual, actual.contains(":refreshes=2:reuses=1"));
            context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
            SuperTokens.resetForTests();
        }
    }

    private String consumedRefresh(boolean legacy, boolean interceptor) throws Exception {
        String front = front();
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger reuses = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/login")) return login(front);
                if (request.getPath().contains("/session/refresh")) {
                    assertEquals("Bearer refresh", request.getHeader("Authorization"));
                    if (refreshes.incrementAndGet() == 1) {
                        return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
                    }
                    reuses.incrementAndGet();
                    return new MockResponse().setResponseCode(401);
                }
                return new MockResponse().setResponseCode(401);
            }
        });
        try (Legacy054Harness old = legacy ? new Legacy054Harness(context) : null) {
            if (legacy) {
                old.build(server.url("/").toString(), "header");
                old.request(server.url("/login").toString());
            } else {
                new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header").build();
                request(false, null, "/login");
            }
            String result = interceptor ? requestOutcome(old) : refreshOutcome(old);
            return result + ":refreshes=" + refreshes.get() + ":reuses=" + reuses.get();
        }
    }

    private enum Caller { INTERCEPTOR, EXISTS, ACCESS, REFRESH, PAYLOAD, SIGN_OUT }
    private enum Outcome { COMPLETE, FRONT_ONLY, REMOVAL, BARE_401, FORBIDDEN, RATE_LIMIT,
        RATE_LIMIT_RETRY_AFTER, SERVER_ERROR, UNAVAILABLE_RETRY_AFTER, RESET }

    private MockResponse outcome(Outcome outcome, String front) {
        switch (outcome) {
            case COMPLETE: return login(front);
            case FRONT_ONLY: return new MockResponse().addHeader("front-token", front);
            case REMOVAL: return new MockResponse().setResponseCode(401).addHeader("front-token", "remove");
            case BARE_401: return new MockResponse().setResponseCode(401);
            case FORBIDDEN: return new MockResponse().setResponseCode(403);
            case RATE_LIMIT: return new MockResponse().setResponseCode(429);
            case RATE_LIMIT_RETRY_AFTER: return new MockResponse().setResponseCode(429).addHeader("Retry-After", "3600");
            case SERVER_ERROR: return new MockResponse().setResponseCode(500);
            case UNAVAILABLE_RETRY_AFTER: return new MockResponse().setResponseCode(503).addHeader("Retry-After", "3600");
            case RESET: return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
            default: throw new AssertionError(outcome);
        }
    }

    private String invokeCaller(Legacy054Harness old, Caller caller) {
        try {
            switch (caller) {
                case INTERCEPTOR: return requestOutcome(old);
                case EXISTS: return String.valueOf(old == null ? SuperTokens.doesSessionExist(context) : old.invoke("doesSessionExist"));
                case ACCESS: return String.valueOf(old == null ? SuperTokens.getAccessToken(context) : old.invoke("getAccessToken"));
                case REFRESH: return refreshOutcome(old);
                case PAYLOAD: return String.valueOf(old == null ? SuperTokens.getAccessTokenPayloadSecurely(context) : old.invoke("getAccessTokenPayloadSecurely"));
                case SIGN_OUT:
                    if (old == null) SuperTokens.signOut(context); else old.invoke("signOut");
                    return "void";
                default: throw new AssertionError(caller);
            }
        } catch (Exception error) {
            return error.getClass().getName();
        }
    }

    private String matrixRow(boolean legacy, Caller caller, Outcome outcome) throws Exception {
        String fresh = front();
        String expired = Base64.getEncoder().encodeToString("{\"uid\":\"user\",\"ate\":1,\"up\":{}}".getBytes("UTF-8"));
        boolean needsExpiry = caller == Caller.EXISTS || caller == Caller.ACCESS || caller == Caller.PAYLOAD;
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger ordinaryRequests = new AtomicInteger();
        List<String> events = new ArrayList<>();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/login")) return login(needsExpiry ? expired : fresh);
                if (request.getPath().contains("/session/refresh")) {
                    return refreshes.incrementAndGet() == 1 ? outcome(outcome, fresh) : login(fresh);
                }
                return new MockResponse().setResponseCode(ordinaryRequests.incrementAndGet() == 1 ? 401 : 200);
            }
        });
        try (Legacy054Harness old = legacy ? new Legacy054Harness(context) : null) {
            if (legacy) {
                old.invoke("buildWithEvents", new Class<?>[]{String.class, List.class, String.class},
                        server.url("/").toString(), events, null);
                old.request(server.url("/login").toString());
            } else {
                new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                        .eventHandler(event -> events.add(event.name())).build();
                request(false, null, "/login");
            }
            events.clear();
            String first = invokeCaller(old, caller);
            List<String> firstEvents = new ArrayList<>();
            for (String event : events) firstEvents.add(event.split(":")[0]);
            String storedFront = context.getSharedPreferences("supertokens-android-shared-preferences", 0)
                    .getString("supertokens-android-fronttoken-key", null);
            int firstRefreshes = refreshes.get();
            ordinaryRequests.set(0);
            String second = requestOutcome(old);
            List<String> eventNames = new ArrayList<>();
            for (String event : events) eventNames.add(event.split(":")[0]);
            return first + ":front=" + storedFront + ":refreshes=" + firstRefreshes
                    + ":firstEvents=" + firstEvents + ":second=" + second + ":totalRefreshes=" + refreshes.get() + ":events=" + eventNames;
        }
    }

    private void compareCaller(Caller caller, boolean reset) throws Exception {
        List<String> differences = new ArrayList<>();
        for (Outcome outcome : Outcome.values()) {
            if (outcome == Outcome.FRONT_ONLY) continue;
            if ((outcome == Outcome.RESET) != reset) continue;
            context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
            SuperTokens.resetForTests();
            String expected = matrixRow(true, caller, outcome);
            context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
            SuperTokens.resetForTests();
            String actual = matrixRow(false, caller, outcome);
            if (!expected.equals(actual)) differences.add(outcome + " expected=" + expected + " actual=" + actual);
        }
        assertTrue(caller + " differences: " + differences, differences.isEmpty());
    }

    private void compareReset(Caller caller) throws Exception {
        compareCaller(caller, true);
    }

    @Test public void matrixInterceptor() throws Exception { compareCaller(Caller.INTERCEPTOR, false); }
    @Test public void matrixDoesSessionExist() throws Exception { compareCaller(Caller.EXISTS, false); }
    @Test public void matrixGetAccessToken() throws Exception { compareCaller(Caller.ACCESS, false); }
    @Test public void matrixAttemptRefreshingSession() throws Exception { compareCaller(Caller.REFRESH, false); }
    @Test public void failedRefreshEmitsLegacySignOutWithoutClearingSession() throws Exception {
        for (boolean legacy : new boolean[]{true, false}) {
            context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
            SuperTokens.resetForTests();
            assertEquals("java.io.IOException:front=" + front()
                    + ":refreshes=1:firstEvents=[SIGN_OUT]:second=200:totalRefreshes=2:events=[SIGN_OUT, REFRESH_SESSION]",
                    matrixRow(legacy, Caller.REFRESH, Outcome.FORBIDDEN));
        }
    }
    @Test public void matrixGetAccessTokenPayloadSecurely() throws Exception { compareCaller(Caller.PAYLOAD, false); }
    @Test public void matrixSignOut() throws Exception { compareCaller(Caller.SIGN_OUT, false); }
    @Test public void frontOnlyRefreshFromHeaderSessionIsAnAcceptedDifference() throws Exception {
        String expected = matrixRow(true, Caller.REFRESH, Outcome.FRONT_ONLY);
        context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
        SuperTokens.resetForTests();
        String actual = matrixRow(false, Caller.REFRESH, Outcome.FRONT_ONLY);
        assertTrue(expected, expected.startsWith("true:") && expected.contains(":refreshes=1:"));
        assertTrue(actual, actual.startsWith("java.io.IOException:") && actual.contains(":refreshes=1:"));
    }
    @Test public void frontOnlyGuardrailAppliesToEveryCaller() throws Exception {
        String expired = Base64.getEncoder().encodeToString("{\"uid\":\"user\",\"ate\":1,\"up\":{}}".getBytes("UTF-8"));
        for (Caller caller : Caller.values()) {
            context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
            SuperTokens.resetForTests();
            String expected = matrixRow(true, caller, Outcome.FRONT_ONLY);
            context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
            SuperTokens.resetForTests();
            String actual = matrixRow(false, caller, Outcome.FRONT_ONLY);
            String legacyFirst;
            String candidateFirst;
            switch (caller) {
                case INTERCEPTOR: legacyFirst = "200"; candidateFirst = "java.io.IOException"; break;
                case EXISTS: legacyFirst = "true"; candidateFirst = "false"; break;
                case ACCESS: legacyFirst = "access"; candidateFirst = "null"; break;
                case REFRESH: legacyFirst = "true"; candidateFirst = "java.io.IOException"; break;
                case PAYLOAD: legacyFirst = "{}"; candidateFirst = "java.io.IOException"; break;
                case SIGN_OUT: legacyFirst = "java.lang.IllegalStateException"; candidateFirst = "java.io.IOException"; break;
                default: throw new AssertionError(caller);
            }
            boolean expiredInput = caller == Caller.EXISTS || caller == Caller.ACCESS || caller == Caller.PAYLOAD;
            String suffix = ":second=200:totalRefreshes=2:events=";
            assertEquals(caller.toString(), legacyFirst + ":front=" + front()
                    + ":refreshes=1:firstEvents=[REFRESH_SESSION]" + suffix
                    + "[REFRESH_SESSION, REFRESH_SESSION]", expected);
            assertEquals(caller.toString(), candidateFirst + ":front=" + (expiredInput ? expired : front())
                    + ":refreshes=1:firstEvents=[]" + suffix + "[REFRESH_SESSION]", actual);
        }
    }
    @Test public void resetInterceptor() throws Exception { compareReset(Caller.INTERCEPTOR); }
    @Test public void resetDoesSessionExist() throws Exception { compareReset(Caller.EXISTS); }
    @Test public void resetGetAccessToken() throws Exception { compareReset(Caller.ACCESS); }
    @Test public void resetAttemptRefreshingSession() throws Exception { compareReset(Caller.REFRESH); }
    @Test public void resetGetAccessTokenPayloadSecurely() throws Exception { compareReset(Caller.PAYLOAD); }
    @Test public void resetSignOut() throws Exception { compareReset(Caller.SIGN_OUT); }

    private String readTimeout(boolean legacy) throws Exception {
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger protectedRequests = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/login")) {
                    try { return login(front()); } catch (Exception e) { throw new AssertionError(e); }
                }
                if (request.getPath().contains("/session/refresh")) {
                    if (refreshes.incrementAndGet() == 1) {
                        return new MockResponse().setHeadersDelay(1, TimeUnit.SECONDS);
                    }
                    try { return login(front()); } catch (Exception e) { throw new AssertionError(e); }
                }
                return new MockResponse().setResponseCode(protectedRequests.incrementAndGet() <= 2 ? 401 : 200);
            }
        });
        try (Legacy054Harness old = legacy ? new Legacy054Harness(context) : null) {
            if (legacy) {
                old.invoke("setReadTimeoutMillis", new Class<?>[]{long.class}, 200L);
                old.build(server.url("/").toString(), "header");
                old.request(server.url("/login").toString());
            } else {
                client = client.newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build();
                new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header").build();
                request(false, null, "/login");
            }
            String first = requestOutcome(old);
            String stored = context.getSharedPreferences("supertokens-android-shared-preferences", 0)
                    .getString("supertokens-android-fronttoken-key", null);
            String second = requestOutcome(old);
            return first + ":front=" + stored + ":second=" + second + ":refreshes=" + refreshes.get();
        }
    }

    @Test public void interceptorReadTimeoutRetainsSessionAndNext401Retries() throws Exception {
        String expected = readTimeout(true);
        context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
        SuperTokens.resetForTests();
        String actual = readTimeout(false);
        assertTrue(expected, expected.startsWith("java.net.SocketTimeoutException:front=" + front())
                && expected.endsWith(":second=200:refreshes=2"));
        assertEquals(expected, actual);
    }

    private String callTimeout(boolean legacy) throws Exception {
        String fresh = front();
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger protectedRequests = new AtomicInteger();
        List<String> events = new ArrayList<>();
        List<String> wire = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.CountDownLatch refreshEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseRefresh = new java.util.concurrent.CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                wire.add(request.getPath() + ":authorization=" + request.getHeader("Authorization")
                        + ":csrf=" + request.getHeader("anti-csrf"));
                if (request.getPath().equals("/login")) return login(fresh);
                if (request.getPath().contains("/session/refresh")) {
                    if (refreshes.incrementAndGet() == 1) {
                        refreshEntered.countDown();
                        try {
                            if (!releaseRefresh.await(20, TimeUnit.SECONDS)) {
                                return new MockResponse().setResponseCode(503);
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            return new MockResponse().setResponseCode(503);
                        }
                        return login(fresh);
                    }
                    return login(fresh);
                }
                return new MockResponse().setResponseCode(protectedRequests.incrementAndGet() <= 2 ? 401 : 200);
            }
        });
        try (Legacy054Harness old = legacy ? new Legacy054Harness(context) : null) {
            if (legacy) {
                old.invoke("setCallTimeoutMillis", new Class<?>[]{long.class}, 5000L);
                old.invoke("buildWithEvents", new Class<?>[]{String.class, List.class, String.class},
                        server.url("/").toString(), events, null);
                old.request(server.url("/login").toString());
            } else {
                client = client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build();
                new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                        .eventHandler(event -> events.add(event.name())).build();
                request(false, null, "/login");
            }
            events.clear();
            java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
            long submittedAt = System.nanoTime();
            java.util.concurrent.Future<String> firstCall = worker.submit(() -> requestOutcome(old));
            String first;
            try {
                assertTrue("refresh was not entered", refreshEntered.await(8, TimeUnit.SECONDS));
                assertTrue("refresh entry observed too late to establish deadline ordering",
                        System.nanoTime() - submittedAt < TimeUnit.SECONDS.toNanos(5));
                assertTrue("call completed before refresh entry was observed", !firstCall.isDone());
                first = firstCall.get(12, TimeUnit.SECONDS);
            } finally {
                releaseRefresh.countDown();
                firstCall.cancel(true);
                worker.shutdownNow();
                assertTrue("request worker did not stop", worker.awaitTermination(5, TimeUnit.SECONDS));
            }
            int firstRefreshes = refreshes.get();
            List<String> firstEvents = new ArrayList<>();
            for (String event : events) firstEvents.add(event.split(":")[0]);
            android.content.SharedPreferences prefs = context.getSharedPreferences("supertokens-android-shared-preferences", 0);
            String stored = ":front=" + prefs.getString("supertokens-android-fronttoken-key", null)
                    + ":access=" + prefs.getString("st-storage-item-st-access-token", null)
                    + ":refresh=" + prefs.getString("st-storage-item-st-refresh-token", null)
                    + ":csrf=" + prefs.getString("supertokens-android-anticsrf-key", null);
            String second = requestOutcome(old);
            List<String> allEvents = new ArrayList<>();
            for (String event : events) allEvents.add(event.split(":")[0]);
            return first + stored + ":firstRefreshes=" + firstRefreshes + ":firstEvents=" + firstEvents
                    + ":second=" + second + ":refreshes=" + refreshes.get() + ":events=" + allEvents + ":wire=" + wire;
        }
    }

    @Test public void callerOwnedOkHttpCallTimeoutRetainsSessionAndNext401Retries() throws Exception {
        String expected = callTimeout(true);
        context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
        SuperTokens.resetForTests();
        String actual = callTimeout(false);
        // OkHttp 3.12 can end the caller-owned outer call before interceptor refresh, leaving its read timeout in effect.
        String firstType = "3.12.13".equals(System.getProperty("session.matrix.okhttp"))
                ? "java.net.SocketTimeoutException:" : "java.io.InterruptedIOException:";
        assertTrue(expected, expected.startsWith(firstType)
                && expected.contains(":front=" + front()) && expected.contains(":firstRefreshes=1:")
                && expected.contains(":second=200:refreshes=2:"));
        assertEquals(expected, actual);
    }

    private String cookieUrlRow(boolean legacy, Caller caller, Outcome result) throws Exception {
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger protectedRequests = new AtomicInteger();
        List<String> refreshCookies = new ArrayList<>();
        List<String> events = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        String fresh = front();
        String expired = Base64.getEncoder().encodeToString("{\"uid\":\"user\",\"ate\":1,\"up\":{}}".getBytes("UTF-8"));
        boolean needsExpiry = caller == Caller.EXISTS || caller == Caller.ACCESS || caller == Caller.PAYLOAD || caller == Caller.SIGN_OUT;
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                paths.add(request.getPath());
                if (request.getPath().equals("/login")) return new MockResponse()
                        .addHeader("front-token", needsExpiry ? expired : fresh).addHeader("Set-Cookie", "sRefreshToken=refresh; Path=/");
                if (request.getPath().contains("/session/refresh")) {
                    refreshCookies.add(String.valueOf(request.getHeader("Cookie")));
                    return refreshes.incrementAndGet() == 1 ? outcome(result, fresh)
                            : result == Outcome.COMPLETE ? login(fresh) : new MockResponse().addHeader("front-token", fresh);
                }
                return new MockResponse().setResponseCode(protectedRequests.incrementAndGet() <= 2 ? 401 : 200);
            }
        });
        CookieHandler previous = CookieHandler.getDefault();
        try (Legacy054Harness old = legacy ? new Legacy054Harness(context) : null) {
            if (legacy) {
                old.invoke("useCookies");
                old.invoke("buildWithEventsForTransfer", new Class<?>[]{String.class, String.class, List.class, String.class},
                        server.url("/").toString(), "cookie", events, null);
                old.invoke("requestURLConnection", new Class<?>[]{String.class}, server.url("/login").toString());
            } else {
                CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
                new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("cookie")
                        .eventHandler(event -> events.add(event.name())).build();
                request(true, null, "/login");
            }
            events.clear();
            String first = caller == Caller.INTERCEPTOR ? urlOutcome(old) : invokeCaller(old, caller);
            String stored = context.getSharedPreferences("supertokens-android-shared-preferences", 0)
                    .getString("supertokens-android-fronttoken-key", null);
            List<String> firstEvents = new ArrayList<>();
            for (String event : events) firstEvents.add(event.split(":")[0]);
            int firstRefreshes = refreshes.get();
            assertTrue(caller + " " + result + " did not refresh on the first call", firstRefreshes > 0);
            if (needsExpiry) assertTrue(caller + " " + result + " was not proactive: " + paths,
                    paths.size() > 1 && paths.get(1).contains("/session/refresh"));
            String second = urlOutcome(old);
            List<String> allEvents = new ArrayList<>();
            for (String event : events) allEvents.add(event.split(":")[0]);
            assertTrue("No cookie refresh for " + result + " legacy=" + legacy, refreshes.get() > 0);
            for (String cookie : refreshCookies) assertTrue(cookie, cookie.contains("sRefreshToken=refresh"));
            return first + ":front=" + stored + ":firstRefreshes=" + firstRefreshes + ":firstEvents=" + firstEvents + ":second=" + second
                    + ":refreshes=" + refreshes.get() + ":events=" + allEvents;
        } finally {
            CookieHandler.setDefault(previous);
        }
    }

    private String urlOutcome(Legacy054Harness old) {
        try {
            return String.valueOf(old == null ? request(true, null, "/protected")
                    : old.invoke("requestURLConnection", new Class<?>[]{String.class}, server.url("/protected").toString()));
        } catch (Exception e) { return e.getClass().getName(); }
    }

    @Test public void cookieUrlConnectionRefreshOutcomes() throws Exception {
        for (Outcome outcome : new Outcome[]{Outcome.COMPLETE, Outcome.FRONT_ONLY, Outcome.REMOVAL, Outcome.BARE_401,
                Outcome.FORBIDDEN, Outcome.RATE_LIMIT, Outcome.RATE_LIMIT_RETRY_AFTER,
                Outcome.SERVER_ERROR, Outcome.UNAVAILABLE_RETRY_AFTER, Outcome.RESET}) {
            context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
            SuperTokens.resetForTests();
            String expected = cookieUrlRow(true, Caller.INTERCEPTOR, outcome);
            context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
            SuperTokens.resetForTests();
            assertEquals(outcome.toString(), expected, cookieUrlRow(false, Caller.INTERCEPTOR, outcome));
        }
    }

    @Test public void cookiePublicCallerRefreshOutcomes() throws Exception {
        List<String> differences = new ArrayList<>();
        for (Caller caller : new Caller[]{Caller.EXISTS, Caller.ACCESS, Caller.REFRESH, Caller.PAYLOAD, Caller.SIGN_OUT}) {
            for (Outcome outcome : Outcome.values()) {
                context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
                SuperTokens.resetForTests();
                String expected = cookieUrlRow(true, caller, outcome);
                context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
                SuperTokens.resetForTests();
                String actual = cookieUrlRow(false, caller, outcome);
                if (!expected.equals(actual)) differences.add(caller + " " + outcome + " expected=" + expected + " actual=" + actual);
            }
        }
        assertTrue(differences.toString(), differences.isEmpty());
    }

    private String exposedCookieRow(boolean legacy, Caller caller, Outcome result) throws Exception {
        String fresh = front();
        String expired = Base64.getEncoder().encodeToString(
                "{\"uid\":\"user\",\"ate\":1,\"up\":{}}".getBytes("UTF-8"));
        boolean proactive = caller == Caller.EXISTS || caller == Caller.ACCESS
                || caller == Caller.PAYLOAD || caller == Caller.SIGN_OUT;
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger protectedRequests = new AtomicInteger();
        List<String> wire = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> events = new ArrayList<>();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                wire.add(path + ":cookie=" + request.getHeader("Cookie")
                        + ":authorization=" + request.getHeader("Authorization")
                        + ":csrf=" + request.getHeader("anti-csrf"));
                if (path.equals("/login")) return new MockResponse()
                        .addHeader("front-token", proactive ? expired : fresh)
                        .addHeader("st-access-token", "exposed-initial")
                        .addHeader("anti-csrf", "csrf-initial")
                        .addHeader("Set-Cookie", "sAccessToken=cookie-initial; Path=/")
                        .addHeader("Set-Cookie", "sRefreshToken=refresh-initial; Path=/");
                if (path.contains("/session/refresh")) {
                    if (refreshes.incrementAndGet() == 1 && result != Outcome.FRONT_ONLY) {
                        return outcome(result, fresh);
                    }
                    return new MockResponse().addHeader("front-token", fresh)
                            .addHeader("st-access-token", "exposed-rotated")
                            .addHeader("anti-csrf", "csrf-rotated")
                            .addHeader("Set-Cookie", "sAccessToken=cookie-rotated; Path=/")
                            .addHeader("Set-Cookie", "sRefreshToken=refresh-rotated; Path=/");
                }
                return new MockResponse().setResponseCode(protectedRequests.incrementAndGet() == 1 ? 401 : 200);
            }
        });
        CookieHandler previous = CookieHandler.getDefault();
        try (Legacy054Harness old = legacy ? new Legacy054Harness(context) : null) {
            if (legacy) {
                old.invoke("useCookies");
                old.invoke("buildWithEventsForTransfer", new Class<?>[]{String.class, String.class, List.class, String.class},
                        server.url("/").toString(), "cookie", events, null);
                old.invoke("requestURLConnection", new Class<?>[]{String.class}, server.url("/login").toString());
            } else {
                CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
                new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("cookie")
                        .eventHandler(event -> events.add(event.name())).build();
                request(true, null, "/login");
            }
            events.clear();
            String first = caller == Caller.INTERCEPTOR ? urlOutcome(old) : invokeCaller(old, caller);
            int firstRefreshes = refreshes.get();
            assertTrue(caller + " " + result + " did not refresh", firstRefreshes > 0);
            List<String> firstEvents = new ArrayList<>();
            for (String event : events) firstEvents.add(event.split(":")[0]);
            android.content.SharedPreferences prefs = context.getSharedPreferences("supertokens-android-shared-preferences", 0);
            String projection = ":front=" + prefs.getString("supertokens-android-fronttoken-key", null)
                    + ":access=" + prefs.getString("st-storage-item-st-access-token", null)
                    + ":refresh=" + prefs.getString("st-storage-item-st-refresh-token", null)
                    + ":csrf=" + prefs.getString("supertokens-android-anticsrf-key", null);
            int firstWireSize = wire.size();
            protectedRequests.set(0);
            String second = urlOutcome(old);
            boolean initialRefreshCookie = false;
            boolean subsequentAccessCookie = false;
            synchronized (wire) {
                for (int i = 0; i < wire.size(); i++) {
                    String request = wire.get(i);
                    if (request.contains("/session/refresh") && request.contains("sRefreshToken=refresh-initial")) {
                        initialRefreshCookie = true;
                    }
                    if (i >= firstWireSize && request.startsWith("/protected:")
                            && request.contains("sAccessToken=cookie-rotated")) subsequentAccessCookie = true;
                }
            }
            assertTrue("initial refresh cookie not sent: " + wire, initialRefreshCookie);
            if (result == Outcome.FRONT_ONLY && caller != Caller.SIGN_OUT) {
                assertTrue("rotated access cookie not sent on subsequent protected request: " + wire, subsequentAccessCookie);
            }
            List<String> allEvents = new ArrayList<>();
            for (String event : events) allEvents.add(event.split(":")[0]);
            return first + projection + ":firstRefreshes=" + firstRefreshes + ":firstEvents=" + firstEvents
                    + ":second=" + second + ":refreshes=" + refreshes.get() + ":events=" + allEvents + ":wire=" + wire;
        } finally {
            CookieHandler.setDefault(previous);
        }
    }

    @Test public void exposedCookieRefreshOutcomesAndSubsequentWireCredentials() throws Exception {
        List<String> differences = new ArrayList<>();
        for (Caller caller : Caller.values()) {
            for (Outcome outcome : new Outcome[]{Outcome.FRONT_ONLY, Outcome.REMOVAL, Outcome.BARE_401,
                    Outcome.FORBIDDEN, Outcome.RATE_LIMIT, Outcome.SERVER_ERROR}) {
                context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
                SuperTokens.resetForTests();
                String expected = exposedCookieRow(true, caller, outcome);
                context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
                SuperTokens.resetForTests();
                String actual = exposedCookieRow(false, caller, outcome);
                if (!expected.equals(actual)) differences.add(caller + " " + outcome + " expected=" + expected + " actual=" + actual);
            }
        }
        assertTrue(differences.toString(), differences.isEmpty());
    }
}
