package com.supertokens.session;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionIdentityVersionTest extends SessionTestFixture {
    @Test public void legacyApplicationClaimsAreNotIdentity() throws Exception {
        JSONObject payload = new JSONObject().put("sessionHandle", "real-handle")
                .put("userId", "real-user").put("expiryTime", 4102444800000L);
        JSONObject custom = new JSONObject().put("sessionHandle", new JSONObject())
                .put("userId", "application-user").put("tId", 123);
        JSONObject liveShape = new JSONObject().put("sessionHandle", "application-handle")
                .put("userId", "application-user").put("sub", "application-sub")
                .put("tId", "application-tenant").put("expiryTime", 1).put("exp", 1);
        for (String version : new String[]{"1", "2"}) {
            for (JSONObject userData : new JSONObject[]{custom, liveShape}) {
                SessionIdentity identity = SessionIdentity.parse(tuple(new JSONObject().put("version", version),
                        payload, userData), 0);
                assertEquals("real-handle", identity.sessionHandle);
                assertEquals("real-user", identity.userId);
                assertEquals("public", identity.tenantId);
                assertEquals(4102444800000L, identity.accessTokenExpiry);
            }
        }
    }

    @Test public void absentVersionUsesModernClaimsAndRequiresKeyId() throws Exception {
        JSONObject header = new JSONObject().put("alg", "RS256").put("typ", "JWT").put("kid", "d-key");
        JSONObject modern = new JSONObject().put("sessionHandle", "modern-handle")
                .put("sub", "real-user").put("exp", 4102444800L).put("tId", "tenant");
        assertEquals("tenant", SessionIdentity.parse(tuple(header, modern, modern), 0).tenantId);
        header.remove("kid");
        reject(tuple(header, modern, modern));
        header.put("kid", "d-key");
        JSONObject legacy = new JSONObject().put("sessionHandle", "legacy-handle")
                .put("userId", "real-user").put("expiryTime", 4102444800000L);
        reject(tuple(header, legacy, new JSONObject()));
    }

    private static void reject(SessionTokens tokens) {
        try { SessionIdentity.parse(tokens, 0); fail("Invalid version/claims accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    private static SessionTokens tuple(JSONObject header, JSONObject payload, JSONObject userData) throws Exception {
        String access = encode(header) + "." + encode(payload) + ".c2ln";
        JSONObject front = new JSONObject().put("uid", "real-user").put("ate", 4102444800000L).put("up", userData);
        return new SessionTokens(access, "refresh", encode(front), null);
    }

    private static String encode(JSONObject value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8));
    }
}
