package com.supertokens.session;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic twin only; the Core test separately establishes the observed pair's grace. */
public class LostRefreshResponseTest extends SessionTestFixture {
    @Test public void retainedParentRecoversWhenBackendAllowsOneGraceUse() throws Exception { exercise(true); }
    @Test public void noGraceModelFailsInsteadOfPretendingRecovery() throws Exception { exercise(false); }

    private void exercise(boolean grace) throws Exception {
        SessionTokens initial = tokens("lost-response", "initial");
        FakeBackend backend = new FakeBackend(initial);
        backend.parentGraceUses(grace ? 1 : 0);
        server.setDispatcher(backend);
        assertTrue(SuperTokens.installSession(context, SuperTokens.getSessionSnapshot(context).getOwner(), initial).isSuccess());
        SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
        Map<String, Object> durable = preferenceStore.durableValues();
        OkHttpClient raw = new OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build();
        try {
            try (Response lost = raw.newCall(new Request.Builder().url(server.url("/auth/session/refresh"))
                    .header("Authorization", "Bearer " + initial.getRefreshToken())
                    .post(RequestBody.create(MediaType.get("application/json"), "{}")).build()).execute()) {
                assertEquals(200, lost.code());
                assertTrue("Synthetic backend must rotate", !initial.getRefreshToken().equals(lost.header("st-refresh-token")));
            }
            assertSame(before, SuperTokens.getSessionSnapshot(context));
            assertTrue("Discarded response changed storage", durable.equals(preferenceStore.durableValues()));
            SessionResolution result = SuperTokens.resolveAccessToken(context, before.getOwner(), true, null);
            assertEquals(grace, result.isSuccess());
            assertEquals(2, backend.refreshAttempts.get());
            assertEquals(grace ? 0 : 1, backend.reuseRejections.get());
            if (grace) {
                assertSame(before.getOwner(), SuperTokens.getSessionSnapshot(context).getOwner());
                assertEquals(200, request(false, null, "/protected"));
                assertTrue("Parent was reused for retry", ("Bearer " + initial.getRefreshToken()).equals(backend.requests().get(1).getHeader("Authorization")));
            } else assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        } finally {
            raw.connectionPool().evictAll();
            raw.dispatcher().executorService().shutdownNow();
        }
    }
}
