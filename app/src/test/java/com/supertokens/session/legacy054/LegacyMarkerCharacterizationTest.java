package com.supertokens.session.legacy054;

import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.SessionCommitResult;
import com.supertokens.session.SessionTokens;
import com.supertokens.session.SuperTokens;
import java.net.CookieHandler;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.supertokens.session.legacy054.OraclePreferences.*;
import static com.supertokens.session.legacy054.LegacyImportCharacterizationTest.*;

public class LegacyMarkerCharacterizationTest {
    private MockWebServer server;
    private CookieHandler cookies;

    @Before public void setUp() throws Exception {
        cookies = CookieHandler.getDefault();
        CookieHandler.setDefault(null);
        server = new MockWebServer();
        server.start();
    }

    @After public void tearDown() throws Exception {
        try { server.shutdown(); } finally {
            SuperTokens.resetForTests();
            CookieHandler.setDefault(cookies);
        }
    }

    private static Map<String, Object> session() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(ACCESS, jwt("owner-a", 5));
        values.put(REFRESH, "refresh-a");
        values.put(FRONT, front("owner-a", 4102444800000L));
        values.put(CSRF, "csrf-a");
        values.put(MARKER, "1");
        values.put(OLD_FRONTEND, "old-frontend");
        return values;
    }

    private static MockResponse response(boolean cookie, String user) throws Exception {
        MockResponse response = new MockResponse().setBody("{\"status\":\"OK\"}")
                .addHeader("front-token", front(user, 4102444800000L)).addHeader("anti-csrf", "csrf-b");
        if (!cookie) response.addHeader("st-access-token", jwt(user, 5) + "b").addHeader("st-refresh-token", "refresh-b");
        return response;
    }

    private static Set<String> changed(Map<String, ?> before, Map<String, ?> after) {
        Set<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        keys.removeIf(key -> java.util.Objects.equals(before.get(key), after.get(key)));
        return keys;
    }

    private static void assertChanged(Map<String, ?> before, OraclePreferences store, String... keys) {
        assertEquals(new TreeSet<>(Arrays.asList(keys)), changed(before, store.snapshot()));
    }

    private static void assertMarkerInInterval(OraclePreferences store, long start, long end) {
        Object marker = store.snapshot().get(MARKER);
        assertTrue("marker is a String", marker instanceof String);
        long timestamp = Long.parseLong((String) marker);
        assertTrue("marker is wall-clock milliseconds", timestamp >= start && timestamp <= end);
    }

    @Test public void headerAndCookieLoginWriteMarkerAndRemoveOldFrontend() throws Exception {
        for (boolean cookie : new boolean[]{false, true}) {
            OraclePreferences store = new OraclePreferences(Collections.singletonMap(OLD_FRONTEND, "old"));
            Map<String, Object> before = store.snapshot();
            try (Legacy054Harness legacy = new Legacy054Harness(store.context)) {
                legacy.build(server.url("/").toString(), cookie ? "cookie" : "header");
                server.enqueue(response(cookie, "owner-b"));
                long start = System.currentTimeMillis();
                legacy.request(server.url("/login").toString());
                assertMarkerInInterval(store, start, System.currentTimeMillis());
                assertChanged(before, store, cookie ? new String[]{FRONT, CSRF, MARKER, OLD_FRONTEND}
                        : new String[]{ACCESS, REFRESH, FRONT, CSRF, MARKER, OLD_FRONTEND});
                assertEquals(cookie ? 4 : 6, store.writes.size());
                assertEquals("owner-b", legacy.invoke("getUserId"));
                System.out.println("ORACLE login " + (cookie ? "cookie" : "header") + " writes=" + store.writes);
            }
        }
    }

    @Test public void headerAndCookieRefreshUpdateMarkerAndKeepUnmentionedMetadata() throws Exception {
        for (boolean cookie : new boolean[]{false, true}) {
            Map<String, Object> initial = session();
            if (cookie) { initial.remove(ACCESS); initial.remove(REFRESH); }
            initial.put("unrelated-preference", 7);
            OraclePreferences store = new OraclePreferences(initial);
            try (Legacy054Harness legacy = new Legacy054Harness(store.context)) {
                legacy.build(server.url("/").toString(), cookie ? "cookie" : "header");
                server.enqueue(response(cookie, "owner-b"));
                long start = System.currentTimeMillis();
                assertEquals(true, legacy.invoke("refresh"));
                assertMarkerInInterval(store, start, System.currentTimeMillis());
                assertChanged(initial, store, cookie ? new String[]{FRONT, CSRF, MARKER, OLD_FRONTEND}
                        : new String[]{ACCESS, REFRESH, FRONT, CSRF, MARKER, OLD_FRONTEND});
                assertEquals(cookie ? 4 : 6, store.writes.size());
                assertEquals(7, store.snapshot().get("unrelated-preference"));
                System.out.println("ORACLE refresh " + (cookie ? "cookie" : "header") + " writes=" + store.writes);
            }
        }
    }

    @Test public void signOutOnlyClearsWhenResponseSuppliesRemovalHeaderAndKeepsMarker() throws Exception {
        for (boolean removal : new boolean[]{false, true}) {
            Map<String, Object> initial = session();
            OraclePreferences store = new OraclePreferences(initial);
            try (Legacy054Harness legacy = new Legacy054Harness(store.context)) {
                legacy.build(server.url("/").toString(), "header");
                MockResponse response = new MockResponse().setBody("{\"status\":\"OK\"}");
                if (removal) response.addHeader("front-token", "remove");
                server.enqueue(response);
                long start = System.currentTimeMillis();
                legacy.invoke("signOut");
                assertEquals(!removal, legacy.invoke("doesSessionExist"));
                if (removal) {
                    assertMarkerInInterval(store, start, System.currentTimeMillis());
                    assertChanged(initial, store, ACCESS, REFRESH, FRONT, CSRF, MARKER, OLD_FRONTEND);
                    assertEquals(Collections.singleton(MARKER), store.snapshot().keySet());
                    assertEquals(6, store.writes.size());
                } else {
                    assertTrue(initial.equals(store.snapshot()));
                    assertTrue(store.writes.isEmpty());
                }
                System.out.println("ORACLE signout removal=" + removal + " writes=" + store.writes);
            }
        }
    }

    @Test public void rawFrontAndAccessWritesCanChangeTupleWithoutChangingMarker() throws Exception {
        Map<String, Object> initial = session();
        OraclePreferences store = new OraclePreferences(initial);
        try (Legacy054Harness legacy = new Legacy054Harness(store.context)) {
            legacy.build(server.url("/").toString(), "header");
            legacy.invoke("setFrontToken", new Class<?>[]{String.class}, front("owner-b", 4102444800000L));
            legacy.invoke("store", new Class<?>[]{String.class, String.class}, "st-access-token", "opaque-b");
            assertEquals(initial.get(MARKER), store.snapshot().get(MARKER));
            assertChanged(initial, store, FRONT, ACCESS);
            assertEquals("owner-b", legacy.invoke("getUserId"));
            assertEquals(2, store.writes.size());
            System.out.println("ORACLE public raw setters writes=" + store.writes);
        }
    }

    @Test public void accessOnlyResponseDoesNotAdvanceMarker() throws Exception {
        Map<String, Object> initial = session();
        OraclePreferences store = new OraclePreferences(initial);
        try (Legacy054Harness legacy = new Legacy054Harness(store.context)) {
            legacy.build(server.url("/").toString(), "header");
            server.enqueue(new MockResponse().setBody("ok").addHeader("st-access-token", "opaque-b"));
            legacy.request(server.url("/update").toString());
            assertEquals(initial.get(MARKER), store.snapshot().get(MARKER));
            assertChanged(initial, store, ACCESS);
            assertEquals(1, store.writes.size());
            System.out.println("ORACLE access-only response writes=" + store.writes);
        }
    }

    @Test public void exposedAccessGetterDoesNotImplyAuthorizationAttachment() throws Exception {
        for (boolean missingAccess : new boolean[]{false, true}) {
            Map<String, Object> initial = session();
            initial.remove(missingAccess ? ACCESS : REFRESH);
            OraclePreferences store = new OraclePreferences(initial);
            try (Legacy054Harness legacy = new Legacy054Harness(store.context)) {
                legacy.build(server.url("/").toString(), "header");
                assertEquals(true, legacy.invoke("doesSessionExist"));
                Object token = legacy.invoke("getAccessToken");
                assertTrue("legacy getter returns the stored access value", java.util.Objects.equals(initial.get(ACCESS), token));
                server.enqueue(new MockResponse().setBody("ok"));
                legacy.request(server.url("/protected").toString());
                RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
                assertNotNull(request);
                assertNull(request.getHeader("Authorization"));
                assertTrue(initial.equals(store.snapshot()));
                assertTrue(store.writes.isEmpty());
            }
        }
    }

    @Test public void markerClockProbeRecordsCollisionsWithoutUsingTimingAsAcceptanceGate() throws Exception {
        OraclePreferences store = new OraclePreferences(session());
        try (Legacy054Harness legacy = new Legacy054Harness(store.context)) {
            legacy.build(server.url("/").toString(), "header");
            Map<?, ?> probe = (Map<?, ?>) legacy.invoke("probeMarkerCollision", new Class<?>[]{String.class, String.class},
                    front("owner-a", 4102444800000L), front("owner-b", 4102444800000L));
            long first = Long.parseLong((String) probe.get("firstMarker"));
            long second = Long.parseLong((String) probe.get("secondMarker"));
            assertTrue(first >= (Long) probe.get("start") && first <= (Long) probe.get("end"));
            assertTrue(second >= (Long) probe.get("start") && second <= (Long) probe.get("end"));
            assertEquals(first == second, probe.get("collision"));
            assertEquals("owner-b", legacy.invoke("getUserId"));
            // A real wall clock cannot be pinned here; raw setters separately prove marker insufficiency deterministically.
            System.out.println("ORACLE clock observation collision=" + probe.get("collision") + " attempts=" + probe.get("attempts"));
        }
    }

    @Test public void expiredFrontRefreshesInsteadOfBeingDeletedAtStartup() throws Exception {
        for (boolean rejected : new boolean[]{false, true}) {
            Map<String, Object> initial = session();
            initial.put(FRONT, front("owner-a", 1));
            OraclePreferences store = new OraclePreferences(initial);
            try (Legacy054Harness legacy = new Legacy054Harness(store.context)) {
                legacy.build(server.url("/").toString(), "header");
                assertTrue(initial.equals(store.snapshot()));
                assertEquals("owner-a", legacy.invoke("getUserId"));
                server.enqueue(rejected ? new MockResponse().setResponseCode(401).addHeader("front-token", "remove")
                        : response(false, "owner-b"));
                assertEquals(!rejected, legacy.invoke("doesSessionExist"));
                if (rejected) assertEquals(Collections.singleton(MARKER), store.snapshot().keySet());
                else assertEquals("owner-b", legacy.invoke("getUserId"));
                System.out.println("ORACLE expired refresh rejected=" + rejected + " diff=" + diff(initial, store.snapshot()));
            }
        }
    }

    @Test public void publishedWritesPreserveSchemaOneMetadataAcrossRollback() throws Exception {
        for (String operation : new String[]{"login", "cookie-login", "refresh", "same-user-refresh", "signout"}) {
            SuperTokens.resetForTests();
            OraclePreferences store = new OraclePreferences(Collections.emptyMap());
            String domain = server.url("/").toString();
            new SuperTokens.Builder(store.context, domain).tokenTransferMethod("header").build();
            SessionCommitResult install = SuperTokens.installSession(store.context,
                    SuperTokens.getSessionSnapshot(store.context).getOwner(),
                    new SessionTokens(jwt("owner-a", 5), "refresh-a", front("owner-a", 4102444800000L), "csrf-a"));
            assertEquals(SessionCommitResult.Status.SUCCESS, install.getStatus());
            Map<String, Object> metadata = metadata(store.snapshot());
            assertEquals("1", metadata.get("supertokens-android-session-schema"));
            SuperTokens.resetForTests();
            Map<String, Object> before = store.snapshot();
            store.writes.clear();
            try (Legacy054Harness legacy = new Legacy054Harness(store.context)) {
                legacy.build(domain, operation.equals("cookie-login") ? "cookie" : "header");
                if (operation.equals("signout")) {
                    server.enqueue(new MockResponse().setBody("{\"status\":\"OK\"}").addHeader("front-token", "remove"));
                    legacy.invoke("signOut");
                } else {
                    String user = operation.equals("same-user-refresh") ? "owner-a" : "owner-b";
                    MockResponse response = response(operation.equals("cookie-login"), user);
                    if (operation.equals("cookie-login")) response.addHeader("st-access-token", "").addHeader("st-refresh-token", "");
                    server.enqueue(response);
                    if (operation.endsWith("refresh")) assertEquals(true, legacy.invoke("refresh"));
                    else legacy.request(server.url("/login").toString());
                    assertEquals(user, legacy.invoke("getUserId"));
                }
                assertTrue("old SDK modified schema-1 metadata", metadata.equals(metadata(store.snapshot())));
                System.out.println("ORACLE rollback " + operation + " metadataUnchanged=true diff=" + diff(before, store.snapshot())
                        + " writes=" + store.writes);
            }
            for (int restart = 0; restart < 3; restart++) {
                SuperTokens.resetForTests();
                new SuperTokens.Builder(store.context, domain).tokenTransferMethod("header").build();
                assertEquals(operation, operation.equals("signout") ? "ABSENT" : "AVAILABLE",
                        SuperTokens.getSessionSnapshot(store.context).getStatus().name());
                if (!operation.equals("signout")) {
                    assertEquals(operation.equals("same-user-refresh") ? "owner-a" : "owner-b", SuperTokens.getUserId(store.context));
                }
            }
        }
    }

    private static Map<String, Object> metadata(Map<String, Object> values) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        values.forEach((key, value) -> { if (key.startsWith("supertokens-android-session-")) metadata.put(key, value); });
        return metadata;
    }

    @Test public void publishedFrontOnlyEditWithUnchangedMarkerIsRecoveredAcrossTwoRestarts() throws Exception {
        OraclePreferences store = new OraclePreferences(Collections.emptyMap());
        String domain = server.url("/").toString();
        new SuperTokens.Builder(store.context, domain).tokenTransferMethod("header").build();
        assertEquals(SessionCommitResult.Status.SUCCESS, SuperTokens.installSession(store.context,
                SuperTokens.getSessionSnapshot(store.context).getOwner(),
                new SessionTokens(jwt("owner-a", 5), "refresh-a", front("owner-a", 4102444800000L), "csrf-a")).getStatus());
        Map<String, Object> before = store.snapshot();
        SuperTokens.resetForTests();
        try (Legacy054Harness legacy = new Legacy054Harness(store.context)) {
            legacy.build(domain, "header");
            legacy.invoke("setFrontToken", new Class<?>[]{String.class}, front("owner-b", 4102444800000L));
            assertEquals(before.get(MARKER), store.snapshot().get(MARKER));
            assertTrue(metadata(before).equals(metadata(store.snapshot())));
            assertEquals("owner-b", legacy.invoke("getUserId"));
        }
        for (int restart = 0; restart < 3; restart++) {
            SuperTokens.resetForTests();
            new SuperTokens.Builder(store.context, domain).tokenTransferMethod("header").build();
            assertEquals("AVAILABLE", SuperTokens.getSessionSnapshot(store.context).getStatus().name());
            assertEquals("owner-b", SuperTokens.getUserId(store.context));
            assertEquals(before.get(MARKER), store.snapshot().get(MARKER));
        }
    }

    @Test public void publishedLoginReplacesAbsentAndRecoveryMetadataAcrossTwoRestarts() throws Exception {
        for (String status : new String[]{"absent", "recovery"}) {
            SuperTokens.resetForTests();
            OraclePreferences store = new OraclePreferences(Collections.emptyMap());
            String domain = server.url("/").toString();
            new SuperTokens.Builder(store.context, domain).tokenTransferMethod("header").build();
            // Empty initialization is read-only; this case needs a complete durable metadata record.
            assertEquals(SessionCommitResult.Status.SUCCESS, SuperTokens.clearSession(store.context,
                    SuperTokens.getSessionSnapshot(store.context).getOwner()).getStatus());
            store.values.put("supertokens-android-session-status", status);
            Map<String, Object> before = metadata(store.snapshot());
            SuperTokens.resetForTests();
            try (Legacy054Harness legacy = new Legacy054Harness(store.context)) {
                legacy.build(domain, "header");
                server.enqueue(response(false, "owner-b"));
                legacy.request(server.url("/login").toString());
                assertTrue(before.equals(metadata(store.snapshot())));
                assertEquals("owner-b", legacy.invoke("getUserId"));
            }
            for (int restart = 0; restart < 3; restart++) {
                SuperTokens.resetForTests();
                new SuperTokens.Builder(store.context, domain).tokenTransferMethod("header").build();
                assertEquals(status, "AVAILABLE", SuperTokens.getSessionSnapshot(store.context).getStatus().name());
                assertEquals("owner-b", SuperTokens.getUserId(store.context));
            }
        }
    }
}
