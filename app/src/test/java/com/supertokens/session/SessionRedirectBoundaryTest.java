package com.supertokens.session;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class SessionRedirectBoundaryTest extends SessionTestFixture {
    @Parameterized.Parameters(name = "urlConnection={0}, tagged={1}, present={2}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][]{
                {false, false, false}, {false, false, true}, {false, true, false}, {false, true, true},
                {true, false, false}, {true, false, true}, {true, true, false}, {true, true, true}});
    }

    private final boolean urlConnection;
    private final boolean tagged;
    private final boolean present;

    public SessionRedirectBoundaryTest(boolean urlConnection, boolean tagged, boolean present) {
        this.urlConnection = urlConnection;
        this.tagged = tagged;
        this.present = present;
    }

    @Test public void crossPortTupleCannotEstablishOrReplaceSession() throws Exception {
        outside(false, rotation(tokens("attacker", "foreign")));
    }

    @Test public void crossPortRemovalCannotClearSession() throws Exception {
        outside(false, new MockResponse().addHeader("front-token", "remove"));
    }

    @Test public void crossPortExpiryCannotTriggerManagedRefresh() throws Exception {
        outside(false, new MockResponse().setResponseCode(401));
    }

    @Test public void sameOriginOutsidePathCannotAdoptSession() throws Exception {
        outside(true, rotation(tokens("attacker", "outside-path")));
    }

    @Test public void sameOriginOutsidePathCannotClearOrRefreshSession() throws Exception {
        outside(true, new MockResponse().setResponseCode(401).addHeader("front-token", "remove"));
    }

    private void outside(boolean sameOrigin, MockResponse response) throws Exception {
        assertTrue(new SuperTokens.Builder(context, server.url("/managed").toString())
                .tokenTransferMethod("header").maxRetryAttemptsForSessionRefresh(0).reconfigure().isSuccess());
        SessionSnapshot before = present ? install("victim", "initial") : SuperTokens.getSessionSnapshot(context);
        SessionRequestTag tag = tagged ? SuperTokens.captureRequestTag(context) : null;
        try (MockWebServer foreign = new MockWebServer()) {
            foreign.start();
            MockWebServer destination = sameOrigin ? server : foreign;
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", destination.url("/outside")));
            destination.enqueue(response);
            try {
                assertEquals(Integer.parseInt(response.getStatus().split(" ")[1]),
                        request(urlConnection, tag, "/managed/start"));
                assertFalse("tagged out-of-scope response must reject", tagged);
            } catch (IOException expected) {
                assertTrue("untagged response must remain readable", tagged);
                assertTrue(expected.getMessage(), expected.getMessage().contains("outside captured session scope"));
            }
            assertEquals(sameOrigin ? 2 : 1, server.getRequestCount());
            assertEquals(sameOrigin ? 0 : 1, foreign.getRequestCount());
            assertEquals("/managed/start", server.takeRequest(1, TimeUnit.SECONDS).getPath());
            assertEquals("/outside", destination.takeRequest(1, TimeUnit.SECONDS).getPath());
            SessionSnapshot after = SuperTokens.getSessionSnapshot(context);
            assertSame(before.getOwner(), after.getOwner());
            assertEquals(before.getRevision(), after.getRevision());
            assertEquals(before.getAdoptionId(), after.getAdoptionId());
            if (tag != null) assertNull(tag.getAcceptedSnapshot());
        }
    }

    @Test public void sameOriginWithinPathAdoptsLegitimateAuthentication() throws Exception {
        assertTrue(new SuperTokens.Builder(context, server.url("/managed").toString())
                .tokenTransferMethod("header").reconfigure().isSuccess());
        if (present) install("victim", "initial");
        SessionRequestTag tag = tagged ? SuperTokens.captureRequestTag(context) : null;
        SessionTokens accepted = tokens("legitimate", "redirect");
        server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", server.url("/managed/callback")));
        server.enqueue(rotation(accepted));
        assertEquals(200, request(urlConnection, tag, "/managed/start"));
        assertEquals(accepted.getAccessToken(), SuperTokens.getSessionSnapshot(context).getTokens().getAccessToken());
        if (tag != null) assertSame(SuperTokens.getSessionSnapshot(context).getOwner(), tag.getAcceptedSnapshot().getOwner());
        assertEquals(2, server.getRequestCount());
    }
}
