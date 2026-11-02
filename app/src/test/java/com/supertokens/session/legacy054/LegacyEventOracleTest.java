package com.supertokens.session.legacy054;

import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.SessionTestFixture;
import com.supertokens.session.SessionTokens;
import com.supertokens.session.SuperTokens;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyEventOracleTest extends SessionTestFixture {
    @Override protected boolean initializeOnSetup() { return false; }

    private static SessionTokens tokens(String handle, String claim) throws Exception {
        JSONObject payload = new JSONObject().put("sessionHandle", handle).put("sub", "primary-user")
                .put("rsub", "recipe-user").put("tId", "public").put("exp", 4102444800L).put("iat", 1700000000L).put("claim", claim);
        String access = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"version\":\"5\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8)) + ".c2lnbmF0dXJl";
        String front = Base64.getEncoder().encodeToString(new JSONObject().put("uid", "primary-user")
                .put("ate", 4102444800000L).put("up", payload).toString().getBytes(StandardCharsets.UTF_8));
        return new SessionTokens(access, handle + "-refresh-" + claim, front, "csrf");
    }

    private static MockResponse rotation(SessionTokens tokens) {
        return new MockResponse().addHeader("st-access-token", tokens.getAccessToken())
                .addHeader("st-refresh-token", tokens.getRefreshToken()).addHeader("front-token", tokens.getFrontToken());
    }

    private void assertEvents(List<String> events, String... expected) {
        List<String> actual = new ArrayList<>();
        for (String type : expected) actual.add(type + ":" + Thread.currentThread().getId());
        assertEquals(actual, events);
    }

    private Legacy054Harness oracle(List<String> events, String throwOn) throws Exception {
        Legacy054Harness oracle = new Legacy054Harness(context);
        oracle.invoke("buildWithEvents", new Class<?>[]{String.class, List.class, String.class},
                server.url("/").toString(), events, throwOn);
        return oracle;
    }

    @Test public void recordOrderAndProducerThread() throws Exception {
        List<String> events = new ArrayList<>();
        try (Legacy054Harness oracle = oracle(events, null)) {
            server.enqueue(new MockResponse().setResponseCode(401));
            oracle.request(server.url("/absent").toString());
            System.out.println("ORACLE absent401 " + events);
            assertEvents(events, "UNAUTHORISED");
            events.clear();
            oracle.invoke("refresh");
            System.out.println("ORACLE absentRefresh " + events);
            assertEvents(events, "UNAUTHORISED");
            events.clear();
            oracle.invoke("signOut");
            System.out.println("ORACLE absentSignOut " + events);
            assertEvents(events, "SIGN_OUT");
            events.clear();
            server.enqueue(rotation(tokens("A", "one")));
            oracle.request(server.url("/login").toString());
            System.out.println("ORACLE login " + events);
            assertEvents(events, "SESSION_CREATED");
            events.clear();
            server.enqueue(rotation(tokens("A", "two")));
            oracle.invoke("refresh");
            System.out.println("ORACLE refresh " + events + " producer=" + Thread.currentThread().getId());
            assertEvents(events, "ACCESS_TOKEN_PAYLOAD_UPDATED", "REFRESH_SESSION");
        }
    }

    @Test public void promotionDuringRefreshEventOrder() throws Exception {
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (Legacy054Harness legacy = oracle(events, null)) {
            SessionTokens initial = tokens("A", "one");
            SessionTokens promoted = tokens("A", "two");
            SessionTokens refreshed = tokens("A", "three");
            server.enqueue(rotation(initial));
            legacy.request(server.url("/login").toString());
            events.clear();
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) throws InterruptedException {
                    if (request.getPath().contains("/session/refresh")) {
                        entered.countDown();
                        if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("refresh timed out");
                        return rotation(refreshed);
                    }
                    return new MockResponse().addHeader("front-token", promoted.getFrontToken())
                            .addHeader("st-access-token", promoted.getAccessToken());
                }
            });
            java.util.concurrent.Future<Object> refresh = worker.submit(() -> legacy.invoke("refresh"));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            legacy.request(server.url("/update").toString());
            release.countDown();
            assertEquals(Boolean.TRUE, refresh.get(5, java.util.concurrent.TimeUnit.SECONDS));
            List<String> types = new ArrayList<>();
            for (String event : events) types.add(event.substring(0, event.indexOf(':')));
            assertEquals(java.util.Arrays.asList("ACCESS_TOKEN_PAYLOAD_UPDATED", "ACCESS_TOKEN_PAYLOAD_UPDATED", "REFRESH_SESSION"), types);
            assertEquals(refreshed.getAccessToken(), legacy.invoke("getAccessToken"));
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test public void recordThrowingHandlerState() throws Exception {
        List<String> events = new ArrayList<>();
        try (Legacy054Harness oracle = oracle(events, "SESSION_CREATED")) {
            server.enqueue(rotation(tokens("A", "one")));
            try {
                oracle.request(server.url("/login").toString());
                fail("Expected handler error");
            } catch (IllegalStateException expected) { assertEquals("oracle-handler", expected.getMessage()); }
            System.out.println("ORACLE throwingLogin " + events + " exists=" + oracle.invoke("doesSessionExist")
                    + " access=" + (oracle.invoke("getAccessToken") != null));
            assertEquals(Boolean.TRUE, oracle.invoke("doesSessionExist"));
            assertNotNull(oracle.invoke("getAccessToken"));
        }
    }

    @Test public void recordThrowingRefreshOrderAndType() throws Exception {
        List<String> events = new ArrayList<>();
        try (Legacy054Harness oracle = oracle(events, "ACCESS_TOKEN_PAYLOAD_UPDATED")) {
            server.enqueue(rotation(tokens("A", "one")));
            oracle.request(server.url("/login").toString());
            events.clear();
            server.enqueue(rotation(tokens("A", "two")));
            try { oracle.invoke("refresh"); fail("Expected handler error"); }
            catch (Exception expected) {
                assertEquals(java.io.IOException.class, expected.getClass());
                assertTrue(expected.getCause() instanceof IllegalStateException);
                assertEvents(events, "ACCESS_TOKEN_PAYLOAD_UPDATED");
                System.out.println("ORACLE throwingRefresh type=" + expected.getClass().getName()
                        + " message=" + expected.getMessage() + " events=" + events);
            }
        }
    }

    @Test public void recordRawFrontSetterEvents() throws Exception {
        List<String> events = new ArrayList<>();
        try (Legacy054Harness oracle = oracle(events, null)) {
            oracle.invoke("setFrontItem", new Class<?>[]{String.class}, tokens("A", "one").getFrontToken());
            System.out.println("ORACLE frontItemCreate " + events);
            assertEvents(events);
            events.clear();
            oracle.invoke("setFrontItem", new Class<?>[]{String.class}, tokens("A", "two").getFrontToken());
            System.out.println("ORACLE frontItemUpdate " + events);
            assertEvents(events, "ACCESS_TOKEN_PAYLOAD_UPDATED");
            events.clear();
            oracle.invoke("setFrontItem", new Class<?>[]{String.class}, "remove");
            System.out.println("ORACLE frontItemRemove " + events);
            assertEvents(events);
        }
    }

    @Test public void refreshHandlerExceptionParityByEntryPoint() throws Exception {
        for (String throwOn : new String[]{"ACCESS_TOKEN_PAYLOAD_UPDATED", "REFRESH_SESSION"}) {
            for (String entry : new String[]{"refresh", "getAccessToken", "doesSessionExist", "getAccessTokenPayloadSecurely",
                    "getUserId", "request", "requestURLConnection", "signOut", "signOutReactive", "ordinaryRequest", "ordinaryURLConnection"}) {
                java.util.Map<String, Object> published = entryOutcome(true, entry, throwOn);
                java.util.Map<String, Object> candidate = entryOutcome(false, entry, throwOn);
                String key = "supertokens-android-fronttoken-key";
                String publishedFront = (String) published.remove(key);
                String candidateFront = (String) candidate.remove(key);
                if (throwOn.equals("ACCESS_TOKEN_PAYLOAD_UPDATED") && !entry.equals("getUserId")) {
                    // Published setItem calls the payload handler before writing front-token; atomic candidate commits first.
                    assertEquals("one", frontClaim(publishedFront));
                    assertEquals("two", frontClaim(candidateFront));
                } else assertEquals(entry + " front token", publishedFront, candidateFront);
                System.out.println("ENTRY " + entry + " throwOn=" + throwOn + " published=" + published);
                assertEquals(entry + " / " + throwOn, published, candidate);
            }
        }
    }

    private String frontClaim(String front) throws Exception {
        return new JSONObject(new String(Base64.getDecoder().decode(front), StandardCharsets.UTF_8))
                .getJSONObject("up").getString("claim");
    }

    private java.util.Map<String, Object> entryOutcome(boolean published, String entry, String throwOn) throws Exception {
        SuperTokens.resetForTests();
        android.content.SharedPreferences preferences = context.getSharedPreferences("supertokens-android-shared-preferences", 0);
        preferences.edit().clear().commit();
        List<String> events = new ArrayList<>();
        Legacy054Harness legacy = published ? oracle(events, throwOn) : null;
        if (!published) new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                .eventHandler(event -> {
                    events.add(event.name() + ":" + Thread.currentThread().getId());
                    if (event.name().equals(throwOn)) throw new IllegalStateException("oracle-handler");
                }).build();
        try {
            SessionTokens initial = tokens("A", "one");
            JSONObject initialFront = new JSONObject(new String(Base64.getDecoder().decode(initial.getFrontToken()), StandardCharsets.UTF_8));
            if (!entry.equals("signOutReactive") && !entry.startsWith("ordinary")) initialFront.put("ate", 1);
            String front = Base64.getEncoder().encodeToString(initialFront.toString().getBytes(StandardCharsets.UTF_8));
            SessionTokens refreshed = tokens("A", "two");
            java.util.concurrent.atomic.AtomicInteger ordinary = new java.util.concurrent.atomic.AtomicInteger();
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                    if (request.getPath().equals("/login")) return rotation(new SessionTokens(initial.getAccessToken(), initial.getRefreshToken(), front, null));
                    if (request.getPath().equals("/auth/session/refresh") || entry.startsWith("ordinary")) return rotation(refreshed);
                    return ordinary.getAndIncrement() == 0 ? new MockResponse().setResponseCode(401)
                            : new MockResponse().setBody("{\"status\":\"OK\"}");
                }
            });
            if (published) legacy.request(server.url("/login").toString());
            else assertEquals(200, request(false, null, "/login"));
            events.clear();
            int before = server.getRequestCount();
            Object result = null;
            Throwable error = null;
            try {
                if (entry.equals("request") || entry.equals("ordinaryRequest")) {
                    result = published ? legacy.request(server.url("/operation").toString()).get("status") : request(false, null, "/operation");
                } else if (entry.equals("requestURLConnection") || entry.equals("ordinaryURLConnection")) {
                    result = published ? legacy.invoke("requestURLConnection", new Class<?>[]{String.class}, server.url("/operation").toString()) : request(true, null, "/operation");
                } else if (published) result = legacy.invoke(entry.equals("signOutReactive") ? "signOut" : entry);
                else switch (entry) {
                    case "refresh": result = SuperTokens.attemptRefreshingSession(context); break;
                    case "getAccessToken": result = SuperTokens.getAccessToken(context); break;
                    case "doesSessionExist": result = SuperTokens.doesSessionExist(context); break;
                    case "getAccessTokenPayloadSecurely": result = SuperTokens.getAccessTokenPayloadSecurely(context).toString(); break;
                    case "getUserId": result = SuperTokens.getUserId(context); break;
                    default: SuperTokens.signOut(context);
                }
            } catch (Exception failure) { error = failure; }
            java.util.Map<String, Object> outcome = new java.util.LinkedHashMap<>();
            outcome.put("result", result);
            outcome.put("exception", error == null ? null : error.getClass().getName());
            outcome.put("message", error == null ? null : error.getMessage());
            outcome.put("cause", error == null || error.getCause() == null ? null : error.getCause().getClass().getName());
            outcome.put("causeMessage", error == null || error.getCause() == null ? null : error.getCause().getMessage());
            outcome.put("requests", server.getRequestCount() - before);
            outcome.put("events", events);
            for (String key : new String[]{"st-storage-item-st-access-token", "st-storage-item-st-refresh-token", "supertokens-android-fronttoken-key"}) {
                outcome.put(key, preferences.getString(key, null));
            }
            return outcome;
        } finally { if (legacy != null) legacy.close(); }
    }
}
