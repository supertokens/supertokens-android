package com.supertokens.session.legacy054;

import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.SessionTestFixture;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.Utils;
import java.nio.charset.StandardCharsets;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LegacyShapeCrossProductTest extends SessionTestFixture {
    @Override protected boolean initializeOnSetup() { return false; }

    private enum Prior { ABSENT, HEADER, COOKIE, EXPOSED_COOKIE }
    private enum Shape { NONE, COMPLETE, ACCESS_FRONT, FRONT, FRONT_CSRF, REMOVE,
        REMOVE_ACCESS, REMOVE_REFRESH, DUPLICATE_FRONT, DUPLICATE_ACCESS }

    private static final String FRONT = Base64.getEncoder().encodeToString(
            "{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{}}".getBytes(StandardCharsets.UTF_8));

    private MockResponse response(Shape shape, int status) {
        MockResponse result = new MockResponse().setResponseCode(status);
        switch (shape) {
            case NONE: return result;
            case COMPLETE: return result.addHeader("front-token", FRONT).addHeader("st-access-token", "access2").addHeader("st-refresh-token", "refresh2");
            case ACCESS_FRONT: return result.addHeader("front-token", FRONT).addHeader("st-access-token", "access2");
            case FRONT: return result.addHeader("front-token", FRONT);
            case FRONT_CSRF: return result.addHeader("front-token", FRONT).addHeader("anti-csrf", "csrf2");
            case REMOVE: return result.addHeader("front-token", "remove");
            case REMOVE_ACCESS: return result.addHeader("front-token", "remove").addHeader("st-access-token", "access2");
            case REMOVE_REFRESH: return result.addHeader("front-token", "remove").addHeader("st-refresh-token", "refresh2");
            case DUPLICATE_FRONT: return result.addHeader("front-token", FRONT).addHeader("front-token", "remove");
            case DUPLICATE_ACCESS: return result.addHeader("front-token", FRONT).addHeader("st-refresh-token", "refresh2").addHeader("st-access-token", "first").addHeader("st-access-token", "second");
            default: throw new AssertionError(shape);
        }
    }

    private String run(boolean legacy, String config, Prior prior, Shape shape, int status, boolean urlConnection) throws Exception {
        List<String> events = new ArrayList<>();
        AtomicReference<String> wire = new AtomicReference<>();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/login")) {
                    MockResponse login = new MockResponse().addHeader("front-token", FRONT).addHeader("anti-csrf", "csrf1");
                    if (prior == Prior.HEADER || prior == Prior.EXPOSED_COOKIE) login.addHeader("st-access-token", "access1");
                    if (prior == Prior.HEADER) login.addHeader("st-refresh-token", "refresh1");
                    return login;
                }
                if (request.getPath().equals("/shape")) return response(shape, status);
                if (request.getPath().equals("/next")) {
                    wire.set(request.getHeader("Authorization") + ":" + request.getHeader("anti-csrf"));
                    return new MockResponse();
                }
                return new MockResponse().setResponseCode(401);
            }
        });
        CookieHandler previousCookies = CookieHandler.getDefault();
        try (Legacy054Harness old = legacy ? new Legacy054Harness(context) : null) {
            if (legacy) old.invoke("useCookies");
            else CookieHandler.setDefault(new CookieManager());
            if (legacy) {
                old.invoke("buildWithEventsForTransfer", new Class<?>[]{String.class, String.class, List.class, String.class},
                        server.url("/").toString(), config, events, null);
            } else {
                new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod(config)
                        .eventHandler(type -> events.add(type.name())).build();
            }
            if (prior != Prior.ABSENT) assertEquals("prior state setup (legacy=" + legacy + ")", "200", call(old, urlConnection, "/login"));
            String outcome = call(old, urlConnection, "/shape");
            String exists = String.valueOf(legacy ? old.invoke("doesSessionExist") : SuperTokens.doesSessionExist(context));
            String user;
            try { user = String.valueOf(legacy ? old.invoke("getUserId") : SuperTokens.getUserId(context)); }
            catch (Exception e) { user = e.getClass().getName(); }
            String access = String.valueOf(legacy ? old.invoke("getAccessToken") : SuperTokens.getAccessToken(context));
            String utility = String.valueOf(legacy ? old.invoke("getTokenForHeaderAuth") : Utils.getTokenForHeaderAuth(Utils.TokenType.ACCESS, context));
            List<String> normalized = new ArrayList<>();
            for (String event : events) normalized.add(event.split(":")[0]);
            StringBuilder stored = new StringBuilder();
            for (String key : new String[]{"supertokens-android-fronttoken-key", "supertokens-android-anticsrf-key",
                    "st-storage-item-st-access-token", "st-storage-item-st-refresh-token", "st-storage-item-sIRTFrontend"}) {
                stored.append(':').append(context.getSharedPreferences("supertokens-android-shared-preferences", 0).getString(key, null));
            }
            String next = call(old, urlConnection, "/next");
            return outcome + ":" + exists + ":" + user + ":" + access + ":" + utility + stored + ":events=" + normalized + ":next=" + next + ":wire=" + wire.get();
        } finally {
            CookieHandler.setDefault(previousCookies);
        }
    }

    private String call(Legacy054Harness old, boolean urlConnection, String path) {
        try {
            if (old == null) return String.valueOf(request(urlConnection, null, path));
            return String.valueOf(urlConnection ? old.invoke("requestURLConnection", new Class<?>[]{String.class}, server.url(path).toString())
                    : old.request(server.url(path).toString()).get("status"));
        } catch (Exception e) { return e.getClass().getName() + (path.equals("/login") ? ":" + e.getMessage() : ""); }
    }

    private String oracle401(Prior prior, Shape shape, boolean urlConnection) {
        String access = shape == Shape.DUPLICATE_ACCESS ? "second"
                : shape == Shape.COMPLETE || shape == Shape.ACCESS_FRONT ? "access2"
                : prior == Prior.HEADER || prior == Prior.EXPOSED_COOKIE ? "access1" : null;
        String refresh = shape == Shape.COMPLETE || shape == Shape.DUPLICATE_ACCESS ? "refresh2"
                : prior == Prior.HEADER ? "refresh1" : null;
        String csrf = shape == Shape.FRONT_CSRF ? "csrf2" : prior == Prior.ABSENT ? null : "csrf1";
        String wireAccess = access != null && refresh != null ? "Bearer " + access : null;
        String outcome = urlConnection ? "java.lang.IllegalAccessException" : "java.io.IOException";
        return outcome + ":true:user:" + access + ":" + access + ":" + FRONT + ":" + csrf
                + ":" + access + ":" + refresh + ":null:events=[SESSION_CREATED]:next=200:wire="
                + wireAccess + ":" + csrf;
    }

    private String cookieReplacement200(Shape shape, String csrf) {
        String access = shape == Shape.COMPLETE ? "access2" : "second";
        return "200:true:user:" + access + ":" + access + ":" + FRONT + ":" + csrf
                + ":" + access + ":refresh2:null:events=[SESSION_CREATED]:next=200:wire=Bearer "
                + access + ":" + csrf;
    }

    private void matrix(String config, boolean urlConnection) throws Exception {
        List<String> differences = new ArrayList<>();
        for (Prior prior : Prior.values()) for (Shape shape : Shape.values()) for (int status : new int[]{200, 401}) {
            if (shape == Shape.NONE) continue;
            try {
            context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
            SuperTokens.resetForTests();
            String expected = run(true, config, prior, shape, status, urlConnection);
            context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
            SuperTokens.resetForTests();
            String actual = run(false, config, prior, shape, status, urlConnection);
            boolean nonRemoval401 = status == 401 && shape != Shape.REMOVE && shape != Shape.REMOVE_ACCESS
                    && shape != Shape.REMOVE_REFRESH && shape != Shape.DUPLICATE_FRONT;
            if (nonRemoval401) {
                String characterized = oracle401(prior, shape, urlConnection);
                if (!characterized.equals(expected)) differences.add(prior + "/" + shape + "/401 oracle=" + expected + " characterized=" + characterized);
                context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
                SuperTokens.resetForTests();
                String safe = run(false, config, prior, Shape.NONE, status, urlConnection);
                if (!safe.equals(actual)) differences.add(prior + "/" + shape + "/401 safe=" + safe + " actual=" + actual);
            } else if (status == 200 && (prior == Prior.COOKIE || prior == Prior.EXPOSED_COOKIE)
                    && (shape == Shape.COMPLETE || shape == Shape.DUPLICATE_ACCESS)) {
                String oracle = cookieReplacement200(shape, "csrf1");
                String safe = cookieReplacement200(shape, "null");
                if (!oracle.equals(expected)) differences.add(prior + "/" + shape + "/200 oracle=" + expected + " characterized=" + oracle);
                if (!safe.equals(actual)) differences.add(prior + "/" + shape + "/200 safe=" + safe + " actual=" + actual);
            } else if (!expected.equals(actual)) differences.add(prior + "/" + shape + "/" + status + " expected=" + expected + " actual=" + actual);
            } catch (AssertionError failure) {
                differences.add(prior + "/" + shape + "/" + status + " setup failure: " + failure);
            }
        }
        assertTrue(config + "/urlConnection=" + urlConnection + " differences: " + differences, differences.isEmpty());
    }

    @Test public void absentRemovalAndSameIdentityFrontOnlyMatchPublishedSdk() throws Exception {
        for (boolean urlConnection : new boolean[]{false, true}) {
            for (Shape shape : new Shape[]{Shape.REMOVE, Shape.FRONT, Shape.FRONT_CSRF}) {
                Prior prior = shape == Shape.REMOVE ? Prior.ABSENT : Prior.HEADER;
                context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
                SuperTokens.resetForTests();
                String expected = run(true, "header", prior, shape, 200, urlConnection);
                context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
                SuperTokens.resetForTests();
                assertEquals(prior + "/" + shape + "/urlConnection=" + urlConnection,
                        expected, run(false, "header", prior, shape, 200, urlConnection));
            }
        }
    }

    @Test public void cookieToHeaderWithUnchangedFrontTokenDoesNotEmitPayloadUpdate() throws Exception {
        for (boolean urlConnection : new boolean[]{false, true}) {
            for (Prior prior : new Prior[]{Prior.COOKIE, Prior.EXPOSED_COOKIE}) {
                context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
                SuperTokens.resetForTests();
                String expected = run(true, "header", prior, Shape.COMPLETE, 200, urlConnection);
                context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
                SuperTokens.resetForTests();
                String actual = run(false, "header", prior, Shape.COMPLETE, 200, urlConnection);
                assertEquals(prior + "/urlConnection=" + urlConnection,
                        expected.split(":events=")[1].split(":next=")[0],
                        actual.split(":events=")[1].split(":next=")[0]);
            }
        }
    }

    @Test public void removal401EventsMatchPublishedSdk() throws Exception {
        for (boolean urlConnection : new boolean[]{false, true}) {
            context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
            SuperTokens.resetForTests();
            String expected = run(true, "header", Prior.HEADER, Shape.REMOVE, 401, urlConnection);
            context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
            SuperTokens.resetForTests();
            assertEquals(expected, run(false, "header", Prior.HEADER, Shape.REMOVE, 401, urlConnection));
        }
    }

    @Test public void headerOkHttp() throws Exception { matrix("header", false); }
    @Test public void headerURLConnection() throws Exception { matrix("header", true); }
    @Test public void cookieOkHttp() throws Exception { matrix("cookie", false); }
    @Test public void cookieURLConnection() throws Exception { matrix("cookie", true); }
}
