package com.supertokens.session;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class SignOutHeaderParityTest extends SessionTestFixture {
    @Test public void providerOverridesAuthorizationAndRidIncludingCaseVariants() throws Exception {
        java.net.CookieHandler.setDefault(new java.net.CookieManager());
        for (boolean cookie : new boolean[]{false, true}) for (boolean url : new boolean[]{false, true})
                for (boolean duplicates : new boolean[]{false, true}) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Basic custom");
            headers.put("rid", "custom-recipe");
            if (duplicates) {
                headers.put("authorization", "Basic second");
                headers.put("RID", "second-recipe");
            }
            assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                    .tokenTransferMethod(cookie ? "cookie" : "header")
                    .customHeaderProvider(type -> headers).reconfigure().isSuccess());
            SessionTokens tokens = tokens("header-parity", "before");
            server.enqueue(cookie ? promotion(tokens) : rotation(tokens));
            assertEquals(200, request(url, null, "/login"));
            server.takeRequest(2, TimeUnit.SECONDS);
            server.enqueue(new MockResponse().setBody("{\"status\":\"OK\"}"));
            SuperTokens.signOut(context);
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(sent);
            assertEquals(Collections.singletonList(duplicates ? "Basic second" : "Basic custom"),
                    sent.getHeaders().values("Authorization"));
            assertEquals(Collections.singletonList(duplicates ? "second-recipe" : "custom-recipe"),
                    sent.getHeaders().values("rid"));
        }
    }

    @Test public void providerHeadersDoNotBypassRedirectScope() throws Exception {
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                .customHeaderProvider(type -> Collections.singletonMap("rid", "custom")).reconfigure().isSuccess());
        SessionSnapshot before = install("A", "before");
        try (MockWebServer foreign = new MockWebServer()) {
            foreign.start();
            server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", foreign.url("/outside")));
            try { SuperTokens.signOut(context); fail(); } catch (IOException expected) { }
            assertEquals(0, foreign.getRequestCount());
            assertSame(before, SuperTokens.getSessionSnapshot(context));
        }
    }

    @Test public void providerReplacingOwnerCannotSendOldCredentials() throws Exception {
        java.util.concurrent.atomic.AtomicReference<SessionSnapshot> replacement = new java.util.concurrent.atomic.AtomicReference<>();
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                .customHeaderProvider(type -> {
                    if (type == CustomHeaderProvider.RequestType.SIGN_OUT) {
                        try { replacement.set(install("B", "replacement")); }
                        catch (Exception error) { throw new AssertionError(error); }
                    }
                    return Collections.singletonMap("rid", "custom");
                }).reconfigure().isSuccess());
        install("A", "before");
        int requests = server.getRequestCount();
        SuperTokens.signOut(context);
        assertNotNull(replacement.get());
        assertSame(replacement.get(), SuperTokens.getSessionSnapshot(context));
        assertEquals(requests, server.getRequestCount());
    }
}
