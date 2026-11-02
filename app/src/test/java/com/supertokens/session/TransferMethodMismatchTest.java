package com.supertokens.session;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class TransferMethodMismatchTest extends SessionTestFixture {
    @Test public void partialResponsesFollowShapeAndPreserveSameIdentityPromotion() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                    .tokenTransferMethod("header").reconfigure().isSuccess());
            SessionTokens exposed = tokens("exposed", "login");
            server.enqueue(promotion(exposed));
            assertEquals(200, request(url, null, "/login"));
            assertNull(SessionState.snapshot().getTokens());
            assertEquals(exposed.getAccessToken(), SessionState.snapshot().getInternalTokens().getAccessToken());
            SessionSnapshot header = install("header", "before");
            SessionTokens promoted = tokens("header", "after");
            server.enqueue(promotion(promoted));
            assertEquals(200, request(url, null, "/promotion"));
            assertSame(header.getOwner(), SessionState.snapshot().getOwner());
            assertEquals(header.getTokens().getRefreshToken(), SessionState.snapshot().getTokens().getRefreshToken());
            server.enqueue(new MockResponse().addHeader("front-token", promoted.getFrontToken()));
            assertEquals(200, request(url, null, "/cookie-login"));
            assertSame(header.getOwner(), SessionState.snapshot().getOwner());
            assertEquals(header.getTokens().getRefreshToken(), SessionState.snapshot().getTokens().getRefreshToken());
        }
    }

    @Test public void responseShapeControlsLegacyTransportAcrossRefreshAndSignOut() throws Exception {
        for (boolean url : new boolean[]{false, true}) for (boolean cookieBackend : new boolean[]{false, true}) {
            assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                    .tokenTransferMethod(cookieBackend ? "header" : "cookie").reconfigure().isSuccess());
            CookieHandler.setDefault(new CookieManager());
            client = client.newBuilder().cookieJar(new CookieJar() {
                private final List<Cookie> cookies = new ArrayList<>();
                @Override public void saveFromResponse(HttpUrl url, List<Cookie> received) { cookies.addAll(received); }
                @Override public List<Cookie> loadForRequest(HttpUrl url) { return new ArrayList<>(cookies); }
            }).build();
            SessionTokens login = tokens("shape", "login"), refreshed = tokens("shape", "refreshed");
            server.enqueue(session(login, cookieBackend));
            assertEquals(200, request(url, null, "/login"));
            wire();
            assertEquals(SessionSnapshot.Status.AVAILABLE, SessionState.snapshot().getStatus());
            assertEquals(cookieBackend, SessionState.snapshot().getTokens() == null);
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(session(refreshed, cookieBackend));
            server.enqueue(new MockResponse().setBody("ok"));
            assertEquals(200, request(url, null, "/protected"));
            wire();
            RecordedRequest refresh = wire();
            assertEquals("/auth/session/refresh", refresh.getPath());
            assertEquals(cookieBackend ? null : "Bearer " + login.getRefreshToken(), refresh.getHeader("Authorization"));
            if (cookieBackend) assertTrue(refresh.getHeader("Cookie").contains("sRefreshToken=cookie"));
            wire();
            assertEquals(cookieBackend, SessionState.snapshot().getTokens() == null);
            server.enqueue(new MockResponse().setBody("{\"status\":\"OK\"}").addHeader("front-token", "remove"));
            SuperTokens.signOut(context);
            wire();
            assertEquals(SessionSnapshot.Status.ABSENT, SessionState.snapshot().getStatus());
        }
    }

    @Test public void legacyRemovalWinsButTaggedRemovalRemainsStrict() throws Exception {
        for (boolean url : new boolean[]{false, true}) for (String credential : new String[]{"st-access-token", "st-refresh-token", "anti-csrf"}) {
            SessionSnapshot before = install("removal", "before");
            server.enqueue(new MockResponse().addHeader("front-token", "remove").addHeader(credential, "value"));
            assertEquals(200, request(url, SuperTokens.captureRequestTag(context), "/strict"));
            assertSame(before, SessionState.snapshot());
            server.enqueue(new MockResponse().addHeader("front-token", "remove").addHeader(credential, "value"));
            assertEquals(200, request(url, null, "/legacy"));
            assertEquals(SessionSnapshot.Status.ABSENT, SessionState.snapshot().getStatus());
        }
    }

    private MockResponse session(SessionTokens tokens, boolean cookie) {
        MockResponse response = new MockResponse().addHeader("front-token", tokens.getFrontToken())
                .addHeader("anti-csrf", tokens.getAntiCSRF());
        return cookie ? response.addHeader("Set-Cookie", "sRefreshToken=cookie; Path=/")
                : response.addHeader("st-access-token", tokens.getAccessToken()).addHeader("st-refresh-token", tokens.getRefreshToken());
    }

    private RecordedRequest wire() throws Exception {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        return request;
    }
}
