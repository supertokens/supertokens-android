package com.example.example;

import android.content.Context;
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder;
import com.supertokens.session.EventHandler;
import com.supertokens.session.SessionSnapshot;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensInterceptor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import static org.mockito.Mockito.*;

/** Opt-in, existing-backend tests. No /beforeeach, /startst, /after or global reset. */
public class BackendMutationCoverageTest {
    private Context context;
    private String base;
    private String fixtureId;
    private OkHttpClient raw;
    private OkHttpClient managed;
    private JSONObject capabilities;
    private final List<EventHandler.EventType> events = new ArrayList<>();
    private final AtomicInteger refreshWires = new AtomicInteger();

    @Before public void setup() throws Exception {
        base = System.getProperty("m6BackendUrl");
        assumeNotNull(base);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        raw = new OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build();
        try (Response response = raw.newCall(new Request.Builder().url(base + "/test/capabilities").build()).execute()) {
            assertEquals(200, response.code());
            capabilities = new JSONObject(response.body().string());
        }
        assertTrue("Backend must be reachable/compatible, not silently skipped", capabilities.getBoolean("compatible"));
        assumeTrue("Real mapped-owner fixture unsupported", capabilities.getBoolean("mappedGoogleSession"));
        context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(new SPMockBuilder().createSharedPreferences());
        SuperTokens.resetForTests();
        new SuperTokens.Builder(context, base).tokenTransferMethod("header").eventHandler(events::add).build();
        managed = raw.newBuilder().addInterceptor(new SuperTokensInterceptor()).addNetworkInterceptor(chain -> {
            if (chain.request().url().encodedPath().equals("/auth/session/refresh")) refreshWires.incrementAndGet();
            return chain.proceed(chain.request());
        }).build();
    }

    private Request post(String path, JSONObject input) {
        return new Request.Builder().url(base + path).header("st-auth-mode", "header").header("rid", "session")
                .post(RequestBody.create(MediaType.parse("application/json"), input.toString())).build();
    }

    private JSONObject login(boolean tenant) throws Exception {
        JSONObject input = new JSONObject().put("email", "rank8-" + UUID.randomUUID() + "@example.com").put("recipe", "google");
        try (Response response = managed.newCall(post(tenant ? "/login-tenant" : "/test/email-change-owner", input)).execute()) {
            assertEquals("Fixture login must work once advertised", 200, response.code());
            JSONObject body = new JSONObject(response.body().string());
            fixtureId = body.getString("fixtureId");
            assertEquals(SessionSnapshot.Status.AVAILABLE, SuperTokens.getSessionSnapshot(context).getStatus());
            events.clear();
            return body;
        }
    }

    private void refreshReactively() throws Exception {
        // This real harness route supplies the access rejection; Core decides the refresh outcome.
        // It avoids waiting for expiry or claiming that stateless JWT validation checks revocation immediately.
        try (Response response = managed.newCall(new Request.Builder().url(base + "/throw-401").build()).execute()) {
            assertEquals(401, response.code());
        }
    }

    @Test public void payloadUpdateRetainsOwnerAndEmitsOnePayloadEvent() throws Exception {
        login(false);
        SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
        try (Response response = managed.newCall(post("/update-jwt", new JSONObject().put("rank8", "updated"))).execute()) {
            assertEquals(200, response.code());
        }
        SessionSnapshot after = SuperTokens.getSessionSnapshot(context);
        assertSame(before.getOwner(), after.getOwner());
        assertEquals("updated", after.getAccessTokenPayload().getString("rank8"));
        assertEquals(java.util.Collections.singletonList(EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED), events);
        assertEquals(0, refreshWires.get());
    }

    @Test public void handlePayloadUpdateBecomesVisibleAfterRefresh() throws Exception {
        login(false);
        SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
        try (Response response = managed.newCall(post("/update-jwt-with-handle", new JSONObject().put("rank8Handle", "updated"))).execute()) {
            assertEquals(200, response.code());
        }
        assertFalse(SuperTokens.getSessionSnapshot(context).getAccessTokenPayload().has("rank8Handle"));
        assertTrue(events.isEmpty());
        assertTrue(SuperTokens.attemptRefreshingSession(context));
        SessionSnapshot after = SuperTokens.getSessionSnapshot(context);
        assertSame(before.getOwner(), after.getOwner());
        assertEquals("updated", after.getAccessTokenPayload().getString("rank8Handle"));
        assertEquals(1, java.util.Collections.frequency(events, EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED));
        assertEquals(1, java.util.Collections.frequency(events, EventHandler.EventType.REFRESH_SESSION));
    }

    @Test public void logoutThenRejectedAccessRefreshClearsOnce() throws Exception { revocation("/logout"); }
    @Test public void revokeAllThenRejectedAccessRefreshClearsOnce() throws Exception { revocation("/revokeAll"); }

    private void revocation(String endpoint) throws Exception {
        login(false);
        SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
        // Out-of-band revocation: the candidate intentionally does not consume removal headers here.
        Request.Builder revoke = post(endpoint, new JSONObject()).newBuilder()
                .header("Authorization", "Bearer " + before.getTokens().getAccessToken());
        if (before.getTokens().getAntiCSRF() != null) revoke.header("anti-csrf", before.getTokens().getAntiCSRF());
        try (Response response = raw.newCall(revoke.build()).execute()) { assertEquals(200, response.code()); }
        assertSame(before, SuperTokens.getSessionSnapshot(context));
        refreshReactively();
        assertEquals(1, refreshWires.get());
        assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        assertFalse(before.isCurrent());
        assertNull(SuperTokens.getAccessToken(context));
        assertEquals(java.util.Collections.singletonList(EventHandler.EventType.UNAUTHORISED), events);
    }

    @Test public void claims403NeitherRefreshesNorClears() throws Exception {
        login(false);
        SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
        try (Response response = managed.newCall(post("/session-claims-error", new JSONObject())).execute()) {
            assertEquals(403, response.code());
            assertTrue(response.body().string().contains("test-claim-failing"));
        }
        assertSame(before, SuperTokens.getSessionSnapshot(context));
        assertTrue(before.isCurrent());
        assertTrue(events.isEmpty());
        assertEquals(0, refreshWires.get());
    }

    @Test public void nonPublicTenantLoginAndRefreshRetainTenantAndOwner() throws Exception {
        assumeTrue("Non-public tenant fixture unsupported", capabilities.optBoolean("nonPublicTenantSession", false));
        JSONObject receipt = login(true);
        SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
        assertNotEquals("public", receipt.getString("tenantId"));
        assertEquals(receipt.getString("tenantId"), before.getTenantId());
        assertTrue(SuperTokens.attemptRefreshingSession(context));
        SessionSnapshot after = SuperTokens.getSessionSnapshot(context);
        assertSame(before.getOwner(), after.getOwner());
        assertEquals(before.getTenantId(), after.getTenantId());
        assertEquals(before.getSessionHandle(), after.getSessionHandle());
        assertEquals(before.getUserId(), after.getUserId());
    }

    @After public void cleanup() throws Exception {
        try {
            if (fixtureId != null) {
                try (Response response = raw.newCall(post("/test/cleanup-owner", new JSONObject().put("fixtureId", fixtureId))).execute()) {
                    assertEquals(200, response.code());
                    assertTrue("Scoped cleanup must be acknowledged", new JSONObject(response.body().string()).getBoolean("cleaned"));
                }
            }
        } finally {
            if (context != null) SuperTokens.resetForTests();
            if (raw != null) { raw.connectionPool().evictAll(); raw.dispatcher().executorService().shutdownNow(); }
        }
    }
}
