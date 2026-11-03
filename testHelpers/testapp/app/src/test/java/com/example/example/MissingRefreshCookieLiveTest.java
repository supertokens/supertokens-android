package com.example.example;

import com.supertokens.session.EventHandler;
import com.supertokens.session.SessionSnapshot;
import com.supertokens.session.SuperTokens;
import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class MissingRefreshCookieLiveTest {
    @Parameterized.Parameters(name = "urlConnection={0}")
    public static Collection<Object[]> cases() { return Arrays.asList(new Object[][]{{false}, {true}}); }
    private final boolean urlConnection;
    private LiveFixture fixture;
    private final java.util.concurrent.atomic.AtomicReference<String> marker = new java.util.concurrent.atomic.AtomicReference<>();
    private String generation;

    public MissingRefreshCookieLiveTest(boolean urlConnection) { this.urlConnection = urlConnection; }

    private static String randomHex() {
        byte[] bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }

    private com.google.gson.JsonObject observationCall(String action) throws Exception {
        String node = System.getenv("ST_ARTIFACT_NODE");
        String script = System.getenv("INSTALL_PATH") + "/backend/refreshObservationClient.js";
        Process process = new ProcessBuilder(node, script, action).start();
        try (java.io.OutputStream output = process.getOutputStream()) {
            if (generation != null) output.write(generation.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        byte[] bytes;
        try (java.io.InputStream input = process.getInputStream()) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int count;
            while ((count = input.read(chunk)) != -1) {
                if (buffer.size() + count > 65536) throw new IOException("observation response too large");
                buffer.write(chunk, 0, count);
            }
            bytes = buffer.toByteArray();
        }
        assertEquals(0, process.waitFor());
        return new com.google.gson.JsonParser().parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private void armObservation() throws Exception {
        generation = observationCall("arm").get("generation").getAsString();
        marker.set(generation);
    }

    @BeforeClass public static void start() { com.example.TestUtils.beforeAll(); }
    @AfterClass public static void stop() { com.example.TestUtils.afterAll(); }
    @Before public void setup() throws Exception {
        fixture = new LiveFixture("cookie", urlConnection, type -> type == com.supertokens.session.CustomHeaderProvider.RequestType.REFRESH
                ? Collections.singletonMap("x-st-test-case", marker.get()) : Collections.emptyMap());
    }
    @After public void cleanup() { if (fixture != null) fixture.close(); }

    @Test public void missingRefreshCookieExplicitRemovalClearsSession() throws Exception {
        assertEquals(200, fixture.login("missing-refresh"));
        SessionSnapshot before = SuperTokens.getSessionSnapshot(fixture.context);
        assertEquals(SessionSnapshot.Status.AVAILABLE, before.getStatus());
        boolean removed = false;
        for (URI uri : new ArrayList<>(fixture.cookies.getCookieStore().getURIs())) {
            for (HttpCookie cookie : new ArrayList<>(fixture.cookies.getCookieStore().getCookies())) {
                if (cookie.getName().equals("sRefreshToken")) {
                    removed |= fixture.cookies.getCookieStore().remove(uri, cookie);
                }
            }
        }
        assertTrue("login must set a refresh cookie", removed);
        fixture.events.clear();
        Thread.sleep(5000);
        armObservation();
        assertEquals(401, fixture.request("/", null));
        com.google.gson.JsonObject observed = observationCall("consume");
        assertEquals(generation, observed.get("generation").getAsString());
        com.google.gson.JsonObject observation = observed.getAsJsonObject("observation");
        assertNotNull("refresh observation required", observation);
        assertFalse(observation.get("refreshCookie").getAsBoolean());
        assertFalse(observation.get("refreshHeader").getAsBoolean());
        assertFalse(observation.get("authorization").getAsBoolean());
        assertEquals(401, observation.get("status").getAsInt());
        assertEquals("remove", observation.get("frontToken").getAsString());
        SessionSnapshot after = SuperTokens.getSessionSnapshot(fixture.context);
        assertEquals(SessionSnapshot.Status.ABSENT, after.getStatus());
        assertEquals(Collections.singletonList(EventHandler.EventType.UNAUTHORISED), fixture.events);
    }
}
