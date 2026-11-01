package com.supertokens.session.legacy054;

import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.SessionTestFixture;
import com.supertokens.session.SuperTokens;
import java.io.IOException;
import java.lang.reflect.Field;
import okhttp3.mockwebserver.MockResponse;
import org.junit.Test;
import static org.junit.Assert.*;

public class NegativeRetryParityTest extends SessionTestFixture {
    @Test public void negativeRetryAllowsFirstRequestButNeverRefreshesAsPublished() throws Exception {
        try (Legacy054Harness old = new Legacy054Harness(context)) {
            old.build(server.url("/").toString(), "header");
            Field config = old.sdkClass().getDeclaredField("config");
            config.setAccessible(true);
            Object values = config.get(null);
            Field retries = values.getClass().getDeclaredField("maxRetryAttemptsForSessionRefresh");
            retries.setAccessible(true);
            retries.set(values, -2);
            server.enqueue(new MockResponse().setResponseCode(401));
            try { old.request(server.url("/profile").toString()); fail("published retry limit"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("maximum session refresh limit")); }
            assertEquals(1, server.getRequestCount());
        }
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .maxRetryAttemptsForSessionRefresh(-2).reconfigure().isSuccess());
        for (boolean url : new boolean[]{false, true}) {
            server.enqueue(new MockResponse().setResponseCode(401));
            try { request(url, null, "/profile"); fail("candidate retry limit"); }
            catch (IOException | IllegalAccessException expected) {
                assertTrue(expected.getMessage().contains("maximum session refresh limit"));
            }
        }
        assertEquals(3, server.getRequestCount());
    }
}
