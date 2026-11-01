package com.supertokens.session.legacy054;

import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.SessionSnapshot;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensInterceptor;
import java.net.CookieHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;
import static com.supertokens.session.legacy054.OraclePreferences.*;

/** Real published transport writes, with every durable apply-prefix replayed after process loss. */
@RunWith(Parameterized.class)
public class LegacyPartialWriteCrashTest {
    @Parameterized.Parameters(name="{0}") public static Collection<Object[]> flows() {
        return Arrays.asList(new Object[][]{{"login"}, {"refresh"}});
    }
    private final String flow;
    public LegacyPartialWriteCrashTest(String flow) { this.flow = flow; }

    private MockResponse login(String label) throws Exception {
        return new MockResponse().setBody("ok")
                .addHeader("st-access-token", LegacyImportCharacterizationTest.jwt(label, 5))
                .addHeader("st-refresh-token", "invalid-refresh-" + label)
                .addHeader("front-token", LegacyImportCharacterizationTest.front(label, 4102444800000L))
                .addHeader("anti-csrf", "invalid-csrf-" + label);
    }

    private CrashCheckpointPreferences published(MockWebServer server, int checkpoint) throws Exception {
        CrashCheckpointPreferences store = new CrashCheckpointPreferences();
        try (Legacy054Harness legacy = new Legacy054Harness(store.context)) {
            legacy.build(server.url("/").toString(), "header");
            if (flow.equals("refresh")) {
                server.enqueue(login("seed"));
                assertEquals(200, legacy.request(server.url("/login").toString()).get("status"));
                assertEquals("/login", server.takeRequest(5, TimeUnit.SECONDS).getPath());
                assertTrue("Published seed must be durable", store.durable().containsKey(REFRESH));
            }
            store.arm(checkpoint);
            server.enqueue(login("rotated"));
            if (flow.equals("login")) assertEquals(200, legacy.request(server.url("/login").toString()).get("status"));
            else assertEquals(true, legacy.invoke("refresh"));
            RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals(flow.equals("login") ? "/login" : "/auth/session/refresh", request.getPath());
            if (flow.equals("refresh")) assertTrue("Published refresh must send its saved credential",
                    "Bearer invalid-refresh-seed".equals(request.getHeader("Authorization")));
            assertEquals("Published writer unexpectedly switched to commit", 0, store.commits);
            assertTrue("Dropped disk writes must remain visible in old process memory",
                    "invalid-refresh-rotated".equals(store.memory().get(REFRESH)));
        }
        return store;
    }

    @Test public void everyPublishedApplyPrefixRecoversAndAcceptsLaterLogin() throws Exception {
        CookieHandler previous = CookieHandler.getDefault();
        CookieHandler.setDefault(null);
        OkHttpClient client = new OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS)
                .addInterceptor(new SuperTokensInterceptor()).build();
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            CrashCheckpointPreferences complete = published(server, Integer.MAX_VALUE);
            int count = complete.applies;
            assertTrue("Published flow must expose multiple separate apply checkpoints", count > 1);
            assertEquals(count, complete.persistedApplies);
            assertTrue("Complete published response must persist refresh", "invalid-refresh-rotated".equals(complete.durable().get(REFRESH)));
            List<String> expectedEdits = new ArrayList<>(complete.edits);
            System.out.println("PUBLISHED CHECKPOINTS " + flow + " applies=" + count + " edits=" + expectedEdits);
            for (int k = 0; k <= count; k++) {
                SuperTokens.resetForTests();
                CrashCheckpointPreferences store = published(server, k);
                assertEquals(expectedEdits, store.edits);
                assertEquals(count, store.applies);
                assertEquals(k, store.persistedApplies);
                Map<String, Object> durable = store.durable();
                if (k == 0 && flow.equals("login")) assertTrue("Zero-prefix login must leave empty disk", durable.isEmpty());
                if (k == 0 && flow.equals("refresh")) assertTrue("Zero-prefix refresh must retain seed", "invalid-refresh-seed".equals(durable.get(REFRESH)));
                if (k == 1) assertEquals("First checkpoint must persist exactly one apply", 1, store.persistedApplies);
                if (k == count - 1) assertEquals("Last partial checkpoint must lose exactly the final apply", 1, count - store.persistedApplies);
                if (k == count) assertTrue("Complete checkpoint lost credentials", "invalid-refresh-rotated".equals(durable.get(REFRESH)));
                store.crashAndRecover();
                assertTrue("Restart must discard process-only edits", durable.equals(store.memory()));
                new SuperTokens.Builder(store.context, server.url("/").toString()).tokenTransferMethod("header").build();
                SessionSnapshot.Status status = SuperTokens.getSessionSnapshot(store.context).getStatus();
                assertTrue("Partial import may be absent or available, never recovery", status == SessionSnapshot.Status.ABSENT || status == SessionSnapshot.Status.AVAILABLE);
                if (k == 0 && flow.equals("login")) assertEquals(SessionSnapshot.Status.ABSENT, status);
                if (k == count) {
                    assertEquals(SessionSnapshot.Status.AVAILABLE, status);
                    assertEquals("rotated", SuperTokens.getUserId(store.context));
                }
                SuperTokens.resetForTests();
                store.crashAndRecover();
                new SuperTokens.Builder(store.context, server.url("/").toString()).tokenTransferMethod("header").build();
                assertEquals("Partial import must not become stuck on second restart", status, SuperTokens.getSessionSnapshot(store.context).getStatus());
                server.enqueue(login("recovered"));
                try (Response response = client.newCall(new Request.Builder().url(server.url("/login")).build()).execute()) {
                    assertEquals("Subsequent candidate login must succeed", 200, response.code());
                }
                assertEquals("/login", server.takeRequest(5, TimeUnit.SECONDS).getPath());
                assertEquals(SessionSnapshot.Status.AVAILABLE, SuperTokens.getSessionSnapshot(store.context).getStatus());
                assertEquals("recovered", SuperTokens.getUserId(store.context));
                assertTrue("Subsequent login must persist refresh", "invalid-refresh-recovered".equals(store.durable().get(REFRESH)));
                System.out.println("CRASH PREFIX " + flow + " k=" + k + "/" + count + " imported=" + status + " relogin=AVAILABLE");
            }
        } finally {
            SuperTokens.resetForTests();
            CookieHandler.setDefault(previous);
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
        }
    }
}
