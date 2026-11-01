package com.supertokens.session;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Core parent grace after a successful refresh response never reaches SDK storage. */
public class CoreLostRefreshResponseTest extends CoreSessionProtocolFixture {
    @Test public void discardedRotationCanBeRecoveredWithPersistedParentBeforeChildUse() throws Exception {
        String backend = backend();
        assertNotNull("Explicit live backend required", backend);
        OkHttpClient raw = new OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build();
        String fixtureId = null;
        try {
            try (Response response = raw.newCall(new Request.Builder().url(backend + "/test/capabilities").build()).execute()) {
                assertEquals(200, response.code());
                JSONObject capabilities = new JSONObject(response.body().string());
                assertTrue("Mapped live fixture unavailable", capabilities.getBoolean("mappedGoogleSession"));
                assertTrue("Incompatible or unreachable Core", capabilities.getBoolean("compatible"));
            }
            assertTrue(new SuperTokens.Builder(context, backend).tokenTransferMethod("header").reconfigure().isSuccess());
            JSONObject owner;
            SessionTokens original;
            try (Response response = raw.newCall(post(backend + "/test/email-change-owner", new JSONObject()
                    .put("recipe", "google").put("email", "lost-refresh-" + UUID.randomUUID() + "@example.com"), null)).execute()) {
                assertEquals("Scoped fixture creation failed", 200, response.code());
                owner = new JSONObject(response.body().string());
                fixtureId = owner.getString("fixtureId");
                original = credentials(response);
            }
            assertTrue("Real Core login tuple rejected", SuperTokens.installSession(context,
                    SuperTokens.getSessionSnapshot(context).getOwner(), original).isSuccess());
            SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
            Map<String, ?> storedBefore = new java.util.LinkedHashMap<>(context.getSharedPreferences(
                    Constants.SHARED_PREFERENCES_KEY, android.content.Context.MODE_PRIVATE).getAll());

            // Only this raw client sees child credentials. Do not use/promote the child access token.
            try (Response lost = raw.newCall(post(backend + "/auth/session/refresh", new JSONObject(), original.getRefreshToken())).execute()) {
                assertEquals("Core failed initial rotation", 200, lost.code());
                String rotated = lost.header("st-refresh-token");
                assertTrue("Core must actually rotate before response loss", rotated != null && !rotated.equals(original.getRefreshToken()));
                assertTrue("Rotated response must contain access", lost.header("st-access-token") != null);
            }
            assertSame("Raw response must not publish SDK state", before, SuperTokens.getSessionSnapshot(context));
            assertTrue("Raw response must not update SDK preferences", storedBefore.equals(context.getSharedPreferences(
                    Constants.SHARED_PREFERENCES_KEY, android.content.Context.MODE_PRIVATE).getAll()));

            SessionResolution recovered = SuperTokens.resolveAccessToken(context, before.getOwner(), true, null);
            assertTrue("Current SDK/Core pair did not accept the retained parent before child use: " + recovered.getStatus(), recovered.isSuccess());
            SessionSnapshot after = SuperTokens.getSessionSnapshot(context);
            assertEquals(SessionSnapshot.Status.AVAILABLE, after.getStatus());
            assertSame(before.getOwner(), after.getOwner());
            assertTrue("Recovered refresh must differ from retained parent", !original.getRefreshToken().equals(after.getTokens().getRefreshToken()));
            try (Response response = client.newCall(new Request.Builder().url(backend + "/test/protected").build()).execute()) {
                assertEquals("Recovered SDK access must be usable at real Core", 200, response.code());
                JSONObject verified = new JSONObject(response.body().string());
                assertTrue("Protected identity changed", owner.getString("userId").equals(verified.getString("userId")));
                assertTrue("Protected lineage changed", owner.getString("handle").equals(verified.getString("handle")));
            }
            assertTrue("Recovered lineage must still refresh after child use",
                    SuperTokens.resolveAccessToken(context, before.getOwner(), true, null).isSuccess());
        } finally {
            try {
                if (fixtureId != null) {
                    try (Response response = raw.newCall(post(backend + "/test/cleanup-owner", new JSONObject().put("fixtureId", fixtureId), null)).execute()) {
                        assertEquals("Scoped fixture cleanup HTTP failure", 200, response.code());
                        assertTrue("Scoped fixture cleanup failed", new JSONObject(response.body().string()).getBoolean("cleaned"));
                    }
                }
            } finally {
                raw.connectionPool().evictAll();
                raw.dispatcher().executorService().shutdownNow();
            }
        }
    }

    private static Request post(String url, JSONObject body, String token) {
        Request.Builder builder = new Request.Builder().url(url).header("st-auth-mode", "header").header("rid", "session")
                .post(RequestBody.create(MediaType.get("application/json"), body.toString()));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder.build();
    }

    private static SessionTokens credentials(Response response) {
        return new SessionTokens(response.header("st-access-token"), response.header("st-refresh-token"),
                response.header("front-token"), response.header("anti-csrf"));
    }
}
