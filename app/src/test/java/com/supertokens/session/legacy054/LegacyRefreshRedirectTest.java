package com.supertokens.session.legacy054;

import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.SessionTestFixture;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyRefreshRedirectTest extends SessionTestFixture {
    @Override protected boolean initializeOnSetup() { return false; }

    @Test public void redirectMethods() throws Exception {
        for (int code : new int[]{301, 302, 303, 307, 308}) {
            context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().clear().commit();
            try (Legacy054Harness old = new Legacy054Harness(context)) {
                old.build(server.url("/").toString(), "header");
                String front = Base64.getEncoder().encodeToString("{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{}}".getBytes("UTF-8"));
                server.enqueue(new MockResponse().addHeader("front-token", front)
                        .addHeader("st-access-token", "access").addHeader("st-refresh-token", "refresh"));
                old.request(server.url("/login").toString());
                server.takeRequest();
                server.enqueue(new MockResponse().setResponseCode(code).addHeader("Location", "/redirected"));
                if (code != 308) server.enqueue(new MockResponse().setResponseCode(200));
                try {
                    old.invoke("refresh");
                } catch (java.io.IOException error) {
                    if (code != 308) throw error;
                }
                assertEquals("POST", server.takeRequest(2, TimeUnit.SECONDS).getMethod());
                RecordedRequest redirected = server.takeRequest(1, TimeUnit.SECONDS);
                if (code == 308) assertNull(redirected);
                else assertEquals("status " + code, code == 307 ? "POST" : "GET", redirected.getMethod());
            }
        }
    }
}
