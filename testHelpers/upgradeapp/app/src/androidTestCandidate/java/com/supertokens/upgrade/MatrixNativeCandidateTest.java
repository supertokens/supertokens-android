package com.supertokens.upgrade;

import com.supertokens.session.EventHandler;
import com.supertokens.session.SessionOwner;
import com.supertokens.session.SuperTokens;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import static org.junit.Assert.*;

public final class MatrixNativeCandidateTest extends MatrixNativeStage {
    private final List<EventHandler.EventType> events = new ArrayList<EventHandler.EventType>();

    @Override protected void customize(SuperTokens.Builder builder) {
        builder.eventHandler(new EventHandler() {
            @Override public void handleEvent(EventType event) { events.add(event); }
        });
    }

    @Override protected void continueSession() throws Exception {
        checkHandoff();
        managed("/", null);
        SessionOwner owner = SuperTokens.getSessionSnapshot(context).getOwner();
        assertNotNull(owner);
        refresh();
        observe("candidateRefresh", false);
        assertSame(owner, SuperTokens.getSessionSnapshot(context).getOwner());
        managed("/", null);
        events.clear();
        managed("/update-jwt", new JSONObject().put("upgradeClaim", "candidate"));
        assertSame(owner, SuperTokens.getSessionSnapshot(context).getOwner());
        assertEquals("candidate", SuperTokens.getAccessTokenPayloadSecurely(context).optString("upgradeClaim"));
        assertEquals(1, events.size());
        assertEquals(EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED, events.get(0));
        signOut();
    }
}
