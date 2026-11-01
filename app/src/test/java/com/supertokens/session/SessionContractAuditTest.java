package com.supertokens.session;

import android.content.Context;
import android.content.SharedPreferences;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Integration checks added during cross-module contract review, using the production storage adapter. */
public class SessionContractAuditTest {
    private Context context;
    private Preferences preferences;
    private MockWebServer server;
    private OkHttpClient client;

    @Before public void setUp() throws Exception {
        SuperTokens.resetForTests();
        preferences = new Preferences();
        context = mock(Context.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences.instance);
        server = new MockWebServer();
        server.start();
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header").build();
        client = new OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS)
                .addInterceptor(new SuperTokensInterceptor()).build();
    }

    @After public void tearDown() throws Exception {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
        server.shutdown();
        SuperTokens.resetForTests();
    }

    @Test public void legitimatePartialPromotionPreservesOwnerAndRefresh() throws Exception {
        SessionTokens original = tokens("handle-A", "before", "anti-A");
        SessionSnapshot installed = install(original);
        SessionTokens promoted = tokens("handle-A", "after", null);
        server.enqueue(new MockResponse().setBody("profile")
                .addHeader("st-access-token", promoted.getAccessToken())
                .addHeader("front-token", promoted.getFrontToken()));
        call(null);
        SessionSnapshot actual = SuperTokens.getSessionSnapshot(context);
        assertSame(installed.getOwner(), actual.getOwner());
        assertEquals(promoted.getAccessToken(), actual.getTokens().getAccessToken());
        assertEquals(original.getRefreshToken(), actual.getTokens().getRefreshToken());
        assertEquals("after", actual.getAccessTokenPayload().getString("claim"));
        assertEquals("Bearer " + original.getAccessToken(), server.takeRequest(1, TimeUnit.SECONDS).getHeader("Authorization"));
    }

    @Test public void ownTaggedAuthenticationReturnsReceiptAndClearsOldAntiCsrf() throws Exception {
        SessionSnapshot original = install(tokens("handle-A", "A", "anti-A"));
        SessionRequestTag tag = SuperTokens.captureRequestTag(context);
        SessionTokens replacement = tokens("handle-B", "B", null);
        server.enqueue(response(replacement));
        call(tag);
        SessionSnapshot actual = SuperTokens.getSessionSnapshot(context);
        assertNotSame(original.getOwner(), actual.getOwner());
        assertFalse(original.isCurrent());
        assertNotNull(tag.getAcceptedSnapshot());
        assertSame(actual.getOwner(), tag.getAcceptedSnapshot().getOwner());
        assertNull(actual.getTokens().getAntiCSRF());
        assertFalse(preferences.memory.containsKey(Constants.CSRF_TOKEN_PREFS_KEY));
    }

    @Test public void frontOnlyResponseCannotEstablishHeaderSession() throws Exception {
        SessionSnapshot absent = SuperTokens.getSessionSnapshot(context);
        server.enqueue(new MockResponse().setBody("malformed login")
                .addHeader("front-token", tokens("handle-A", "A", null).getFrontToken()));
        try {
            call(null);
        } catch (IOException rejectedMalformedResponse) {
            assertNotNull(rejectedMalformedResponse.getMessage());
        }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS));
        SessionSnapshot actual = SuperTokens.getSessionSnapshot(context);
        assertSame(absent.getOwner(), actual.getOwner());
        assertEquals(SessionSnapshot.Status.ABSENT, actual.getStatus());
        assertNull(actual.getTokens());
    }

    @Test public void missingAccessOnHeaderRefreshDoesNotDestroyUsableTuple() throws Exception {
        SessionSnapshot original = install(tokens("handle-A", "A", null));
        SessionTokens rotated = tokens("handle-A", "rotated", null);
        server.enqueue(new MockResponse().setBody("{}")
                .addHeader("front-token", rotated.getFrontToken())
                .addHeader("st-refresh-token", "rotated-refresh"));
        SessionResolution result = SuperTokens.resolveAccessToken(context, original.getOwner(), true, null);
        assertFalse(result.isSuccess());
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS));
        SessionSnapshot actual = SuperTokens.getSessionSnapshot(context);
        assertSame(original.getOwner(), actual.getOwner());
        assertEquals(original.getTokens().getAccessToken(), actual.getTokens().getAccessToken());
        assertEquals(original.getTokens().getRefreshToken(), actual.getTokens().getRefreshToken());
    }

    @Test public void failedCommitAfterRamMutationInvalidatesOwnerAndPublishesRecovery() throws Exception {
        SessionSnapshot original = install(tokens("handle-A", "A", null));
        List<SessionSnapshot> observations = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch recoveryObserved = new CountDownLatch(1);
        try (SessionSubscription subscription = SuperTokens.observeSessions(context, snapshot -> {
            observations.add(snapshot);
            if (snapshot.getStatus() == SessionSnapshot.Status.RECOVERY_REQUIRED) recoveryObserved.countDown();
        })) {
            subscription.activate();
            int before = preferences.commits;
            preferences.failNextCommit = true;
            SessionCommitResult failed = SuperTokens.installSession(context, original.getOwner(), tokens("handle-B", "B", null));
            assertEquals(SessionCommitResult.Status.PERSISTENCE_FAILED, failed.getStatus());
            assertFalse(original.isCurrent());
            assertNull(SuperTokens.getAccessToken(context));
            SessionSnapshot recovery = SuperTokens.getSessionSnapshot(context);
            assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, recovery.getStatus());
            assertTrue("observer learns fail-closed state", recoveryObserved.await(2, TimeUnit.SECONDS));
            assertFalse(observations.isEmpty());
            assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, observations.get(observations.size() - 1).getStatus());
            assertEquals("failed adoption plus recovery tombstone", before + 2, preferences.commits);
            assertFalse(preferences.disk.containsKey("st-storage-item-st-access-token"));
            assertEquals("SDK changes must use acknowledged transactions", 0, preferences.applies);
        }
    }

    @Test public void failedTombstoneReportsUncertaintyWithoutRollingBackMemory() throws Exception {
        SessionSnapshot original = install(tokens("handle-A", "A", null));
        Map<String, String> originalDisk = new HashMap<>(preferences.disk);
        preferences.failEveryCommit = true;
        SessionCommitResult failed = SuperTokens.clearSession(context, original.getOwner());
        assertEquals(SessionCommitResult.Status.PERSISTENCE_FAILED, failed.getStatus());
        assertFalse(original.isCurrent());
        assertNull(SuperTokens.getSessionSnapshot(context).getTokens());
        assertEquals("failed persistence cannot promise erased disk", originalDisk, preferences.disk);
    }

    @Test public void correlationIsDurableAndDirectReplacementClearsIt() throws Exception {
        SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
        SessionCommitResult result = SuperTokens.installSession(context, before.getOwner(),
                tokens("handle-A", "A", null), "rownd-operation-A");
        assertTrue(result.isSuccess());
        SessionSnapshot installed = result.getSnapshot();
        assertNotEquals(before.getAdoptionId(), installed.getAdoptionId());
        assertEquals("rownd-operation-A", installed.getAdoptionCorrelation());
        String adoption = installed.getAdoptionId();
        SuperTokens.resetForTests();
        preferences.memory.clear();
        preferences.memory.putAll(preferences.disk);
        new SuperTokens.Builder(context, server.url("/").toString()).tokenTransferMethod("header").build();
        SessionSnapshot hydrated = SuperTokens.getSessionSnapshot(context);
        assertNotSame(installed.getOwner(), hydrated.getOwner());
        assertEquals(adoption, hydrated.getAdoptionId());
        assertEquals("rownd-operation-A", hydrated.getAdoptionCorrelation());
        SessionSnapshot replacement = install(tokens("handle-B", "B", null));
        assertNotEquals(adoption, replacement.getAdoptionId());
        assertNull(replacement.getAdoptionCorrelation());
    }

    private SessionSnapshot install(SessionTokens tokens) {
        SessionCommitResult result = SuperTokens.installSession(context,
                SuperTokens.getSessionSnapshot(context).getOwner(), tokens);
        assertEquals(SessionCommitResult.Status.SUCCESS, result.getStatus());
        return result.getSnapshot();
    }

    private void call(SessionRequestTag tag) throws IOException {
        Request.Builder request = new Request.Builder().url(server.url("/profile"));
        if (tag != null) request.tag(SessionRequestTag.class, tag);
        try (Response response = client.newCall(request.build()).execute()) {
            assertEquals(200, response.code());
        }
    }

    private static MockResponse response(SessionTokens tokens) {
        MockResponse response = new MockResponse().setBody("{}").addHeader("st-access-token", tokens.getAccessToken())
                .addHeader("st-refresh-token", tokens.getRefreshToken()).addHeader("front-token", tokens.getFrontToken());
        if (tokens.getAntiCSRF() != null) response.addHeader("anti-csrf", tokens.getAntiCSRF());
        return response;
    }

    private static SessionTokens tokens(String handle, String claim, String anti) throws Exception {
        JSONObject payload = new JSONObject().put("sub", "primary-user").put("sessionHandle", handle)
                .put("tId", "public").put("exp", 4102444800L).put("iat", 1700000000L).put("claim", claim);
        String access = url64("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"version\":\"4\"}")
                + "." + url64(payload.toString()) + "." + url64("fixture-signature");
        JSONObject front = new JSONObject().put("uid", "primary-user").put("ate", 4102444800000L)
                .put("up", new JSONObject().put("claim", claim));
        return new SessionTokens(access, "refresh-" + handle,
                Base64.getEncoder().encodeToString(front.toString().getBytes(StandardCharsets.UTF_8)), anti);
    }

    private static String url64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Preferences {
        final Map<String, String> memory = new HashMap<>();
        final Map<String, String> disk = new HashMap<>();
        boolean failNextCommit;
        boolean failEveryCommit;
        int commits;
        int applies;
        final SharedPreferences instance = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAll": return new HashMap<>(memory);
                        case "getString": return memory.getOrDefault(args[0], (String) args[1]);
                        case "contains": return memory.containsKey(args[0]);
                        case "edit": return editor();
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });

        private Object editor() {
            Map<String, String> pending = new HashMap<>();
            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (editor, operation, args) -> {
                        switch (operation.getName()) {
                            case "putString": pending.put((String) args[0], (String) args[1]); return editor;
                            case "remove": pending.put((String) args[0], null); return editor;
                            case "commit":
                            case "apply":
                                pending.forEach((key, value) -> {
                                    if (value == null) memory.remove(key); else memory.put(key, value);
                                });
                                if (operation.getName().equals("apply")) { applies++; return null; }
                                commits++;
                                if (failNextCommit || failEveryCommit) { failNextCommit = false; return false; }
                                disk.clear();
                                disk.putAll(memory);
                                return true;
                            default: throw new UnsupportedOperationException(operation.getName());
                        }
                    });
        }
    }
}
