package com.supertokens.session.legacy054;

import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.SessionRefreshFailureModeTest;
import com.supertokens.session.SessionRefreshFailureModeTest.Fault;
import com.supertokens.session.SessionTestFixture;
import com.supertokens.session.SessionTokens;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyRefreshFailureOracleTest extends SessionTestFixture {
    @Override protected boolean initializeOnSetup() { return false; }

    private static SessionTokens tokens(String handle, String claim) throws Exception {
        org.json.JSONObject payload = new org.json.JSONObject().put("sessionHandle", handle).put("sub", "primary-user")
                .put("rsub", "recipe-user").put("tId", "public").put("exp", 4102444800L).put("iat", 1700000000L).put("claim", claim);
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
        String access = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"version\":\"5\"}".getBytes(utf8)) + "."
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().getBytes(utf8)) + ".c2lnbmF0dXJl";
        String front = java.util.Base64.getEncoder().encodeToString(new org.json.JSONObject().put("uid", "primary-user")
                .put("ate", 4102444800000L).put("up", payload).toString().getBytes(utf8));
        return new SessionTokens(access, handle + "-refresh-" + claim, front, "csrf");
    }

    private static MockResponse rotation(SessionTokens tokens) {
        return new MockResponse().addHeader("st-access-token", tokens.getAccessToken())
                .addHeader("st-refresh-token", tokens.getRefreshToken()).addHeader("front-token", tokens.getFrontToken());
    }

    @Test public void publishedDirectRefreshFaultCharacterization() throws Exception {
        android.content.SharedPreferences preferences = context.getSharedPreferences("supertokens-android-shared-preferences", 0);
        // Published refresh has no configured native timeout here; timeout remains uncharacterized.
        for (Fault fault : new Fault[]{Fault.HTML, Fault.MISSING_HEADERS, Fault.HTTP_500, Fault.HTTP_502,
                Fault.HTTP_503, Fault.RETRY_AFTER, Fault.RESET, Fault.FRONT_ONLY}) {
            preferences.edit().clear().commit();
            SessionTokens initial = tokens("oracle", "before");
            SessionTokens changed = tokens("oracle", "after");
            AtomicInteger refreshes = new AtomicInteger();
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().equals("/login")) return rotation(initial);
                    refreshes.incrementAndGet();
                    return SessionRefreshFailureModeTest.response(fault, changed);
                }
            });
            try (Legacy054Harness legacy = new Legacy054Harness(context)) {
                legacy.build(server.url("/").toString(), "header");
                legacy.request(server.url("/login").toString());
                Object result = null;
                IOException error = null;
                try { result = legacy.invoke("refresh"); }
                catch (IOException expected) { error = expected; }
                assertEquals(fault == Fault.RESET ? 2 : 1, refreshes.get());
                assertEquals(initial.getAccessToken(), preferences.getString("st-storage-item-st-access-token", null));
                assertEquals(initial.getRefreshToken(), preferences.getString("st-storage-item-st-refresh-token", null));
                assertEquals(fault == Fault.FRONT_ONLY ? changed.getFrontToken() : initial.getFrontToken(),
                        preferences.getString("supertokens-android-fronttoken-key", null));
                boolean malformed = fault == Fault.HTML || fault == Fault.MISSING_HEADERS || fault == Fault.FRONT_ONLY;
                if (malformed) { assertEquals(Boolean.TRUE, result); assertNull(error); }
                else assertNotNull(error);
                System.out.println("ORACLE direct-refresh fault=" + fault + " result=" + result + " exception="
                        + (error == null ? null : error.getClass().getName()) + " refreshes=" + refreshes);
            }
        }
    }
}
