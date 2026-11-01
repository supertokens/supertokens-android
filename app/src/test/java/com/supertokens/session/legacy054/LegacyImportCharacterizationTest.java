package com.supertokens.session.legacy054;

import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.SuperTokens;
import java.net.CookieHandler;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;
import static com.supertokens.session.legacy054.OraclePreferences.*;

/** Published getter oracle plus durable candidate import acceptance (M1 / approved 2026-10-29 recovery policy). */
@RunWith(Parameterized.class)
public class LegacyImportCharacterizationTest {
    @Parameterized.Parameters(name = "{0}") public static Collection<Object[]> rows() {
        return Arrays.asList(new Object[][]{
                {"header-v2"}, {"header-v3"}, {"header-v4"}, {"header-v5"},
                {"cookie"}, {"front-only"}, {"cookie-exposed-access"}, {"header-missing-refresh"},
                {"front-no-marker"}, {"cookie-front-no-marker"}, {"marker-no-front"},
                {"access-refresh-no-front"}, {"refresh-front-no-access"}, {"opaque-access"},
                {"mismatched-uid"}, {"expired-front"}, {"header-expired"}, {"malformed-base64"}, {"malformed-json"},
                {"missing-ate"}, {"missing-uid"}, {"stale-sIRTFrontend"}, {"sIRTFrontend-only"}
        });
    }

    private final String row;
    public LegacyImportCharacterizationTest(String row) { this.row = row; }

    static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static String front(String user, long expiry) throws Exception {
        return encode(new JSONObject().put("uid", user).put("ate", expiry)
                .put("up", new JSONObject().put("claim", user)).toString());
    }

    static String jwt(String user, int version) throws Exception {
        return jwt(user, version, 4102444800000L);
    }

    // Structurally versioned synthetic tokens; these do not establish live Core/FDI coverage.
    private static String jwt(String user, int version, long expiry) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = new JSONObject().put("alg", "RS256").put("typ", "JWT").put("version", "" + version).toString();
        JSONObject payload = new JSONObject().put("sessionHandle", "handle");
        if (version == 2) payload.put("userId", user).put("expiryTime", expiry)
                .put("timeCreated", 1700000000000L).put("userData", new JSONObject());
        else payload.put("sub", user).put("exp", expiry / 1000).put("iat", 1700000000L);
        if (version >= 4) payload.put("tId", "public");
        if (version >= 5) payload.put("rsub", user);
        String body = payload.toString();
        return encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(body.getBytes(StandardCharsets.UTF_8)) + ".c2ln";
    }

    private Map<String, Object> seed() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(FRONT, front("front-user", 4102444800000L));
        values.put(MARKER, "1");
        values.put(CSRF, "csrf-fixture");
        values.put(ACCESS, jwt("front-user", 5));
        values.put(REFRESH, "refresh-fixture");
        if (row.startsWith("header-v")) values.put(ACCESS, jwt("front-user", Integer.parseInt(row.substring(8))));
        switch (row) {
            case "cookie": values.remove(ACCESS); values.remove(REFRESH); break;
            case "front-only": values.remove(ACCESS); values.remove(REFRESH); values.remove(CSRF); break;
            case "cookie-exposed-access": values.remove(REFRESH); break;
            case "header-missing-refresh": values.remove(REFRESH); break;
            case "front-no-marker": values.remove(MARKER); break;
            case "cookie-front-no-marker": values.remove(MARKER); values.remove(ACCESS); values.remove(REFRESH); break;
            case "marker-no-front": values.remove(FRONT); values.remove(ACCESS); values.remove(REFRESH); break;
            case "access-refresh-no-front": values.remove(FRONT); break;
            case "refresh-front-no-access": values.remove(ACCESS); break;
            case "opaque-access": values.put(ACCESS, "opaque-fixture"); break;
            case "mismatched-uid": values.put(ACCESS, jwt("jwt-user", 5)); break;
            case "expired-front": values.put(FRONT, front("front-user", 1)); break;
            case "header-expired": values.put(FRONT, front("front-user", 1)); values.put(ACCESS, jwt("front-user", 5, 1000)); break;
            case "malformed-base64": values.put(FRONT, "A"); break;
            case "malformed-json": values.put(FRONT, encode("not-json")); break;
            case "missing-ate": values.put(FRONT, encode("{\"uid\":\"front-user\",\"up\":{}}")); break;
            case "missing-uid": values.put(FRONT, encode("{\"ate\":4102444800000,\"up\":{}}")); break;
            case "stale-sIRTFrontend": values.put(OLD_FRONTEND, "stale-fixture"); break;
            case "sIRTFrontend-only": values.clear(); values.put(OLD_FRONTEND, "stale-fixture"); break;
            default: break;
        }
        return values;
    }

    private String expected(String method) {
        if (row.equals("malformed-base64")) return "throws:java.lang.IllegalArgumentException";
        if (row.equals("malformed-json")) return "throws:java.lang.IllegalStateException";
        boolean absent = row.endsWith("front-no-marker") || row.equals("marker-no-front")
                || row.equals("access-refresh-no-front") || row.equals("sIRTFrontend-only");
        if (method.equals("getUserId")) {
            return absent || row.equals("missing-uid") ? "throws:java.lang.IllegalStateException" : "front-user";
        }
        boolean exists = !absent && !expired() && !row.equals("missing-ate");
        if (method.equals("doesSessionExist")) return "" + exists;
        return exists && !row.equals("cookie") && !row.equals("front-only") && !row.equals("refresh-front-no-access") ? "seed-access" : "null";
    }

    private boolean expired() { return row.equals("expired-front") || row.equals("header-expired"); }

    interface Read { Object get() throws Exception; }
    private static String outcome(String method, Object seededAccess, Read read) {
        try {
            Object value = read.get();
            if (method.equals("getAccessToken") && value != null) return value.equals(seededAccess) ? "seed-access" : "other-access";
            return String.valueOf(value);
        } catch (Exception error) { return "throws:" + error.getClass().getName(); }
    }

    @Test public void publishedGetterMatrixAndDurableCandidateImports() throws Exception {
        CookieHandler previous = CookieHandler.getDefault();
        CookieHandler.setDefault(null);
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) { return new MockResponse().setResponseCode(500); }
            });
            server.start();
            for (boolean initialized : new boolean[]{false, true}) {
                for (String method : new String[]{"doesSessionExist", "getUserId", "getAccessToken"}) {
                    Map<String, Object> initial = seed();
                    OraclePreferences store = new OraclePreferences(initial);
                    int requestsBefore = server.getRequestCount();
                    try (Legacy054Harness legacy = new Legacy054Harness(store.context)) {
                        if (initialized) legacy.build(server.url("/").toString(), row.startsWith("cookie") ? "cookie" : "header");
                        String actual = outcome(method, initial.get(ACCESS), () -> legacy.invoke(method));
                        assertEquals(row + " " + method + " initialized=" + initialized, expected(method), actual);
                        System.out.println("ORACLE GETTER " + row + " initialized=" + initialized + " " + method + "=" + actual);
                        assertTrue("getter changed preferences: " + diff(initial, store.snapshot()), initial.equals(store.snapshot()));
                        int expectedRequests = initialized && expired() && !method.equals("getUserId") ? 1 : 0;
                        assertEquals(expectedRequests, server.getRequestCount() - requestsBefore);
                        if (initialized) compareCandidate(server, initial, method, actual);
                    }
                }
            }
        } finally {
            SuperTokens.resetForTests();
            CookieHandler.setDefault(previous);
        }
    }

    private void compareCandidate(MockWebServer server, Map<String, Object> initial, String method, String oracle) throws Exception {
        SuperTokens.resetForTests();
        OraclePreferences candidate = new OraclePreferences(initial);
        try {
            new SuperTokens.Builder(candidate.context, server.url("/").toString())
                    .tokenTransferMethod(row.startsWith("cookie") ? "cookie" : "header").build();
            boolean unreadable = row.startsWith("malformed-") || row.equals("missing-uid") || row.equals("missing-ate");
            boolean absent = unreadable || row.equals("marker-no-front") || row.equals("access-refresh-no-front") || row.equals("sIRTFrontend-only");
            for (int restart = 0; restart < 3; restart++) {
                assertEquals(row, absent ? "ABSENT" : "AVAILABLE", SuperTokens.getSessionSnapshot(candidate.context).getStatus().name());
                if (!absent) {
                    for (String key : new String[]{ACCESS, REFRESH, FRONT, CSRF}) {
                        assertTrue(row + " preserved " + key, java.util.Objects.equals(initial.get(key), candidate.snapshot().get(key)));
                    }
                }
                if (restart < 2) {
                    SuperTokens.resetForTests();
                    new SuperTokens.Builder(candidate.context, server.url("/").toString())
                            .tokenTransferMethod(row.startsWith("cookie") ? "cookie" : "header").build();
                }
            }
            String actual = outcome(method, initial.get(ACCESS), () -> {
                switch (method) {
                    case "doesSessionExist": return SuperTokens.doesSessionExist(candidate.context);
                    case "getUserId": return SuperTokens.getUserId(candidate.context);
                    default: return SuperTokens.getAccessToken(candidate.context);
                }
            });
            String expected = oracle;
            // Approved improvements: recover readable missing-marker data; expose unreadable front data as ABSENT.
            if (row.endsWith("front-no-marker")) expected = method.equals("doesSessionExist") ? "true"
                    : method.equals("getUserId") ? "front-user" : initial.get(ACCESS) == null ? "null" : "seed-access";
            if (unreadable) expected = method.equals("doesSessionExist") ? "false"
                    : method.equals("getUserId") ? "throws:java.lang.IllegalStateException" : "null";
            assertEquals(row + " " + method, expected, actual);
        } finally { SuperTokens.resetForTests(); }
    }
}
