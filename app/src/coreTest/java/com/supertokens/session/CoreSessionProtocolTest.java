package com.supertokens.session;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;
import org.json.JSONArray;
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
        Throwable primary = null;
        try {
            exerciseWithClient(backend, raw, promotionFirst);
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                raw.connectionPool().evictAll();
                raw.dispatcher().executorService().shutdownNow();
            } catch (Exception | Error cleanup) {
                if (primary != null) primary.addSuppressed(cleanup); else throw cleanup;
            }
        }
    }

    private void exerciseWithClient(String backend, OkHttpClient raw, boolean promotionFirst) throws Exception {
        try (Response response = raw.newCall(new Request.Builder().url(backend + "/test/capabilities").build()).execute()) {
            assertEquals(200, response.code());
            JSONObject capabilities = new JSONObject(response.body().string());
            assertTrue("Compatible mapped Core fixture required", capabilities.getBoolean("compatible")
                    && capabilities.getBoolean("mappedGoogleSession"));
            assertFalse("This test does not qualify the proxy", capabilities.getBoolean("qualificationProxy"));
            if (usesPayloadPromotion(capabilities)) {
                exercisePayloadPromotion(backend, raw, promotionFirst, false);
                exercisePayloadPromotion(backend, raw, promotionFirst, true);
                return;
            }
        }
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch promotionReady = new CountDownLatch(1);
        CountDownLatch refreshReady = new CountDownLatch(1);
        CountDownLatch releasePromotion = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        String handle = null;
        Throwable primary = null;
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
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                releasePromotion.countDown(); releaseRefresh.countDown();
                workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
                if (handle != null) {
                    try (Response ignored = raw.newCall(jsonPost(backend + "/test/revoke-session",
                            new JSONObject().put("handle", handle))).execute()) { }
                }
            } catch (Exception | Error cleanup) {
                if (primary != null) primary.addSuppressed(cleanup); else throw cleanup;
            }
        }
    }

    private void exercisePayloadPromotion(String backend, OkHttpClient raw, boolean promotionFirst,
                                          boolean urlConnection) throws Exception {
        JSONObject owner = null;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch promotionReady = new CountDownLatch(1), refreshReady = new CountDownLatch(1);
        CountDownLatch releasePromotion = new CountDownLatch(1), releaseRefresh = new CountDownLatch(1);
        AtomicReference<String> promotedAccess = new AtomicReference<>();
        AtomicReference<SessionTokens> rotated = new AtomicReference<>();
        AtomicReference<Throwable> dispatchFailure = new AtomicReference<>();
        Throwable primary = null;
        try {
            JSONObject input = new JSONObject().put("email", "sdk-payload-" + UUID.randomUUID() + "@example.com")
                    .put("recipe", "google");
            try (Response response = raw.newCall(jsonPost(backend + "/test/email-change-owner", input)).execute()) {
                assertEquals("Fixture creation status", 200, response.code());
                owner = new JSONObject(response.body().string());
                assertTrue(owner.getBoolean("google"));
            }
            SessionTokens child;
            try (Response response = raw.newCall(jsonPost(backend + "/auth/session/refresh", new JSONObject())
                    .newBuilder().header("Authorization", "Bearer " + owner.getString("refreshToken")).build()).execute()) {
                assertEquals(200, response.code());
                child = credentials(response);
            }
            SessionCommitResult installed = SuperTokens.installSession(context,
                    SuperTokens.getSessionSnapshot(context).getOwner(), child);
            assertTrue(installed.isSuccess());
            SessionSnapshot initial = installed.getSnapshot();
            assertFalse(accessPayload(child.getAccessToken()).optBoolean("fixturePayloadProbe", false));
            final JSONObject expectedOwner = owner;
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
                            String body = result.body() == null ? "" : result.body().string();
                            MockResponse out = new MockResponse().setResponseCode(result.code()).setBody(body);
                            for (String header : new String[]{"st-access-token", "st-refresh-token", "front-token", "anti-csrf"})
                                for (String value : result.headers(header)) out.addHeader(header, value);
                            if (incoming.getPath().equals("/test/payload-promotion")) {
                                assertEquals(200, result.code());
                                assertNotNull(result.header("st-access-token"));
                                assertNotNull(result.header("front-token"));
                                assertNull(result.header("st-refresh-token"));
                                JSONObject actual = new JSONObject(body);
                                assertEquals(expectedOwner.getString("userId"), actual.getString("userId"));
                                assertEquals(expectedOwner.getString("handle"), actual.getString("handle"));
                                promotedAccess.set(result.header("st-access-token"));
                                assertTrue(accessPayload(promotedAccess.get()).getBoolean("fixturePayloadProbe"));
                                promotionReady.countDown();
                                if (!releasePromotion.await(10, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(504);
                            } else if (incoming.getPath().equals("/auth/session/refresh") && refreshReady.getCount() != 0) {
                                assertEquals(200, result.code());
                                SessionTokens tokens = credentials(result);
                                assertNotNull(tokens.getAccessToken());
                                assertNotNull(tokens.getRefreshToken());
                                assertNotNull(tokens.getFrontToken());
                                assertFalse("Core refresh rotates the credential", child.getRefreshToken().equals(tokens.getRefreshToken()));
                                assertTrue("Refresh reflects Core payload update",
                                        accessPayload(tokens.getAccessToken()).getBoolean("fixturePayloadProbe"));
                                rotated.set(tokens);
                                refreshReady.countDown();
                                if (!releaseRefresh.await(10, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(504);
                            }
                            return out;
                        }
                    } catch (Exception | Error failure) {
                        dispatchFailure.compareAndSet(null, failure);
                        promotionReady.countDown(); refreshReady.countDown();
                        return new MockResponse().setResponseCode(502);
                    }
                }
            });
            Future<Integer> ordinary = workers.submit(() -> request(urlConnection, null, "/test/payload-promotion"));
            assertTrue("Core processed promotion", promotionReady.await(10, TimeUnit.SECONDS));
            assertNull("Promotion forwarding", dispatchFailure.get());
            assertNotNull(promotedAccess.get());
            assertFalse("Core promotion changes access", child.getAccessToken().equals(promotedAccess.get()));
            Future<SessionResolution> refresh = workers.submit(() -> SuperTokens.resolveAccessToken(context,
                    initial.getOwner(), true, null));
            assertTrue("Core processed refresh while promotion was held", refreshReady.await(10, TimeUnit.SECONDS));
            assertNull("Refresh forwarding", dispatchFailure.get());
            assertNotNull(rotated.get());
            SessionSnapshot completedRefresh = null;
            if (promotionFirst) {
                releasePromotion.countDown();
                assertEquals(200, (int) ordinary.get(5, TimeUnit.SECONDS));
                SessionSnapshot promoted = SuperTokens.getSessionSnapshot(context);
                assertSame(initial.getOwner(), promoted.getOwner());
                assertEquals(initial.getSessionHandle(), promoted.getSessionHandle());
                assertEquals(initial.getRefreshTokenRevision(), promoted.getRefreshTokenRevision());
                assertTrue("Promotion retains refresh", child.getRefreshToken().equals(promoted.getTokens().getRefreshToken()));
                assertTrue("Promotion is adopted during refresh", promotedAccess.get().equals(promoted.getTokens().getAccessToken()));
                releaseRefresh.countDown();
            } else {
                releaseRefresh.countDown();
                assertTrue(refresh.get(5, TimeUnit.SECONDS).isSuccess());
                completedRefresh = SuperTokens.getSessionSnapshot(context);
                releasePromotion.countDown();
            }
            assertEquals(200, (int) ordinary.get(5, TimeUnit.SECONDS));
            assertTrue("Core refresh accepted", refresh.get(5, TimeUnit.SECONDS).isSuccess());
            SessionSnapshot accepted = SuperTokens.getSessionSnapshot(context);
            assertSame(initial.getOwner(), accepted.getOwner());
            assertEquals(initial.getSessionHandle(), accepted.getSessionHandle());
            assertNotEquals(initial.getRefreshTokenRevision(), accepted.getRefreshTokenRevision());
            if (completedRefresh != null) {
                assertEquals("Late promotion must be fenced", completedRefresh.getRevision(), accepted.getRevision());
            }
            assertTrue("Actual Core refresh remains selected", rotated.get().getRefreshToken().equals(accepted.getTokens().getRefreshToken()));
            assertTrue("Actual Core access remains selected", rotated.get().getAccessToken().equals(accepted.getTokens().getAccessToken()));
            assertEquals(200, request(urlConnection, null, "/test/protected"));
            assertTrue("Rotated refresh remains usable", SuperTokens.resolveAccessToken(context, initial.getOwner(), true, null).isSuccess());
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                releasePromotion.countDown(); releaseRefresh.countDown();
                workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
                if (owner != null) try (Response response = raw.newCall(jsonPost(backend + "/test/cleanup-owner",
                        new JSONObject().put("fixtureId", owner.getString("fixtureId")))).execute()) {
                    assertEquals("Owner cleanup", 200, response.code());
                    assertTrue("Owner cleanup acknowledged", new JSONObject(response.body().string()).getBoolean("cleaned"));
                }
            } catch (Exception | Error cleanup) {
                if (primary != null) primary.addSuppressed(cleanup); else throw cleanup;
            }
        }
    }

    private static JSONObject accessPayload(String access) throws Exception {
        ByteString payload = ByteString.decodeBase64(access.split("\\.")[1]);
        assertNotNull("Access payload must be base64url", payload);
        return new JSONObject(payload.utf8());
    }

    private static boolean usesPayloadPromotion(JSONObject capabilities) throws Exception {
        JSONArray sdk = capabilities.getJSONArray("sdkCdiVersions");
        JSONArray core = capabilities.getJSONArray("coreCdiVersions");
        int bestMajor = -1, bestMinor = -1;
        for (int i = 0; i < sdk.length(); i++) {
            String value = sdk.getString(i);
            if (!value.matches("\\d+\\.\\d+")) continue;
            boolean shared = false;
            for (int j = 0; j < core.length(); j++) if (value.equals(core.getString(j))) shared = true;
            if (!shared) continue;
            String[] parts = value.split("\\.");
            int major = Integer.parseInt(parts[0]), minor = Integer.parseInt(parts[1]);
            if (major > bestMajor || (major == bestMajor && minor > bestMinor)) { bestMajor = major; bestMinor = minor; }
        }
        assertTrue("No shared CDI version", bestMajor >= 0);
        return bestMajor > 5 || (bestMajor == 5 && bestMinor >= 6);
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
