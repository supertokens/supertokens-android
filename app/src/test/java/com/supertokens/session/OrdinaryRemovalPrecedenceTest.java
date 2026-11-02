package com.supertokens.session;

import okhttp3.mockwebserver.MockResponse;
import org.junit.Test;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class OrdinaryRemovalPrecedenceTest extends SessionTestFixture {
    @Test public void untaggedRemovalWinsOverCredentialsOnBothTransports() throws Exception {
        for (boolean urlConnection : new boolean[] { false, true }) {
            for (String header : new String[] { "st-access-token", "st-refresh-token", "anti-csrf" }) {
                install("A", "before");
                server.enqueue(new MockResponse().addHeader("front-token", "remove")
                        .addHeader(header, "superseded"));
                assertEquals(200, request(urlConnection, null, "/ordinary"));
                assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
            }
        }
    }

    @Test public void taggedRemovalWithCredentialsRemainsInvalid() throws Exception {
        for (boolean urlConnection : new boolean[] { false, true }) {
            for (String header : new String[] { "st-access-token", "st-refresh-token", "anti-csrf" }) {
                SessionSnapshot before = install("A", "before");
                SessionRequestTag tag = SuperTokens.captureRequestTag(context);
                server.enqueue(new MockResponse().addHeader("front-token", "remove")
                        .addHeader(header, "superseded"));
                assertEquals(200, request(urlConnection, tag, "/ordinary"));
                assertSame(before, SuperTokens.getSessionSnapshot(context));
                Map<String, List<String>> headers = new HashMap<>();
                headers.put("front-token", Collections.singletonList("remove"));
                headers.put(header, Collections.singletonList("superseded"));
                assertEquals(SessionCommitResult.Status.INVALID_SESSION,
                        SessionState.applyOrdinaryResponse(context, tag,
                                new SessionResponse(200, "", headers)).getStatus());
            }
        }
    }
}
