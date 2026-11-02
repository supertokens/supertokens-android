package com.supertokens.session.legacy054;

import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.SessionTestFixture;
import com.supertokens.session.SuperTokens;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import okhttp3.mockwebserver.MockResponse;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class LegacyR18ResponseShapeTest extends SessionTestFixture {
    @Override protected boolean initializeOnSetup() { return false; }

    @Test public void headerConfigurationAcceptsCookieShapedLogin() throws Exception {
        for (boolean exposedAccess : new boolean[]{false, true}) {
            String front = Base64.getEncoder().encodeToString(
                    "{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{}}".getBytes(StandardCharsets.UTF_8));
            Object expected;
            try (Legacy054Harness old = new Legacy054Harness(context)) {
                old.build(server.url("/").toString(), "header");
                server.enqueue(login(front, exposedAccess));
                old.request(server.url("/login").toString());
                expected = old.invoke("doesSessionExist");
            }
            context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
            SuperTokens.resetForTests();
            new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header").build();
            server.enqueue(login(front, exposedAccess));
            request(false, null, "/login");
            assertEquals(expected, SuperTokens.doesSessionExist(context));
            SuperTokens.resetForTests();
            context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
        }
    }

    private MockResponse login(String front, boolean exposedAccess) {
        MockResponse response = new MockResponse().addHeader("front-token", front)
                .addHeader("Set-Cookie", "sRefreshToken=cookie; Path=/");
        return exposedAccess ? response.addHeader("st-access-token", "access") : response;
    }
}
