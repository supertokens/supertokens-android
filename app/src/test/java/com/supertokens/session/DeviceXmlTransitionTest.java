package com.supertokens.session;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceXmlTransitionTest {
    private static final String FRONT = "supertokens-android-fronttoken-key";
    private static final String ACCESS = "st-storage-item-st-access-token";
    private static final String REFRESH = "st-storage-item-st-refresh-token";

    private Map<String, Object> load(String folder) throws Exception {
        return load(folder, "supertokens-android-shared-preferences");
    }

    private Map<String, Object> load(String folder, String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/upgrade/" + folder + "/" + name + ".xml")) {
            assertNotNull("Missing capture", input);
            return TypedPreferencesXml.read(input);
        }
    }

    private JSONObject front(Map<String, Object> values) throws org.json.JSONException {
        return new JSONObject(new String(Base64.getDecoder().decode((String) values.get(FRONT)), StandardCharsets.UTF_8));
    }

    @Test public void rollbackLoginBReplacesAWhileOldCandidateMetadataRemains() throws Exception {
        Map<String, Object> before = load("candidate/login-b/check-initial");
        Map<String, Object> after = load("0.5.4/login-b/mutate");
        assertFalse(front(before).getString("uid").equals(front(after).getString("uid")));
        assertFalse(front(before).getJSONObject("up").getString("sessionHandle").equals(front(after).getJSONObject("up").getString("sessionHandle")));
        assertTrue(before.get("supertokens-android-session-user").equals(after.get("supertokens-android-session-user")));
        assertFalse(after.get("supertokens-android-session-user").equals(front(after).getString("uid")));
    }

    @Test public void rollbackRefreshPreservesIdentityAndChangesRefreshCredential() throws Exception {
        Map<String, Object> before = load("candidate/refresh/check-initial");
        Map<String, Object> after = load("0.5.4/refresh/mutate");
        assertTrue(front(before).getString("uid").equals(front(after).getString("uid")));
        assertTrue(front(before).getJSONObject("up").getString("sessionHandle").equals(front(after).getJSONObject("up").getString("sessionHandle")));
        assertFalse(before.get(REFRESH).equals(after.get(REFRESH)));
    }

    @Test public void rollbackLogoutRemovesCredentialsButLeavesStaleAvailableMetadata() throws Exception {
        Map<String, Object> after = load("0.5.4/logout/mutate");
        assertFalse(after.containsKey(ACCESS));
        assertFalse(after.containsKey(REFRESH));
        assertFalse(after.containsKey(FRONT));
        assertEquals("available", after.get("supertokens-android-session-status"));
        assertEquals("absent", load("candidate/logout/check-final").get("supertokens-android-session-status"));
    }

    @Test public void cookieLoginBRetainsHeaderCredentialAbsence() throws Exception {
        Map<String, Object> before = load("candidate/cookie/check-initial");
        Map<String, Object> after = load("0.5.4/cookie/mutate");
        assertFalse(front(before).getString("uid").equals(front(after).getString("uid")));
        assertFalse(before.containsKey(ACCESS));
        assertFalse(after.containsKey(ACCESS));
        assertFalse(before.containsKey(REFRESH));
        assertFalse(after.containsKey(REFRESH));
    }

    @Test public void cleanupRetainsSixHistoricalDigestsAndLogoutKeepsEmptyDigests() throws Exception {
        int preserved = 0;
        for (String scenario : new String[]{"login-b", "refresh", "cookie", "logout"}) {
            Map<String, Object> before = load("candidate/" + scenario + "/check-final", "upgrade-receipt");
            Map<String, Object> cleanup = load("candidate/" + scenario + "/cleanup", "upgrade-receipt");
            for (String key : new String[]{"accessDigest", "refreshDigest"}) {
                assertTrue("Cleanup must retain the earlier credential digest", before.get(key).equals(cleanup.get(key)));
                if (scenario.equals("logout")) assertEquals("", cleanup.get(key));
                else {
                    assertFalse("Historical digest erased", ((String) cleanup.get(key)).isEmpty());
                    preserved++;
                }
            }
        }
        assertEquals(6, preserved);
    }
}
