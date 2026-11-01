package com.supertokens.session;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import okhttp3.mockwebserver.MockResponse;
import org.junit.Test;
import static org.junit.Assert.*;

public class SignOutBodyParityTest extends SessionTestFixture {
    @Test public void headerDispatchReentrancyPreservesReplacementAndReceivedBodyErrors() throws Exception {
        for (boolean reconfigure : new boolean[]{false, true}) for (String body : new String[]{"{\"status\":\"OK\"}", "<html>invalid</html>"}) {
            java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<SessionSnapshot> replacement = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();
            assertTrue(new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header")
                    .eventHandler(event -> {
                        if (event != EventHandler.EventType.SIGN_OUT || !armed.compareAndSet(true, false)) return;
                        callbacks.incrementAndGet();
                        assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
                        try {
                            if (reconfigure) assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                                    .tokenTransferMethod("header").reconfigure().isSuccess());
                            replacement.set(install("replacement", "listener"));
                        } catch (Exception error) { throw new AssertionError(error); }
                    }).reconfigure().isSuccess());
            install("original", "before");
            armed.set(true);
            server.enqueue(new MockResponse().addHeader("front-token", "remove").setBody(body));
            int requests = server.getRequestCount();
            try {
                SuperTokens.signOut(context);
                assertEquals("{\"status\":\"OK\"}", body);
            } catch (IllegalStateException expected) {
                assertEquals("<html>invalid</html>", body);
            }
            assertEquals(1, callbacks.get());
            assertNotNull(replacement.get());
            assertSame(replacement.get(), SuperTokens.getSessionSnapshot(context));
            assertEquals(requests + 1, server.getRequestCount());
        }
    }

    @Test public void bodyErrorsAndRemovalPostStateMatchPublishedSdkInHeaderAndCookieModes() throws Exception {
        String[] bodies = {"{\"status\":\"OK\"}", "{\"status\":\"UNRECOGNIZED\"}", "{}", "<html>oops</html>", "",
                "{\"status\":\"GENERAL_ERROR\",\"message\":\"failure\"}", "{\"status\":42}", "{\"status\":null}"};
        for (boolean cookie : new boolean[]{false, true}) for (boolean url : new boolean[]{false, true}) {
            for (boolean removal : new boolean[]{false, true}) for (int kind = 0; kind < bodies.length; kind++) {
                CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
                assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                        .tokenTransferMethod(cookie ? "cookie" : "header").reconfigure().isSuccess());
                SessionTokens tokens = tokens("signout-body", "before");
                server.enqueue(cookie ? promotion(tokens) : rotation(tokens));
                assertEquals(200, request(url, null, "/login"));
                SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
                MockResponse response = new MockResponse().setBody(bodies[kind]);
                if (removal) response.addHeader("front-token", "remove");
                server.enqueue(response);
                int requests = server.getRequestCount();
                String outcome = "SUCCESS";
                try { SuperTokens.signOut(context); }
                catch (Exception error) { outcome = error.getClass().getName(); }
                // Numeric/null expectations characterize desktop org.json; device parity uses Android's getString.
                assertEquals(kind < 2 ? "SUCCESS" : kind == 5 ? SuperTokensGeneralError.class.getName()
                        : IllegalStateException.class.getName(), outcome);
                assertEquals(requests + 1, server.getRequestCount());
                if (removal) assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
                else assertSame(before, SuperTokens.getSessionSnapshot(context));
            }
        }
    }
}
