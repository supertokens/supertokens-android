package com.supertokens.session;

import org.junit.Test;
import static org.junit.Assert.*;

public class RefreshRetryAdmissionTest extends SessionTestFixture {
    @Test public void admissionAllowsAccessPromotionButRejectsRefreshRotation() throws Exception {
        SessionSnapshot original = install("admission", "before");
        SessionRequestTag tag = SessionState.capture();
        server.enqueue(promotion(tokens("admission", "promotion")));
        assertEquals(200, request(false, null, "/profile"));
        SessionTransport.requireCurrentRefresh(tag);

        server.enqueue(rotation(tokens("admission", "rotated")));
        assertTrue(SuperTokens.resolveAccessToken(context, original.getOwner(), true, null).isSuccess());
        assertSame(original.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
        try {
            SessionTransport.requireCurrentRefresh(tag);
            fail("rotated refresh lineage must not be admitted");
        } catch (StaleSessionException expected) { }
        int requests = server.getRequestCount();
        try {
            SuperTokensHttpURLConnection.refresh(context, tag.configuration, tag.getSnapshot(), true);
            fail("obsolete refresh must not dispatch");
        } catch (StaleSessionException expected) { }
        assertEquals(requests, server.getRequestCount());
    }
}
