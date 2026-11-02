package com.supertokens.session.legacy054;

import com.supertokens.session.SessionTestFixture;
import com.supertokens.session.Legacy054Harness;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import org.junit.Test;
import static org.junit.Assert.*;

public class JdkHttpOnlyCookieVisibilityTest extends SessionTestFixture {
    @Override protected boolean initializeOnSetup() { return false; }

    @Test public void publishedUrlConnectionFailsOnHiddenHttpOnlyCookie() throws Exception {
        CookieManager manager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(manager);
        server.enqueue(new MockResponse().addHeader("Set-Cookie", "secret=value; Path=/; HttpOnly"));
        try (Legacy054Harness published = new Legacy054Harness(context)) {
            published.build(server.url("/").toString(), "cookie");
            try {
                published.invoke("requestURLConnection", new Class<?>[]{String.class}, server.url("/resource").toString());
                fail("Expected the published SDK to parse the hidden cookie header");
            } catch (IllegalArgumentException expected) {
                assertEquals("Empty cookie header string", expected.getMessage());
            }
            assertEquals("value", manager.getCookieStore().getCookies().get(0).getValue());
        }
    }

    @Test public void installedCookieHandlerSeesCookieButURLConnectionHidesItsValue() throws Exception {
        CookieManager manager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(manager);
        server.enqueue(new MockResponse().addHeader("Set-Cookie", "secret=value; Path=/; HttpOnly"));
        HttpURLConnection connection = (HttpURLConnection) new URL(server.url("/").toString()).openConnection();
        try {
            assertEquals(200, connection.getResponseCode());
            List<String> cookies = header(connection.getHeaderFields(), "Set-Cookie");
            assertNotNull(cookies);
            assertTrue(cookies.contains(""));
            assertEquals("value", manager.getCookieStore().getCookies().get(0).getValue());
            try {
                java.net.HttpCookie.parse(cookies.get(0));
                fail("The published SDK parses the hidden value and should fail on the JDK");
            } catch (IllegalArgumentException expected) {
                assertEquals("Empty cookie header string", expected.getMessage());
            }
        } finally {
            connection.disconnect();
        }
    }

    private static List<String> header(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }
}
