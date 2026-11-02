package com.supertokens.session.legacy054;

import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.SessionTestFixture;
import java.net.CookieHandler;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyDriverCookieTest extends SessionTestFixture {
    @Override protected boolean initializeOnSetup() { return false; }

    @Test public void sharesCookiesBetweenTransportsAndRestoresHandler() throws Exception {
        CookieHandler original = CookieHandler.getDefault();
        server.enqueue(new MockResponse().addHeader("Set-Cookie", "shared=one; Path=/"));
        server.enqueue(new MockResponse().addHeader("Set-Cookie", "fromurl=two; Path=/"));
        server.enqueue(new MockResponse());
        try (Legacy054Harness old = new Legacy054Harness(context)) {
            old.invoke("useCookies");
            old.build(server.url("/").toString(), "cookie");
            assertEquals(200, old.request(server.url("/one").toString()).get("status"));
            assertEquals(200, old.invoke("requestURLConnection", new Class<?>[]{String.class}, server.url("/two").toString()));
            assertEquals(200, old.request(server.url("/three").toString()).get("status"));
            RecordedRequest first = server.takeRequest();
            RecordedRequest second = server.takeRequest();
            RecordedRequest third = server.takeRequest();
            assertNull(first.getHeader("Cookie"));
            assertTrue(String.valueOf(second.getHeader("Cookie")).contains("shared=one"));
            assertTrue(String.valueOf(third.getHeader("Cookie")).contains("shared=one"));
            assertTrue(String.valueOf(third.getHeader("Cookie")).contains("fromurl=two"));
        }
        assertSame(original, CookieHandler.getDefault());
    }

    @Test public void respectsPathSecureAndDeletionAcrossTransports() throws Exception {
        server.enqueue(new MockResponse().addHeader("Set-Cookie", "scoped=one; Path=/scope")
                .addHeader("Set-Cookie", "secure=two; Path=/; Secure"));
        server.enqueue(new MockResponse());
        server.enqueue(new MockResponse().addHeader("Set-Cookie", "scoped=gone; Path=/scope; Max-Age=0"));
        server.enqueue(new MockResponse());
        try (Legacy054Harness old = new Legacy054Harness(context)) {
            old.invoke("useCookies");
            old.build(server.url("/").toString(), "cookie");
            old.request(server.url("/scope/seed").toString());
            old.invoke("requestURLConnection", new Class<?>[]{String.class}, server.url("/outside").toString());
            old.invoke("requestURLConnection", new Class<?>[]{String.class}, server.url("/scope/delete").toString());
            old.request(server.url("/scope/after").toString());
            server.takeRequest();
            assertNull(server.takeRequest().getHeader("Cookie"));
            String scoped = server.takeRequest().getHeader("Cookie");
            assertTrue(String.valueOf(scoped).contains("scoped=one"));
            assertFalse(String.valueOf(scoped).contains("secure=two"));
            assertNull(server.takeRequest().getHeader("Cookie"));
        }
    }
}
