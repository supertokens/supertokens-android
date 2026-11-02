package com.example.example;

import com.supertokens.session.EventHandler;
import com.supertokens.session.SessionSnapshot;
import com.supertokens.session.SuperTokens;
import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class RefreshWithoutClearLiveTest {
    @Parameterized.Parameters(name = "urlConnection={0}")
    public static Collection<Object[]> cases() { return Arrays.asList(new Object[][]{{false}, {true}}); }
    private final boolean urlConnection;
    private LiveFixture fixture;
    public RefreshWithoutClearLiveTest(boolean urlConnection) { this.urlConnection = urlConnection; }
    @BeforeClass public static void start() { com.example.TestUtils.beforeAll(); }
    @AfterClass public static void stop() { com.example.TestUtils.afterAll(); }
    @Before public void setup() throws Exception { fixture = new LiveFixture("cookie", urlConnection); }
    @After public void cleanup() { if (fixture != null) fixture.close(); }

    @Test public void missingRefreshCookieDoesNotClearFrontToken() throws Exception {
        assertEquals(200, fixture.login("missing-refresh"));
        SessionSnapshot before = SuperTokens.getSessionSnapshot(fixture.context);
        assertEquals(SessionSnapshot.Status.AVAILABLE, before.getStatus());
        boolean removed = false;
        for (URI uri : new ArrayList<>(fixture.cookies.getCookieStore().getURIs())) {
            for (HttpCookie cookie : new ArrayList<>(fixture.cookies.getCookieStore().getCookies())) {
                if (cookie.getName().equals("sRefreshToken")) {
                    removed |= fixture.cookies.getCookieStore().remove(uri, cookie);
                }
            }
        }
        assertTrue("login must set a refresh cookie", removed);
        fixture.events.clear();
        Thread.sleep(5000);
        try {
            fixture.request("/", null);
            fail("missing refresh cookie must fail the request");
        } catch (IOException expected) {
            // The backend rejects refresh without clearing the session.
        }
        SessionSnapshot after = SuperTokens.getSessionSnapshot(fixture.context);
        assertEquals(SessionSnapshot.Status.AVAILABLE, after.getStatus());
        assertEquals(before.getUserId(), after.getUserId());
        assertEquals(before.getSessionHandle(), after.getSessionHandle());
        assertEquals(Collections.singletonList(EventHandler.EventType.UNAUTHORISED), fixture.events);
    }
}
