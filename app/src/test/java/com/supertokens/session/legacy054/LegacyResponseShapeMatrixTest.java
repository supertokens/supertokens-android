package com.supertokens.session.legacy054;

import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.SessionTestFixture;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.Utils;
import com.supertokens.session.SessionRequestTag;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import okhttp3.mockwebserver.MockResponse;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LegacyResponseShapeMatrixTest extends SessionTestFixture {
    @Override protected boolean initializeOnSetup() { return false; }

    private static String front() {
        return Base64.getEncoder().encodeToString(
                "{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{}}".getBytes(StandardCharsets.UTF_8));
    }

    private static MockResponse login() {
        return new MockResponse().addHeader("front-token", front())
                .addHeader("st-access-token", "access").addHeader("st-refresh-token", "refresh");
    }

    private void compare(boolean urlConnection, boolean removal) throws Exception {
        String expected;
        try (Legacy054Harness old = new Legacy054Harness(context)) {
            old.build(server.url("/").toString(), "header");
            if (removal) {
                server.enqueue(login());
                old.request(server.url("/login").toString());
            }
            server.enqueue(removal ? new MockResponse().addHeader("front-token", "remove")
                    .addHeader("st-access-token", "ignored") : new MockResponse().addHeader("front-token", front()));
            if (urlConnection) old.invoke("requestURLConnection", new Class<?>[]{String.class}, server.url("/shape").toString());
            else old.request(server.url("/shape").toString());
            expected = old.invoke("doesSessionExist") + ":" + legacyUser(old);
        }
        context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
        SuperTokens.resetForTests();
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header").build();
        if (removal) {
            server.enqueue(login());
            request(urlConnection, null, "/login");
        }
        server.enqueue(removal ? new MockResponse().addHeader("front-token", "remove")
                .addHeader("st-access-token", "ignored") : new MockResponse().addHeader("front-token", front()));
        request(urlConnection, null, "/shape");
        assertEquals(expected, SuperTokens.doesSessionExist(context) + ":" + candidateUser());
    }

    private String legacyUser(Legacy054Harness old) throws Exception {
        try { return String.valueOf(old.invoke("getUserId")); }
        catch (Exception error) { return "throws " + error.getClass().getName(); }
    }

    private String candidateUser() {
        try { return String.valueOf(SuperTokens.getUserId(context)); }
        catch (Exception error) { return "throws " + error.getClass().getName(); }
    }

    @Test public void r18FrontOnlyOkHttp() throws Exception { compare(false, false); }
    @Test public void r18FrontOnlyURLConnection() throws Exception { compare(true, false); }
    @Test public void r22RemovalWithAccessOkHttp() throws Exception { compare(false, true); }
    @Test public void r22RemovalWithAccessURLConnection() throws Exception { compare(true, true); }

    private MockResponse duplicate(boolean frontHeader) {
        MockResponse response = new MockResponse();
        if (frontHeader) {
            response.addHeader("front-token", front()).addHeader("front-token", "remove");
        } else {
            response.addHeader("front-token", front()).addHeader("st-refresh-token", "refresh")
                    .addHeader("st-access-token", "first").addHeader("st-access-token", "second");
        }
        return response;
    }

    private void duplicateParity(boolean urlConnection, boolean frontHeader) throws Exception {
        Object exists;
        Object token;
        try (Legacy054Harness old = new Legacy054Harness(context)) {
            old.build(server.url("/").toString(), "header");
            if (frontHeader) {
                server.enqueue(login());
                if (urlConnection) old.invoke("requestURLConnection", new Class<?>[]{String.class}, server.url("/login").toString());
                else old.request(server.url("/login").toString());
            }
            server.enqueue(duplicate(frontHeader));
            if (urlConnection) old.invoke("requestURLConnection", new Class<?>[]{String.class}, server.url("/shape").toString());
            else old.request(server.url("/shape").toString());
            exists = old.invoke("doesSessionExist");
            token = old.invoke("getTokenForHeaderAuth");
        }
        context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
        SuperTokens.resetForTests();
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header").build();
        if (frontHeader) {
            server.enqueue(login());
            request(urlConnection, null, "/login");
        }
        server.enqueue(duplicate(frontHeader));
        request(urlConnection, null, "/shape");
        assertEquals("session", exists, SuperTokens.doesSessionExist(context));
        assertEquals("header token", token, Utils.getTokenForHeaderAuth(Utils.TokenType.ACCESS, context));
    }

    private void exposedCookieUtility(boolean urlConnection) throws Exception {
        Object expected;
        try (Legacy054Harness old = new Legacy054Harness(context)) {
            old.invoke("useCookies");
            old.build(server.url("/").toString(), "cookie");
            server.enqueue(new MockResponse().addHeader("front-token", front()).addHeader("st-access-token", "exposed"));
            if (urlConnection) old.invoke("requestURLConnection", new Class<?>[]{String.class}, server.url("/shape").toString());
            else old.request(server.url("/shape").toString());
            assertEquals(Boolean.TRUE, old.invoke("doesSessionExist"));
            expected = old.invoke("getTokenForHeaderAuth");
            assertEquals("exposed", expected);
        }
        context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
        SuperTokens.resetForTests();
        java.net.CookieHandler.setDefault(new java.net.CookieManager());
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("cookie").build();
        server.enqueue(new MockResponse().addHeader("front-token", front()).addHeader("st-access-token", "exposed"));
        request(urlConnection, null, "/shape");
        assertTrue(SuperTokens.doesSessionExist(context));
        assertEquals(expected, Utils.getTokenForHeaderAuth(Utils.TokenType.ACCESS, context));
    }

    private void strictDuplicate(boolean urlConnection, boolean frontHeader) throws Exception {
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header").build();
        server.enqueue(login());
        request(urlConnection, null, "/login");
        assertTrue(SuperTokens.doesSessionExist(context));
        SessionRequestTag tag = SuperTokens.captureRequestTag(context);
        server.enqueue(duplicate(frontHeader));
        request(urlConnection, tag, "/shape");
        assertTrue(SuperTokens.doesSessionExist(context));
        assertEquals("access", Utils.getTokenForHeaderAuth(Utils.TokenType.ACCESS, context));
    }

    @Test public void duplicateFrontOkHttp() throws Exception { duplicateParity(false, true); }
    @Test public void duplicateFrontURLConnection() throws Exception { duplicateParity(true, true); }
    @Test public void duplicateAccessOkHttp() throws Exception { duplicateParity(false, false); }
    @Test public void duplicateAccessURLConnection() throws Exception { duplicateParity(true, false); }
    @Test public void r23ExposedCookieUtilityOkHttp() throws Exception { exposedCookieUtility(false); }
    @Test public void r23ExposedCookieUtilityURLConnection() throws Exception { exposedCookieUtility(true); }
    @Test public void strictDuplicateFrontOkHttp() throws Exception { strictDuplicate(false, true); }
    @Test public void strictDuplicateFrontURLConnection() throws Exception { strictDuplicate(true, true); }
    @Test public void strictDuplicateAccessOkHttp() throws Exception { strictDuplicate(false, false); }
    @Test public void strictDuplicateAccessURLConnection() throws Exception { strictDuplicate(true, false); }
}
