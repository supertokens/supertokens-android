package com.supertokens.session.legacy054;

import com.supertokens.session.Legacy054Harness;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacySignOutBodyCharacterizationTest {
    @Test public void sameOriginRedirectCommitsRemovalBeforeGeneralError() throws Exception {
        CookieHandler previous = CookieHandler.getDefault();
        CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        for (int redirect : new int[]{302, 307}) try (MockWebServer server = new MockWebServer()) {
            server.start();
            OraclePreferences store = new OraclePreferences(new LinkedHashMap<>());
            try (Legacy054Harness legacy = new Legacy054Harness(store.context)) {
                legacy.build(server.url("/").toString(), "header");
                server.enqueue(new MockResponse().addHeader("st-access-token", "access").addHeader("st-refresh-token", "refresh")
                        .addHeader("front-token", Base64.getEncoder().encodeToString(
                                "{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{}}".getBytes(StandardCharsets.UTF_8))));
                legacy.request(server.url("/login").toString());
                server.enqueue(new MockResponse().setResponseCode(redirect).addHeader("Location", "/auth/done"));
                server.enqueue(new MockResponse().addHeader("front-token", "remove")
                        .setBody("{\"status\":\"GENERAL_ERROR\",\"message\":\"failure\"}"));
                try { legacy.invoke("signOut"); fail(); }
                catch (Exception error) { assertEquals("com.supertokens.session.SuperTokensGeneralError", error.getClass().getName()); }
                assertEquals(false, legacy.invoke("doesSessionExist"));
                assertEquals(3, server.getRequestCount());
                System.out.println("SIGNOUT redirect=" + redirect + " GENERAL_ERROR removal committed");
            }
        } finally { CookieHandler.setDefault(previous); }
    }

    @Test public void reactiveRefreshFinalRemovalCommitsBeforeBodyError() throws Exception {
        CookieHandler previous = CookieHandler.getDefault();
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            for (boolean cookie : new boolean[]{false, true}) for (boolean removal : new boolean[]{false, true}) {
                CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
                OraclePreferences store = new OraclePreferences(new LinkedHashMap<>());
                try (Legacy054Harness legacy = new Legacy054Harness(store.context)) {
                    legacy.build(server.url("/").toString(), cookie ? "cookie" : "header");
                    String front = Base64.getEncoder().encodeToString(
                            "{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{}}".getBytes(StandardCharsets.UTF_8));
                    MockResponse login = new MockResponse().addHeader("front-token", front);
                    MockResponse refresh = new MockResponse().addHeader("front-token", front);
                    if (!cookie) {
                        login.addHeader("st-access-token", "access-before").addHeader("st-refresh-token", "refresh-before");
                        refresh.addHeader("st-access-token", "access-after").addHeader("st-refresh-token", "refresh-after");
                    }
                    server.enqueue(login);
                    legacy.request(server.url("/login").toString());
                    server.enqueue(new MockResponse().setResponseCode(401));
                    server.enqueue(refresh);
                    MockResponse finalResponse = new MockResponse().setBody("{\"status\":\"GENERAL_ERROR\",\"message\":\"failure\"}");
                    if (removal) finalResponse.addHeader("front-token", "remove");
                    server.enqueue(finalResponse);
                    int requests = server.getRequestCount();
                    try { legacy.invoke("signOut"); fail(); }
                    catch (Exception error) { assertEquals("com.supertokens.session.SuperTokensGeneralError", error.getClass().getName()); }
                    assertEquals(requests + 3, server.getRequestCount());
                    for (String path : new String[]{"/login", "/auth/signout", "/auth/session/refresh", "/auth/signout"}) {
                        okhttp3.mockwebserver.RecordedRequest sent = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS);
                        assertNotNull(sent);
                        assertEquals(path, sent.getPath());
                        if (path.equals("/auth/session/refresh")) assertEquals(cookie ? null : "Bearer refresh-before", sent.getHeader("Authorization"));
                    }
                    assertEquals(!removal, legacy.invoke("doesSessionExist"));
                    if (!cookie && !removal) assertEquals("access-after", legacy.invoke("getAccessToken"));
                    System.out.println("SIGNOUT reactive cookie=" + cookie + " removal=" + removal + " GENERAL_ERROR exists=" + !removal);
                }
            }
        } finally { CookieHandler.setDefault(previous); }
    }

    @Test public void publishedBodyAndRemovalMatrix() throws Exception {
        String[] bodies = {"{\"status\":\"OK\"}", "{\"status\":\"UNRECOGNIZED\"}", "{}", "<html>oops</html>", "",
                "{\"status\":\"GENERAL_ERROR\",\"message\":\"failure\"}", "{\"status\":42}", "{\"status\":null}"};
        CookieHandler previous = CookieHandler.getDefault();
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            for (boolean cookie : new boolean[]{false, true}) for (boolean removal : new boolean[]{false, true}) {
                for (int kind = 0; kind < bodies.length; kind++) {
                    CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
                    Map<String, Object> initial = new LinkedHashMap<>();
                    initial.put(OraclePreferences.FRONT, Base64.getEncoder().encodeToString(
                            "{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{}}".getBytes(StandardCharsets.UTF_8)));
                    initial.put(OraclePreferences.MARKER, "1");
                    if (!cookie) {
                        initial.put(OraclePreferences.ACCESS, "access");
                        initial.put(OraclePreferences.REFRESH, "refresh");
                    }
                    OraclePreferences store = new OraclePreferences(initial);
                    try (Legacy054Harness legacy = new Legacy054Harness(store.context)) {
                        legacy.build(server.url("/").toString(), cookie ? "cookie" : "header");
                        MockResponse response = new MockResponse().setBody(bodies[kind]);
                        if (removal) response.addHeader("front-token", "remove");
                        server.enqueue(response);
                        String outcome = "SUCCESS";
                        try { legacy.invoke("signOut"); }
                        catch (Exception error) { outcome = error.getClass().getName(); }
                        boolean exists = (Boolean) legacy.invoke("doesSessionExist");
                        System.out.println("SIGNOUT cookie=" + cookie + " removal=" + removal + " kind=" + kind
                                + " outcome=" + outcome + " exists=" + exists);
                        assertEquals(!removal, exists);
                        // Numeric/null getString behavior here belongs to desktop org.json, not Android's implementation.
                        String expected = kind < 2 ? "SUCCESS" : kind == 5
                                ? "com.supertokens.session.SuperTokensGeneralError" : "java.lang.IllegalStateException";
                        assertEquals(expected, outcome);
                    }
                }
            }
        } finally { CookieHandler.setDefault(previous); }
    }
}
