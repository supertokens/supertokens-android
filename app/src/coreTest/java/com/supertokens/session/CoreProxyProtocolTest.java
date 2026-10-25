package com.supertokens.session;

import android.util.Base64;
import java.nio.charset.Charset;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Requires the Core owner's completed-response qualification proxy. */
public class CoreProxyProtocolTest extends CoreSessionProtocolFixture {
    private static final String PROFILE = "/auth/plugin/rownd/user";
    private static final String REFRESH = "/auth/session/refresh";

    @Test public void refreshDeliveredBeforePromotion() throws Exception { exercise(false); }
    @Test public void promotionDeliveredBeforeRefresh() throws Exception { exercise(true); }

    private void exercise(boolean promotionFirst) throws Exception {
        String base = backend();
        assertNotNull("Qualification proxy URL required", base);
        OkHttpClient raw = new OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build();
        OkHttpClient managed = raw.newBuilder().addInterceptor(new SuperTokensInterceptor(raw)).build();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        String handle = null;
        boolean armed = false;
        try {
            JSONObject owner = post(raw, base + "/test/email-change-owner", new JSONObject()
                    .put("email", "sdk-proxy-" + UUID.randomUUID() + "@example.com").put("recipe", "google"));
            assertTrue(owner.getBoolean("google"));
            SessionTokens child;
            try (Response response = raw.newCall(postRequest(base + REFRESH, new JSONObject()).newBuilder()
                    .header("Authorization", "Bearer " + owner.getString("refreshToken")).build()).execute()) {
                assertEquals("Initial raw refresh", 200, response.code());
                child = new SessionTokens(response.header("st-access-token"), response.header("st-refresh-token"),
                        response.header("front-token"), response.header("anti-csrf"));
            }
            JSONObject access = new JSONObject(new String(Base64.decode(child.getAccessToken().split("\\.")[1],
                    Base64.URL_SAFE), Charset.forName("UTF-8")));
            assertTrue("Child access must retain parent lineage", access.has("parentRefreshTokenHash1")
                    && !access.isNull("parentRefreshTokenHash1"));
            assertTrue(new SuperTokens.Builder(context, base).reconfigure().isSuccess());
            SessionCommitResult installed = SuperTokens.installSession(context,
                    SuperTokens.getSessionSnapshot(context).getOwner(), child);
            assertTrue(installed.isSuccess());
            SessionSnapshot initial = installed.getSnapshot();
            handle = initial.getSessionHandle();
            armed = true;
            post(raw, base + "/__qualification/arm", new JSONObject()
                    .put("paths", new JSONArray().put(PROFILE).put(REFRESH)));
            Future<Integer> ordinary = workers.submit(() -> {
                try (Response response = managed.newCall(new Request.Builder().url(base + PROFILE).build()).execute()) {
                    return response.code();
                }
            });
            JSONObject promotion = awaitReady(raw, base, PROFILE);
            assertEquals(200, promotion.getInt("status"));
            assertTrue(promotion.getBoolean("hasAccess"));
            assertTrue(promotion.getBoolean("hasFront"));
            assertFalse(promotion.getBoolean("hasRefresh"));
            assertTrue(promotion.getBoolean("accessChangedFromRequest"));
            Future<SessionResolution> refresh = workers.submit(() -> SuperTokens.resolveAccessToken(context,
                    initial.getOwner(), true, null));
            JSONObject rotation = awaitReady(raw, base, REFRESH);
            assertEquals(200, rotation.getInt("status"));
            assertTrue(rotation.getBoolean("hasAccess"));
            assertTrue(rotation.getBoolean("hasFront"));
            assertTrue(rotation.getBoolean("hasRefresh"));
            if (promotionFirst) {
                release(raw, base, PROFILE);
                assertEquals(200, (int) ordinary.get(5, TimeUnit.SECONDS));
                release(raw, base, REFRESH);
                assertTrue(refresh.get(5, TimeUnit.SECONDS).isSuccess());
            } else {
                release(raw, base, REFRESH);
                assertTrue(refresh.get(5, TimeUnit.SECONDS).isSuccess());
                release(raw, base, PROFILE);
                assertEquals(200, (int) ordinary.get(5, TimeUnit.SECONDS));
            }
            SessionSnapshot selected = SuperTokens.getSessionSnapshot(context);
            assertSame(initial.getOwner(), selected.getOwner());
            assertEquals(initial.getSessionHandle(), selected.getSessionHandle());
            assertTrue(selected.getRefreshTokenRevision() > initial.getRefreshTokenRevision());
            assertFalse(child.getRefreshToken().equals(selected.getTokens().getRefreshToken()));
            try (Response protectedResponse = raw.newCall(new Request.Builder().url(base + "/test/protected")
                    .header("st-auth-mode", "header").header("rid", "session")
                    .header("Authorization", "Bearer " + selected.getTokens().getAccessToken()).build()).execute()) {
                assertEquals("Exact native selected access", 200, protectedResponse.code());
            }
            try (Response refreshed = raw.newCall(postRequest(base + REFRESH, new JSONObject()).newBuilder()
                    .header("Authorization", "Bearer " + selected.getTokens().getRefreshToken()).build()).execute()) {
                assertEquals("Exact native selected refresh after access use", 200, refreshed.code());
                assertNotNull(refreshed.header("st-refresh-token"));
            }
        } finally {
            try {
                if (armed) post(raw, base + "/__qualification/release-all", new JSONObject());
            } finally {
                managed.dispatcher().cancelAll();
                workers.shutdownNow();
                try {
                    assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
                    if (handle != null) post(raw, base + "/test/revoke-session", new JSONObject().put("handle", handle));
                } finally {
                    raw.connectionPool().evictAll();
                    raw.dispatcher().executorService().shutdownNow();
                }
            }
        }
    }

    private static JSONObject awaitReady(OkHttpClient raw, String base, String path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            try (Response response = raw.newCall(new Request.Builder().url(base + "/__qualification/state").build()).execute()) {
                assertEquals(200, response.code());
                JSONObject gate = new JSONObject(response.body().string()).getJSONObject("gates").getJSONObject(path);
                assertFalse("Proxy gate timed out", gate.getBoolean("timedOut"));
                if (gate.getBoolean("responseReady")) {
                    assertTrue(gate.getBoolean("claimed"));
                    assertFalse(gate.getBoolean("released"));
                    assertFalse(gate.getBoolean("delivered"));
                    return gate;
                }
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Proxy response not ready: " + path);
    }

    private static void release(OkHttpClient raw, String base, String path) throws Exception {
        post(raw, base + "/__qualification/release", new JSONObject().put("path", path));
    }

    private static JSONObject post(OkHttpClient raw, String url, JSONObject body) throws Exception {
        try (Response response = raw.newCall(postRequest(url, body)).execute()) {
            assertEquals("Fixture/control HTTP status", 200, response.code());
            return new JSONObject(response.body().string());
        }
    }

    private static Request postRequest(String url, JSONObject body) {
        return new Request.Builder().url(url).header("st-auth-mode", "header").header("rid", "session")
                .post(RequestBody.create(MediaType.get("application/json"), body.toString())).build();
    }
}
