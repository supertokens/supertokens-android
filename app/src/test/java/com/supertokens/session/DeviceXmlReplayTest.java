package com.supertokens.session;

import android.content.Context;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(Parameterized.class)
public class DeviceXmlReplayTest {
    private static final String SESSION = "supertokens-android-shared-preferences";
    private static final String COOKIES = "supertokens-cookie-shared-prefs";
    private static final String ACCESS = "st-storage-item-st-access-token";
    private static final String REFRESH = "st-storage-item-st-refresh-token";
    private static final String FRONT = "supertokens-android-fronttoken-key";
    private final JSONObject row;
    private Context context;
    private TestPreferences session;
    private TestPreferences cookiePreferences;
    private Map<String, Object> raw;
    private CookieHandler previous;
    private final List<EventHandler.EventType> events = new ArrayList<>();

    public DeviceXmlReplayTest(String name, JSONObject row) { this.row = row; }

    @Parameterized.Parameters(name="{0}") public static Collection<Object[]> captures() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        try (InputStream input = resource("capture-provenance.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            JSONArray cases = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).getJSONArray("cases");
            for (int i = 0; i < cases.length(); i++) {
                JSONObject row = cases.getJSONObject(i);
                rows.add(new Object[]{row.getString("scenario") + "/" + row.getString("phase"), row});
            }
        }
        return rows;
    }

    private static InputStream resource(String name) {
        InputStream input = DeviceXmlReplayTest.class.getResourceAsStream("/upgrade/" + name);
        assertNotNull("Missing replay resource", input);
        return input;
    }

    private Map<String, Object> load(String name) throws Exception {
        try (InputStream input = resource(row.getString("folder") + "/" + name + ".xml")) {
            return TypedPreferencesXml.read(input);
        }
    }

    @Before public void setup() throws Exception {
        SuperTokens.resetForTests();
        previous = CookieHandler.getDefault();
        context = mock(Context.class);
        session = new TestPreferences();
        cookiePreferences = new TestPreferences();
        raw = load(SESSION);
        session.seed(raw);
        if (row.getString("scenario").equals("cookie")) cookiePreferences.seed(load(COOKIES));
        when(context.getSharedPreferences(SESSION, 0)).thenReturn(session.preferences);
        when(context.getSharedPreferences(COOKIES, 0)).thenReturn(cookiePreferences.preferences);
    }

    @After public void cleanup() {
        SuperTokens.resetForTests();
        CookieHandler.setDefault(previous);
    }

    private void initialize() throws Exception {
        CookieHandler.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(context), CookiePolicy.ACCEPT_ALL));
        new SuperTokens.Builder(context, "http://10.0.2.2:8080")
                .tokenTransferMethod(row.getString("scenario").equals("cookie") ? "cookie" : "header")
                .eventHandler(events::add).build();
    }

    private void restart() throws Exception {
        SuperTokens.resetForTests();
        session.restart();
        cookiePreferences.restart();
        initialize();
    }

    private void assertState() throws Exception {
        SessionSnapshot snapshot = SuperTokens.getSessionSnapshot(context);
        assertEquals(row.getBoolean("available") ? SessionSnapshot.Status.AVAILABLE : SessionSnapshot.Status.ABSENT, snapshot.getStatus());
        assertEquals(row.getString("expectedValidation"), session.preferences.getString(SharedPreferencesSessionStorage.VALIDATION, null));
        if (row.getBoolean("available")) {
            assertEquals(row.getString("userId"), snapshot.getUserId());
            assertEquals(row.getLong("expiry"), snapshot.getAccessTokenExpiry());
            assertEquals(row.getString("userId"), SuperTokens.getUserId(context));
            SessionTokens tuple = snapshot.getInternalTokens();
            assertTrue("Front tuple changed", raw.get(FRONT).equals(tuple.getFrontToken()));
            assertTrue("Access tuple changed", java.util.Objects.equals(raw.get(ACCESS), tuple.getAccessToken()));
            assertTrue("Refresh tuple changed", java.util.Objects.equals(raw.get(REFRESH), tuple.getRefreshToken()));
            JSONObject front = snapshot.getFrontToken();
            assertEquals(row.getString("userId"), front.getString("uid"));
            assertEquals(front.getLong("ate"), front.getJSONObject("up").getLong("exp") * 1000);
            assertTrue(front.getJSONObject("up").isNull("antiCsrfToken"));
            assertTrue(front.getJSONObject("up").isNull("parentRefreshTokenHash1"));
            if (raw.containsKey(ACCESS)) {
                String[] jwt = ((String) raw.get(ACCESS)).split("\\.");
                JSONObject header = new JSONObject(new String(Base64.getUrlDecoder().decode(jwt[0]), StandardCharsets.UTF_8));
                assertEquals(row.getString("tokenVersion"), header.getString("version"));
                assertEquals(row.getString("handle"), snapshot.getSessionHandle());
            } else {
                assertTrue("Cookie tuple exported header credentials", snapshot.getTokens() == null);
                List<HttpCookie> cookies = new SuperTokensPersistentCookieStore(context).getCookies();
                // getCookies includes the root-path access cookie again for the refresh URI.
                assertEquals(2, new java.util.HashSet<>(cookies).size());
                for (HttpCookie cookie : cookies) {
                    if (cookie.getName().equals("sAccessToken")) {
                        JSONObject header = new JSONObject(new String(Base64.getUrlDecoder().decode(cookie.getValue().split("\\.")[0]), StandardCharsets.UTF_8));
                        assertEquals(row.getString("tokenVersion"), header.getString("version"));
                        JSONObject payload = new JSONObject(new String(Base64.getUrlDecoder().decode(cookie.getValue().split("\\.")[1]), StandardCharsets.UTF_8));
                        assertEquals(row.getString("userId"), payload.getString("sub"));
                        assertEquals(front.getLong("ate"), payload.getLong("exp") * 1000);
                    } else assertTrue("Refresh cookie was not invalidated", cookie.getValue().startsWith("INVALID-FIXTURE-SECRET-"));
                }
            }
        } else {
            assertFalse(SuperTokens.doesSessionExist(context));
            assertTrue("Absent snapshot exposes tokens", snapshot.getTokens() == null);
            assertFalse(session.preferences.contains(ACCESS));
            assertFalse(session.preferences.contains(REFRESH));
        }
        assertTrue("Startup/replay must not emit lifecycle events", events.isEmpty());
    }

    @Test public void typedCaptureImportsOnceAndSurvivesTwoDurableXmlRestarts() throws Exception {
        initialize();
        assertState();
        assertEquals(row.getInt("expectedInitialWrites"), session.commits);
        assertEquals("Initialization must not apply preferences", 0, session.applies);
        SessionSnapshot first = SuperTokens.getSessionSnapshot(context);
        if (row.getString("originalEvidence").equals("coherent")) {
            assertEquals(raw.get(SharedPreferencesSessionStorage.ADOPTION), first.getAdoptionId());
            assertEquals(Long.parseLong((String) raw.get(SharedPreferencesSessionStorage.REVISION)), first.getRevision());
            assertEquals(Long.parseLong((String) raw.get(SharedPreferencesSessionStorage.ACCESS_REVISION)), first.getAccessTokenRevision());
            assertEquals(Long.parseLong((String) raw.get(SharedPreferencesSessionStorage.REFRESH_REVISION)), first.getRefreshTokenRevision());
        }
        for (int i = 0; i < 2; i++) {
            int commits = session.commits;
            int applies = session.applies;
            Map<String, Object> before = session.durableValues();
            ByteArrayOutputStream xml = new ByteArrayOutputStream();
            TypedPreferencesXml.write(before, xml);
            Map<String, Object> parsed = TypedPreferencesXml.read(new ByteArrayInputStream(xml.toByteArray()));
            assertEquals(before, parsed);
            session.seed(parsed);
            restart();
            assertState();
            SessionSnapshot next = SuperTokens.getSessionSnapshot(context);
            assertEquals(first.getAdoptionId(), next.getAdoptionId());
            assertEquals(first.getConfigurationId(), next.getConfigurationId());
            assertEquals(first.getRevision(), next.getRevision());
            assertEquals(first.getAccessTokenRevision(), next.getAccessTokenRevision());
            assertEquals(first.getRefreshTokenRevision(), next.getRefreshTokenRevision());
            assertEquals(commits, session.commits);
            assertEquals(applies, session.applies);
        }
    }

    @Test public void allCapturedFilesRetainDeclaredTypesAndRoundTrip() throws Exception {
        JSONObject files = row.getJSONObject("files");
        for (java.util.Iterator<String> names = files.keys(); names.hasNext();) {
            String name = names.next();
            Map<String, Object> values = load(name);
            JSONObject types = files.getJSONObject(name).getJSONObject("types");
            java.util.Set<String> declaredKeys = new java.util.HashSet<>();
            for (java.util.Iterator<String> keys = types.keys(); keys.hasNext();) declaredKeys.add(keys.next());
            assertEquals(declaredKeys, values.keySet());
            for (String key : values.keySet()) {
                String type = types.getString(key);
                if (type.equals("string")) assertTrue(values.get(key) instanceof String);
                else if (type.equals("boolean")) assertTrue(values.get(key) instanceof Boolean);
                else fail("New actual capture type needs explicit coverage");
            }
            ByteArrayOutputStream xml = new ByteArrayOutputStream();
            TypedPreferencesXml.write(values, xml);
            assertEquals(values, TypedPreferencesXml.read(new ByteArrayInputStream(xml.toByteArray())));
        }
    }

    @Test public void invalidStrictInstallDoesNotReplaceImportedTupleOrEmitEvents() throws Exception {
        initialize();
        SessionSnapshot before = SuperTokens.getSessionSnapshot(context);
        String front = (String) raw.get(FRONT);
        if (front == null) {
            try (InputStream input = resource("0.5.4/login-b/seed/" + SESSION + ".xml")) {
                front = (String) TypedPreferencesXml.read(input).get(FRONT);
            }
        }
        SessionCommitResult result = SuperTokens.installSession(context, before.getOwner(), new SessionTokens("invalid-access", "invalid-refresh", front, null));
        assertEquals(SessionCommitResult.Status.INVALID_SESSION, result.getStatus());
        assertSame(before, SuperTokens.getSessionSnapshot(context));
        assertTrue(events.isEmpty());
    }

    @Test public void coherentFingerprintDoesNotExcuseCorruptVersionedMetadata() throws Exception {
        initialize();
        assertTrue(session.preferences.edit().putString(SharedPreferencesSessionStorage.REVISION, "-1").commit());
        restart();
        assertEquals(SessionSnapshot.Status.RECOVERY_REQUIRED, SuperTokens.getSessionSnapshot(context).getStatus());
        assertTrue("Recovery must not expose tokens", SuperTokens.getSessionSnapshot(context).getTokens() == null);
        assertTrue("Recovery startup must not fabricate lifecycle events", events.isEmpty());
    }
}
