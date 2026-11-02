package com.supertokens.session.legacy054;

import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.SessionTestFixture;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Legacy401RetryOracleTest extends SessionTestFixture {
    @Override protected boolean initializeOnSetup() { return false; }

    @Test public void urlConnection401CreatingSessionExhaustsRetryLimit() throws Exception {
        String front = Base64.getEncoder().encodeToString(
                "{\"uid\":\"user\",\"ate\":4102444800000,\"up\":{}}".getBytes(StandardCharsets.UTF_8));
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger refreshes = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/shape")) {
                    requests.incrementAndGet();
                    return new MockResponse().setResponseCode(401).addHeader("front-token", front);
                }
                refreshes.incrementAndGet();
                return new MockResponse().setResponseCode(401);
            }
        });
        try (Legacy054Harness old = new Legacy054Harness(context)) {
            old.invoke("useCookies");
            old.build(server.url("/").toString(), "header");
            try {
                old.invoke("requestURLConnection", new Class<?>[]{String.class}, server.url("/shape").toString());
                fail("Expected retry limit");
            } catch (IllegalAccessException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("maximum session refresh limit"));
            }
            assertTrue((Boolean) old.invoke("doesSessionExist"));
            assertTrue(requests.get() > 1);
            assertEquals(0, refreshes.get());
        }
    }
}
