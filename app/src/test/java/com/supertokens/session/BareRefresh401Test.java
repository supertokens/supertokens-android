package com.supertokens.session;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import org.junit.Test;
import static org.junit.Assert.*;

public class BareRefresh401Test extends SessionTestFixture {
    private final List<EventHandler.EventType> events = new CopyOnWriteArrayList<>();

    @Override protected void initializeSession() throws Exception {
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                .eventHandler(events::add).build();
    }

    @Test public void d8Bare401RetainsSessionAndOnlyCompatibleEmitsUnauthorised() throws Exception {
        for (boolean url : new boolean[]{false, true}) {
            for (boolean tagged : new boolean[]{false, true}) {
                SessionSnapshot before = install("bare-" + url + tagged, "before");
                events.clear();
                server.enqueue(new MockResponse().setResponseCode(401));
                server.enqueue(new MockResponse().setResponseCode(401).setBody("missing refresh"));
                try {
                    request(url, tagged ? SuperTokens.captureRequestTag(context) : null, "/protected");
                    fail("D8: bare refresh 401 must fail");
                } catch (IOException expected) {
                    assertFalse(expected instanceof StaleSessionException);
                }
                assertSame("D8: bare refresh 401 retains session", before, SuperTokens.getSessionSnapshot(context));
                assertEquals("D8: only compatible flights emit UNAUTHORISED", tagged ? 0 : 1, events.size());
                if (!tagged) assertEquals(EventHandler.EventType.UNAUTHORISED, events.get(0));
            }
        }
    }

    @Test public void d8BackendDistinguishesMissingAndRejectedRefreshCredentials() throws Exception {
        server.setDispatcher(new FakeBackend(tokens("fixture", "initial")));
        OkHttpClient raw = new OkHttpClient();
        try {
            Request.Builder request = new Request.Builder().url(server.url("/auth/session/refresh"));
            try (Response missing = raw.newCall(request.build()).execute()) {
                assertEquals(401, missing.code());
                assertNull("D8: missing credentials do not prove removal", missing.header("front-token"));
            }
            try (Response rejected = raw.newCall(request.header("Authorization", "Bearer invalid").build()).execute()) {
                assertEquals(401, rejected.code());
                assertEquals("D8: rejected credentials carry explicit removal", "remove", rejected.header("front-token"));
            }
        } finally {
            raw.connectionPool().evictAll();
            raw.dispatcher().executorService().shutdownNow();
        }
    }
}
