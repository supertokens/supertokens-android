package com.example.example;

import com.supertokens.session.EventHandler;
import com.supertokens.session.SuperTokens;
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
public class LegacyEventsLiveTest {
    @Parameterized.Parameters(name = "{0}, urlConnection={1}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][]{{"header", false}, {"header", true}, {"cookie", false}, {"cookie", true}});
    }
    private final String method;
    private final boolean urlConnection;
    private LiveFixture fixture;
    public LegacyEventsLiveTest(String method, boolean urlConnection) { this.method = method; this.urlConnection = urlConnection; }
    @BeforeClass public static void start() { com.example.TestUtils.beforeAll(); }
    @AfterClass public static void stop() { com.example.TestUtils.afterAll(); }
    @Before public void setup() throws Exception { fixture = new LiveFixture(method, urlConnection); }
    @After public void cleanup() { if (fixture != null) fixture.close(); }

    @Test public void loginPayloadUpdateRefreshAndSignOut() throws Exception {
        String[] refreshedPayload = new String[1];
        assertTrue(new SuperTokens.Builder(fixture.context, Constants.apiDomain).tokenTransferMethod(method)
                .eventHandler(event -> {
                    fixture.events.add(event);
                    if (event == EventHandler.EventType.REFRESH_SESSION) {
                        refreshedPayload[0] = SuperTokens.getSessionSnapshot(fixture.context).getAccessTokenPayload().toString();
                    }
                }).reconfigure().isSuccess());
        fixture.events.clear();
        assertEquals(200, fixture.login("legacy-events"));
        assertEquals(Collections.singletonList(EventHandler.EventType.SESSION_CREATED), fixture.events);
        fixture.events.clear();
        assertEquals(200, fixture.request("/update-jwt", "{\"liveEventMarker\":\"updated\"}"));
        assertEquals(Collections.singletonList(EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED), fixture.events);
        fixture.events.clear();
        Thread.sleep(5000);
        String beforePayload = SuperTokens.getSessionSnapshot(fixture.context).getAccessTokenPayload().toString();
        assertEquals(200, fixture.request("/", null));
        String afterPayload = SuperTokens.getSessionSnapshot(fixture.context).getAccessTokenPayload().toString();
        assertNotNull("Refresh callback must observe the intermediate payload", refreshedPayload[0]);
        java.util.List<EventHandler.EventType> expected = new java.util.ArrayList<>();
        if (!beforePayload.equals(refreshedPayload[0])) expected.add(EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED);
        expected.add(EventHandler.EventType.REFRESH_SESSION);
        if (!refreshedPayload[0].equals(afterPayload)) expected.add(EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED);
        assertEquals("0.5.4 oracle: each response emits its own payload update", expected, fixture.events);
        fixture.events.clear();
        SuperTokens.signOut(fixture.context);
        assertEquals(Collections.singletonList(EventHandler.EventType.SIGN_OUT), fixture.events);
    }

    @Test public void revokedSessionEmitsUnauthorisedOnRefresh() throws Exception {
        assertEquals(200, fixture.login("legacy-events-revoked"));
        assertEquals(200, fixture.request("/revokeAll", "{}"));
        fixture.events.clear();
        assertFalse(SuperTokens.attemptRefreshingSession(fixture.context));
        assertFalse(SuperTokens.doesSessionExist(fixture.context));
        assertEquals(Collections.singletonList(EventHandler.EventType.UNAUTHORISED), fixture.events);
    }
}
