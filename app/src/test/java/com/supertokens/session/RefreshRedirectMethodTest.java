package com.supertokens.session;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;
import static org.junit.Assert.*;

public class RefreshRedirectMethodTest extends SessionTestFixture {
    @Test public void followsRedirectMethodsForBothRefreshModes() throws Exception {
        SessionSnapshot snapshot = install("session", "before");
        for (boolean legacy : new boolean[]{true, false}) {
            for (int code : new int[]{301, 302, 303, 307, 308}) {
                server.enqueue(new MockResponse().setResponseCode(code).addHeader("Location", "/redirected"));
                server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", "/last"));
                server.enqueue(new MockResponse());
                assertEquals(200, SuperTokensHttpURLConnection.refresh(context,
                        SessionState.capture().configuration, snapshot, legacy).code);
                assertEquals("POST", server.takeRequest().getMethod());
                String expected = code == 307 || code == 308 ? "POST" : "GET";
                assertEquals("status " + code, expected, server.takeRequest().getMethod());
                assertEquals("subsequent 307 after " + code, expected, server.takeRequest().getMethod());
            }
        }
    }

    @Test public void getRedirectStillRejectsForeignOrigin() throws Exception {
        SessionSnapshot snapshot = install("session", "before");
        try (MockWebServer foreign = new MockWebServer()) {
            foreign.start();
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/redirected"));
            server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", foreign.url("/foreign")));
            try {
                SuperTokensHttpURLConnection.refresh(context, SessionState.capture().configuration, snapshot, false);
                fail("foreign redirect accepted");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("outside captured session scope"));
            }
            assertEquals("POST", server.takeRequest().getMethod());
            assertEquals("GET", server.takeRequest().getMethod());
            assertEquals(0, foreign.getRequestCount());
        }
    }

    @Test public void rejectsMoreThanTwentyRedirects() throws Exception {
        SessionSnapshot snapshot = install("session", "before");
        for (int i = 0; i < 21; i++) {
            server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", "/again"));
        }
        try {
            SuperTokensHttpURLConnection.refresh(context, SessionState.capture().configuration, snapshot, true);
            fail("redirect limit not enforced");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("Too many refresh redirects"));
        }
        assertEquals(21, server.getRequestCount());
    }
}
