package com.supertokens.session;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Optional real-Core layer; enabled only with -PcoreProtocolUrl=http://host:port. */
public class CoreSessionProtocolTest extends CoreSessionProtocolFixture {
    @Test public void candidateKeepsUsableCoreLineagePromotionDeliveredFirst() throws Exception { exercise(true); }
    @Test public void candidateKeepsUsableCoreLineageRefreshDeliveredFirst() throws Exception { exercise(false); }

    private void exercise(boolean promotionFirst) throws Exception {
        String backend = backend();
        assertNotNull("Explicit live Core harness required", backend);
        OkHttpClient raw = new OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch promotionReady = new CountDownLatch(1);
        CountDownLatch refreshReady = new CountDownLatch(1);
        CountDownLatch releasePromotion = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        String handle = null;
        try {
            JSONObject owner;
            JSONObject input = new JSONObject().put("email", "sdk-lineage-" + UUID.randomUUID() + "@example.com")
                    .put("recipe", "google");
            try (Response response = raw.newCall(jsonPost(backend + "/test/email-change-owner", input)).execute()) {
                assertEquals("Fixture creation status", 200, response.code());
                owner = new JSONObject(response.body().string());
                assertTrue("Genuine mapped Google recipe required", owner.getBoolean("google"));
            }
            SessionTokens child;
            try (Response response = raw.newCall(jsonPost(backend + "/auth/session/refresh", new JSONObject())
                    .newBuilder().header("Authorization", "Bearer " + owner.getString("refreshToken")).build()).execute()) {
                assertEquals("Initial refresh status", 200, response.code());
                child = credentials(response);
            }
            SessionCommitResult installed = SuperTokens.installSession(context,
                    SuperTokens.getSessionSnapshot(context).getOwner(), child);
            assertTrue("Actual Core tuple must pass candidate identity validation", installed.isSuccess());
            SessionSnapshot initial = installed.getSnapshot();
            handle = initial.getSessionHandle();
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest incoming) throws InterruptedException {
                    try {
                        Request.Builder forwarded = new Request.Builder().url(backend + incoming.getPath())
                                .header("st-auth-mode", "header").header("rid", "session");
                        if (incoming.getHeader("Authorization") != null)
                            forwarded.header("Authorization", incoming.getHeader("Authorization"));
                        if (incoming.getHeader("anti-csrf") != null)
                            forwarded.header("anti-csrf", incoming.getHeader("anti-csrf"));
                        if (incoming.getMethod().equals("POST")) forwarded.post(RequestBody.create(MediaType.get("application/json"), "{}"));
                        try (Response result = raw.newCall(forwarded.build()).execute()) {
                            MockResponse out = new MockResponse().setResponseCode(result.code())
                                    .setBody(result.body() == null ? "" : result.body().string());
                            for (String header : new String[]{"st-access-token", "st-refresh-token", "front-token", "anti-csrf"}) {
                                for (String value : result.headers(header)) out.addHeader(header, value);
                            }
                            if (incoming.getPath().equals("/test/protected") && promotionReady.getCount() != 0) {
                                assertEquals("Actual child access promotion status", 200, result.code());
                                assertNotNull("Actual access promotion header", result.header("st-access-token"));
                                assertNotNull("Actual front promotion header", result.header("front-token"));
                                assertNull("Ordinary promotion must omit refresh", result.header("st-refresh-token"));
                                promotionReady.countDown();
                                if (!releasePromotion.await(10, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(504);
                            } else if (incoming.getPath().equals("/auth/session/refresh") && refreshReady.getCount() != 0) {
                                assertEquals("Actual Core rotation status", 200, result.code());
                                assertNotNull("Actual rotated refresh header", result.header("st-refresh-token"));
                                refreshReady.countDown();
                                if (!releaseRefresh.await(10, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(504);
                            }
                            return out;
                        }
                    } catch (Exception failure) { return new MockResponse().setResponseCode(502); }
                }
            });
            Future<Integer> ordinary = workers.submit(() -> request(false, null, "/test/protected"));
            assertTrue("Ordinary promotion processed by real Core", promotionReady.await(10, TimeUnit.SECONDS));
            Future<SessionResolution> refresh = workers.submit(() -> SuperTokens.resolveAccessToken(context,
                    initial.getOwner(), true, null));
            assertTrue("Refresh processed by real Core", refreshReady.await(10, TimeUnit.SECONDS));
            if (promotionFirst) {
                releasePromotion.countDown();
                assertEquals(200, (int) ordinary.get(5, TimeUnit.SECONDS));
                releaseRefresh.countDown();
            } else {
                releaseRefresh.countDown();
                assertTrue(refresh.get(5, TimeUnit.SECONDS).isSuccess());
                releasePromotion.countDown();
            }
            assertEquals(200, (int) ordinary.get(5, TimeUnit.SECONDS));
            assertTrue("Candidate accepted rotated Core tuple", refresh.get(5, TimeUnit.SECONDS).isSuccess());
            SessionSnapshot accepted = SuperTokens.getSessionSnapshot(context);
            assertSame(initial.getOwner(), accepted.getOwner());
            assertTrue("Opaque refresh credential rotated", !child.getRefreshToken().equals(accepted.getTokens().getRefreshToken()));
            assertEquals("Candidate selected access remains usable at Core", 200, request(false, null, "/test/protected"));
            assertTrue("Candidate selected refresh remains usable after child access use",
                    SuperTokens.resolveAccessToken(context, initial.getOwner(), true, null).isSuccess());
        } finally {
            releasePromotion.countDown(); releaseRefresh.countDown();
            workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
            if (handle != null) {
                try (Response ignored = raw.newCall(jsonPost(backend + "/test/revoke-session",
                        new JSONObject().put("handle", handle))).execute()) { }
            }
            raw.connectionPool().evictAll(); raw.dispatcher().executorService().shutdownNow();
        }
    }

    private static Request jsonPost(String url, JSONObject body) {
        return new Request.Builder().url(url).header("st-auth-mode", "header").header("rid", "session")
                .post(RequestBody.create(MediaType.get("application/json"), body.toString())).build();
    }

    private static SessionTokens credentials(Response response) {
        return new SessionTokens(response.header("st-access-token"), response.header("st-refresh-token"),
                response.header("front-token"), response.header("anti-csrf"));
    }
}
