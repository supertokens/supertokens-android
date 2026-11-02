package com.example.example;

import com.supertokens.session.SuperTokens;
import com.supertokens.session.SessionRequestTag;
import com.supertokens.session.SessionSnapshot;
import com.supertokens.session.StaleSessionException;
import com.supertokens.session.SuperTokensHttpURLConnection;
import java.net.HttpURLConnection;
import java.net.URL;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.MediaType;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class ParallelSessionLiveTest {
    @Parameterized.Parameters(name = "{0}, urlConnection={1}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][]{{"header", false}, {"header", true}, {"cookie", false}, {"cookie", true}});
    }
    private final String method;
    private final boolean urlConnection;
    private LiveFixture fixture;
    private final OkHttpClient control = new OkHttpClient();
    public ParallelSessionLiveTest(String method, boolean urlConnection) { this.method = method; this.urlConnection = urlConnection; }
    @BeforeClass public static void start() { com.example.TestUtils.beforeAll(); }
    @AfterClass public static void stop() { com.example.TestUtils.afterAll(); }
    @Before public void setup() throws Exception { fixture = new LiveFixture(method, urlConnection); }
    @After public void cleanup() throws Exception {
        try { barrier("DELETE", null); } finally {
            if (fixture != null) fixture.close();
            control.connectionPool().evictAll();
            control.dispatcher().executorService().shutdownNow();
        }
    }

    private String barrier(String verb, String id) throws Exception {
        return barrier(verb, id, "resource");
    }

    private String barrier(String verb, String id, String target) throws Exception {
        String runId = System.getenv("ST_TEST_RUN_ID");
        if (runId == null) throw new AssertionError("isolated fixture required");
        Request.Builder request = new Request.Builder().url(Constants.apiDomain + "/test/request-barrier")
                .header("x-st-test-run-id", runId);
        if (verb.equals("POST")) request.post(RequestBody.create(MediaType.parse("application/json"),
                "{\"id\":\"" + id + "\",\"target\":\"" + target + "\"}"));
        else if (verb.equals("DELETE")) request.delete();
        try (Response response = control.newCall(request.build()).execute()) {
            assertEquals(200, response.code());
            return response.body().string();
        }
    }

    private void awaitArrivals(int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            com.google.gson.JsonObject status = new JsonParser().parse(barrier("GET", null)).getAsJsonObject();
            assertTrue("barrier expired", status.get("active").getAsBoolean());
            if (status.get("arrived").getAsInt() >= count) return;
            Thread.sleep(25);
        }
        fail("timed out waiting for " + count + " backend arrivals");
    }

    private int heldRequest(String id) throws Exception {
        if (urlConnection) {
            HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(
                    new URL(Constants.apiDomain + "/"), con -> {
                        con.setConnectTimeout(15000);
                        con.setReadTimeout(15000);
                        con.setRequestProperty("x-st-test-barrier", id);
                    });
            try { return connection.getResponseCode(); } finally { connection.disconnect(); }
        }
        Request request = new Request.Builder().url(Constants.apiDomain + "/")
                .header("x-st-test-barrier", id).build();
        try (Response response = fixture.client.newCall(request).execute()) { return response.code(); }
    }

    @Test public void concurrentExpiredRequestsShareRefresh() throws Exception {
        assertEquals(200, fixture.login("parallel-refresh"));
        int before = com.example.TestUtils.getRefreshTokenCounter();
        Thread.sleep(5000);
        barrier("POST", "parallel-401");
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> responses = new ArrayList<>();
            for (int i = 0; i < 20; i++) responses.add(executor.submit(() -> {
                ready.countDown();
                if (!go.await(15, TimeUnit.SECONDS)) throw new AssertionError("start timed out");
                return heldRequest("parallel-401");
            }));
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            go.countDown();
            awaitArrivals(20);
            barrier("DELETE", null);
            for (Future<Integer> response : responses) assertEquals(200, (int) response.get(30, TimeUnit.SECONDS));
            assertEquals(before + 1, com.example.TestUtils.getRefreshTokenCounter());
            assertTrue(SuperTokens.doesSessionExist(fixture.context));
        } finally {
            go.countDown();
            barrier("DELETE", null);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test public void inFlightOldOwnerCannotOverwriteReplacement() throws Exception {
        assertEquals(200, fixture.login("in-flight-a"));
        barrier("POST", "old-owner");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> old = executor.submit(() -> heldRequest("old-owner"));
            awaitArrivals(1);
            assertEquals(200, fixture.login("in-flight-b"));
            SessionSnapshot replacement = SuperTokens.getSessionSnapshot(fixture.context);
            barrier("DELETE", null);
            try { old.get(15, TimeUnit.SECONDS); }
            catch (java.util.concurrent.ExecutionException error) {
                if (!(error.getCause() instanceof StaleSessionException)) throw error;
            }
            SessionSnapshot current = SuperTokens.getSessionSnapshot(fixture.context);
            assertSame(replacement.getOwner(), current.getOwner());
            assertEquals(replacement.getRevision(), current.getRevision());
            if (replacement.getTokens() != null) {
                assertNotNull(current.getTokens());
                assertEquals(replacement.getTokens().getAccessToken(), current.getTokens().getAccessToken());
                assertEquals(replacement.getTokens().getRefreshToken(), current.getTokens().getRefreshToken());
            }
        } finally {
            barrier("DELETE", null);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test public void loginWhileSignOutIsInFlightKeepsReplacement() throws Exception {
        assertEquals(200, fixture.login("signout-a"));
        barrier("POST", "signout", "signout");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> signOut = executor.submit(() -> { SuperTokens.signOut(fixture.context); return null; });
            awaitArrivals(1);
            assertEquals(200, fixture.login("signout-b"));
            SessionSnapshot replacement = SuperTokens.getSessionSnapshot(fixture.context);
            barrier("DELETE", null);
            try { signOut.get(15, TimeUnit.SECONDS); }
            catch (java.util.concurrent.ExecutionException error) {
                if (!(error.getCause() instanceof StaleSessionException)) throw error;
            }
            SessionSnapshot current = SuperTokens.getSessionSnapshot(fixture.context);
            assertSame(replacement.getOwner(), current.getOwner());
            assertEquals(replacement.getRevision(), current.getRevision());
            assertTrue(SuperTokens.doesSessionExist(fixture.context));
        } finally {
            barrier("DELETE", null);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test public void oldOwnerTagCannotUseReplacementCredentials() throws Exception {
        assertEquals(200, fixture.login("owner-a"));
        SessionRequestTag old = SuperTokens.captureRequestTag(fixture.context);
        assertEquals(200, fixture.login("owner-b"));
        SessionSnapshot replacement = SuperTokens.getSessionSnapshot(fixture.context);
        assertNotSame(old.getOwner(), replacement.getOwner());
        try {
            if (urlConnection) {
                HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(
                        new URL(Constants.apiDomain + "/"), con -> {}, old);
                try { connection.getResponseCode(); } finally { connection.disconnect(); }
            } else {
                Request request = new Request.Builder().url(Constants.apiDomain + "/")
                        .tag(SessionRequestTag.class, old).build();
                try (Response ignored = fixture.client.newCall(request).execute()) { }
            }
            fail("stale owner request must not use replacement credentials");
        } catch (StaleSessionException expected) { }
        SessionSnapshot current = SuperTokens.getSessionSnapshot(fixture.context);
        assertSame(replacement.getOwner(), current.getOwner());
        assertEquals(replacement.getRevision(), current.getRevision());
        if (replacement.getTokens() != null) {
            assertNotNull(current.getTokens());
            assertEquals(replacement.getTokens().getAccessToken(), current.getTokens().getAccessToken());
            assertEquals(replacement.getTokens().getRefreshToken(), current.getTokens().getRefreshToken());
        } else assertNull(current.getTokens());
    }
}
