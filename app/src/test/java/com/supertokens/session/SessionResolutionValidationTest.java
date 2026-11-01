package com.supertokens.session;

import okhttp3.mockwebserver.MockResponse;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionResolutionValidationTest extends SessionTestFixture {
    private static final long FUTURE = 4102444800000L;

    private SessionTokens invalidAccess(int kind) throws Exception {
        SessionTokens valid = tokens("validation", "user", FUTURE, FUTURE, 5);
        if (kind == 0) return tokens("validation", "user", 1000, FUTURE, 5);
        String access = kind == 1 ? "malformed.jwt.token"
                : tokens("validation", "other-user", FUTURE, FUTURE, 5).getAccessToken();
        return new SessionTokens(access, valid.getRefreshToken(), valid.getFrontToken(), valid.getAntiCSRF());
    }

    private SessionSnapshot admit(boolean url, SessionTokens tokens, boolean cookie) throws Exception {
        assertTrue(SuperTokens.clearSession(context, SuperTokens.getSessionSnapshot(context).getOwner()).isSuccess());
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .tokenTransferMethod(cookie ? "cookie" : "header").reconfigure().isSuccess());
        MockResponse response = new MockResponse().setBody("ok").addHeader("front-token", tokens.getFrontToken());
        if (tokens.getAccessToken() != null) response.addHeader("st-access-token", tokens.getAccessToken());
        if (!cookie) response.addHeader("st-refresh-token", tokens.getRefreshToken());
        server.enqueue(response);
        assertEquals(200, request(url, null, "/login"));
        SessionSnapshot snapshot = SuperTokens.getSessionSnapshot(context);
        assertEquals(SessionSnapshot.Status.AVAILABLE, snapshot.getStatus());
        return snapshot;
    }

    private void rejected(SessionSnapshot snapshot, SessionResolution resolution) {
        assertEquals(SessionResolution.Status.REFRESH_FAILED, resolution.getStatus());
        assertNotNull(resolution.getError());
        assertNull(resolution.getAccessToken());
        assertSame(snapshot, resolution.getSnapshot());
        assertSame(snapshot, SuperTokens.getSessionSnapshot(context));
        assertSame(snapshot.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
    }

    @Test public void explicitFastPathRejectsLegacyAccessWithoutRefreshingOrChangingSession() throws Exception {
        for (boolean url : new boolean[]{false, true}) for (int kind = 0; kind < 3; kind++) {
            SessionTokens tokens = invalidAccess(kind);
            SessionSnapshot snapshot = admit(url, tokens, false);
            int requests = server.getRequestCount();
            assertEquals(tokens.getAccessToken(), SuperTokens.getAccessToken(context));
            rejected(snapshot, SuperTokens.resolveAccessToken(context, snapshot.getOwner(), false, null));
            assertEquals(requests, server.getRequestCount());
        }
    }

    @Test public void acquisitionRevisionRecheckValidatesStrictCallerWithoutExecutingTransport() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            SessionSnapshot snapshot = admit(false, invalidAccess(kind), false);
            int requests = server.getRequestCount();
            RefreshCoordinator.Transport forbidden = (context, configuration, captured) -> {
                throw new AssertionError("Validation must not issue a refresh");
            };
            // Force bypasses the first fast path; an older entry revision deterministically takes acquisition's recheck.
            long entryRevision = snapshot.getRefreshTokenRevision() - 1;
            rejected(snapshot, RefreshCoordinator.resolve(context, snapshot.getOwner(), true, null,
                    forbidden, entryRevision, false));
            SessionResolution legacy = RefreshCoordinator.resolve(context, snapshot.getOwner(), true, null,
                    forbidden, entryRevision, true);
            assertTrue(legacy.isSuccess());
            assertSame(snapshot, legacy.getSnapshot());
            assertEquals(requests, server.getRequestCount());
        }
    }

    @Test public void strictCookieResolutionKeepsIncompleteExportsNullAndValidatesExposedAccess() throws Exception {
        java.net.CookieHandler.setDefault(new java.net.CookieManager());
        for (boolean url : new boolean[]{false, true}) for (int kind = -2; kind < 3; kind++) {
            SessionTokens tokens = kind < 0 ? tokens("validation", "user", FUTURE, FUTURE, 5) : invalidAccess(kind);
            if (kind == -2) tokens = new SessionTokens(null, null, tokens.getFrontToken(), tokens.getAntiCSRF());
            SessionSnapshot snapshot = admit(url, tokens, true);
            int requests = server.getRequestCount();
            assertNull(snapshot.getTokens());
            assertEquals(tokens.getAccessToken(), SuperTokens.getAccessToken(context));
            SessionResolution result = SuperTokens.resolveAccessToken(context, snapshot.getOwner(), false, null);
            if (kind < 0) {
                assertTrue(result.isSuccess());
                assertNull(result.getAccessToken());
                assertSame(snapshot, result.getSnapshot());
            } else rejected(snapshot, result);
            assertSame(snapshot, SuperTokens.getSessionSnapshot(context));
            assertEquals(requests, server.getRequestCount());
        }
    }
}
