package com.supertokens.session;

import java.util.concurrent.TimeUnit;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionConfigurationContractTest extends SessionTestFixture {
    @Test public void crossPortRequestCannotDispatchManagedCredentialsOrPromoteSession() throws Exception {
        SessionSnapshot original = install("configured-origin", "before");
        MockWebServer otherOrigin = new MockWebServer();
        otherOrigin.start();
        try {
            assertNotEquals(server.getPort(), otherOrigin.getPort());
            otherOrigin.enqueue(rotation(tokens("foreign-origin", "response")));
            try (Response response = client.newCall(new Request.Builder().url(otherOrigin.url("/profile")).build()).execute()) {
                assertEquals(200, response.code());
            }
            RecordedRequest wire = otherOrigin.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(wire);
            assertNull(wire.getHeader("Authorization"));
            assertNull(wire.getHeader("anti-csrf"));
            assertNull(wire.getHeader("st-auth-mode"));
            assertSame(original, SuperTokens.getSessionSnapshot(context));
            try {
                SuperTokensHttpURLConnection.newRequest(otherOrigin.url("/profile").url(), connection -> {});
                fail("URLConnection must reject out-of-scope port before dispatch");
            } catch (IllegalAccessException expected) {
                assertTrue(expected.getMessage().contains("cannot be handled"));
            }
            assertEquals(1, otherOrigin.getRequestCount());
            assertEquals(0, server.getRequestCount());
        } finally { otherOrigin.shutdown(); }
    }

    @Test public void transferModeChangesClearAndRetireOwnerAcrossHeaderCookieHeader() throws Exception {
        SessionSnapshot original = install("A", "before");
        SessionRequestTag originalTag = SuperTokens.captureRequestTag(context);
        SuperTokens.setTokenTransferMethod("cookie");
        SessionSnapshot cookie = SuperTokens.getSessionSnapshot(context);
        assertEquals(SessionSnapshot.Status.ABSENT, cookie.getStatus());
        assertNull(cookie.getTokens());
        assertFalse(original.isCurrent());
        assertNotSame(original.getOwner(), cookie.getOwner());
        assertNotEquals(original.getAdoptionId(), cookie.getAdoptionId());
        assertTrue(cookie.getRevision() > original.getRevision());
        SuperTokens.setTokenTransferMethod("header");
        SessionSnapshot restoredConfig = SuperTokens.getSessionSnapshot(context);
        assertEquals(original.getConfigurationId(), restoredConfig.getConfigurationId());
        assertEquals(SessionSnapshot.Status.ABSENT, restoredConfig.getStatus());
        assertFalse(cookie.isCurrent());
        assertFalse(original.isCurrent());
        assertNotSame(original.getOwner(), restoredConfig.getOwner());
        assertNotEquals(cookie.getAdoptionId(), restoredConfig.getAdoptionId());
        assertTrue(restoredConfig.getRevision() > cookie.getRevision());
        assertEquals(SessionCommitResult.Status.STALE_OWNER,
                SuperTokens.installSession(context, original.getOwner(), tokens("A", "late")).getStatus());
        assertEquals(SessionResolution.Status.STALE_OWNER,
                SuperTokens.resolveAccessToken(context, original.getOwner(), true, null).getStatus());
        for (boolean url : new boolean[]{false, true}) {
            try { request(url, originalTag, "/profile"); fail("ABA cannot restore request authority"); }
            catch (StaleSessionException expected) { }
        }
        assertEquals(0, server.getRequestCount());
        SuperTokens.resetForTests();
        new SuperTokens.Builder(context, server.url("/").toString()).build();
        SessionSnapshot hydrated = SuperTokens.getSessionSnapshot(context);
        assertEquals(SessionSnapshot.Status.ABSENT, hydrated.getStatus());
        assertEquals(restoredConfig.getAdoptionId(), hydrated.getAdoptionId());
        assertNull(hydrated.getTokens());
    }
}
