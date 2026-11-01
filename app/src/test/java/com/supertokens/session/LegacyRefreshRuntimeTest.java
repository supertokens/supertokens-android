package com.supertokens.session;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyRefreshRuntimeTest extends SessionTestFixture {
    @Test public void slowPreparationDoesNotImposeSdkDeadlineOnCallersChain() throws Exception {
        AtomicLong clock = new AtomicLong();
        assertTrue(new SuperTokens.Builder(context, server.url("/").toString())
                .customHeaderProvider(type -> {
                    clock.set(TimeUnit.SECONDS.toNanos(11));
                    return null;
                }).reconfigure().isSuccess());
        SessionSnapshot before = install("deadline", "before");
        RefreshDeadline.setClockForTests(clock::get);
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(rotation(tokens("deadline", "after")));
        server.enqueue(new MockResponse().setBody("ok"));
        Call call = client.newCall(new Request.Builder().url(server.url("/profile")).build());
        try {
            try (Response response = call.execute()) { assertEquals(200, response.code()); }
            assertFalse(call.isCanceled());
            assertEquals(3, server.getRequestCount());
            assertSame(before.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
        } finally {
            RefreshDeadline.setClockForTests(null);
        }
    }
}
