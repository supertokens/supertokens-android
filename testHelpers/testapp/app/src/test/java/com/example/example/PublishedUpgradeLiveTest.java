package com.example.example;

import android.content.Context;
import android.content.SharedPreferences;
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder;
import com.supertokens.testing.PublishedUpgradeScenario;
import com.supertokens.testing.PublishedUpgradeScenario.Flow;
import com.supertokens.testing.PublishedUpgradeScenario.Mode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Uses an externally provisioned matrix member; never starts/stops Core or resets backend recipes. */
@RunWith(Parameterized.class)
public class PublishedUpgradeLiveTest {
    @Parameterized.Parameters(name = "{0}-{1}-{2}") public static Collection<Object[]> rows() {
        Collection<Object[]> rows = new ArrayList<>();
        String selected = System.getenv("ST_UPGRADE_MODE");
        if (selected != null) {
            Mode mode = Mode.valueOf(selected);
            String route = System.getenv("ST_UPGRADE_ROUTE");
            if (route == null) route = "/login";
            if (!(route.equals("/login") || (route.equals("/login-2.18") && mode == Mode.HEADER))) {
                throw new IllegalArgumentException("Invalid owned upgrade route/mode");
            }
            if (!route.equals(System.getProperty("upgrade.loginRoute", "/login"))) {
                throw new IllegalArgumentException("Owned upgrade route/property mismatch");
            }
            for (Flow flow : Flow.values()) rows.add(new Object[]{mode, flow, route});
            return rows;
        }
        for (Mode mode : Mode.values()) for (Flow flow : Flow.values()) rows.add(new Object[]{mode, flow, "/login"});
        for (Flow flow : Flow.values()) rows.add(new Object[]{Mode.HEADER, flow, "/login-2.18"});
        return rows;
    }
    private final Mode mode;
    private final Flow flow;
    private final String login;
    public PublishedUpgradeLiveTest(Mode mode, Flow flow, String login) {
        this.mode = mode; this.flow = flow; this.login = login;
    }

    @Test public void publishedSessionContinuesAgainstConfiguredFdiMember() throws Exception {
        String domain = System.getProperty("upgrade.baseUrl");
        Assume.assumeTrue("No externally provisioned live upgrade member selected", domain != null);
        String fdi = System.getProperty("upgrade.fdiVersion");
        assertNotNull("Live matrix requires explicit FDI version", fdi);
        JSONObject capabilities = fetch(domain + "/test/capabilities");
        JSONObject flags = fetch(domain + "/featureFlags");
        assertTrue("Core unavailable", capabilities.getBoolean("coreReachable"));
        assertTrue("Actual Node/Core pair incompatible", capabilities.getBoolean("compatible"));
        String node = capabilities.getString("nodeVersion");
        if (System.getProperty("upgrade.expectedNodeVersion") != null) assertEquals(System.getProperty("upgrade.expectedNodeVersion"), node);
        System.out.println("LIVE_UPGRADE node=" + node + " coreCdi=" + capabilities.getJSONArray("coreCdiVersions")
                + " fdi=" + fdi + " flags=" + flags + " mode=" + mode + " flow=" + flow + " login=" + login);
        if (mode == Mode.HEADER) {
            Assume.assumeTrue("Header transfer requires Node 13+ (published Node changelog)", Integer.parseInt(node.split("\\.")[0]) >= 13);
        }
        boolean exposesCookieAccess = Integer.parseInt(node.split("\\.")[0]) >= 14 && flags.getBoolean("sessionJwt");
        if (mode == Mode.EXPOSED_COOKIE) {
            if (System.getenv("ST_UPGRADE_MODE") != null) assertTrue("Owned exposure not enabled", exposesCookieAccess);
            Assume.assumeTrue("This Node configuration does not enable exposed cookie access tokens",
                    exposesCookieAccess);
        }
        if (mode == Mode.COOKIE) Assume.assumeFalse("Unexposed-cookie row requires cookie access exposure disabled", exposesCookieAccess);
        if (System.getenv("ST_UPGRADE_MODE") != null) {
            assertEquals("Owned configuration mismatch", mode == Mode.EXPOSED_COOKIE, exposesCookieAccess);
            for (String phase : new String[]{"PublishedLogin", "CandidateRefresh"}) {
                assertNotNull("Owned upgrade requires phase version: " + phase, expectedVersion(phase));
            }
            expectedHandoffVersion();
        }
        if (login.equals("/login-2.18")) {
            if (System.getenv("ST_UPGRADE_MODE") != null) {
                // The owned run qualifies the actual route through all four flows, after scoped cleanup.
                assertTrue("Core does not advertise CDI 2.18", contains(capabilities.getJSONArray("coreCdiVersions"), "2.18"));
            } else {
                Assume.assumeTrue("Core does not advertise CDI 2.18", contains(capabilities.getJSONArray("coreCdiVersions"), "2.18"));
                String evidenceFile = System.getProperty("upgrade.legacy218Evidence");
                Assume.assumeTrue("Compatibility route needs prior validated evidence for this pair", evidenceFile != null);
                JSONObject evidence = new JSONObject(new String(Files.readAllBytes(Paths.get(evidenceFile)), StandardCharsets.UTF_8));
                assertEquals("passed", evidence.getString("status"));
                assertEquals("/login-2.18", evidence.getString("route"));
                assertEquals(node, evidence.getString("nodeVersion"));
                assertEquals(fdi, evidence.getString("fdiVersion"));
                assertEquals(capabilities.getJSONArray("coreCdiVersions").toString(), evidence.getJSONArray("coreCdiVersions").toString());
            }
        }
        Context context = mock(Context.class);
        Map<String, SharedPreferences> preferences = new HashMap<>();
        when(context.getSharedPreferences(anyString(), anyInt())).thenAnswer(call ->
                preferences.computeIfAbsent((String) call.getArguments()[0], name -> new SPMockBuilder().createSharedPreferences()));
        assertTrue("Scoped upgrade cleanup requires mapped-owner fixtures", capabilities.getBoolean("mappedGoogleSession"));
        PublishedUpgradeScenario.TokenEvidence observed;
        try (UpgradeFixture fixture = UpgradeFixture.open(domain)) {
            observed = PublishedUpgradeScenario.run(context, mode, flow, new PublishedUpgradeScenario.Routes(
                    domain, login, "/", "/update-jwt", new JSONObject().put("userId", fixture.userId).toString(), fdi), () -> { });
            observed.assertVersions(expectedVersion("PublishedLogin"),
                    expectedHandoffVersion(),
                    expectedVersion("CandidateRefresh"));
        }
        if (System.getenv("ST_UPGRADE_MODE") != null) {
            String reportDirectory = System.getenv("ST_TEST_REPORT_DIR");
            String nonce = System.getenv("ST_ARTIFACT_NONCE");
            String runId = System.getenv("ST_TEST_RUN_ID");
            assertNotNull("Owned upgrade report directory required", reportDirectory);
            assertNotNull("Fresh preparation nonce required", nonce);
            assertNotNull("Owned run ID required", runId);
            // Reached only after published/candidate assertions AND scoped close succeed.
            JSONObject row = new JSONObject().put("schema", 1).put("mode", mode.name())
                    .put("flow", flow.name()).put("route", login).put("fdi", fdi)
                    .put("backend", domain).put("nodeVersion", node)
                    .put("coreCdiVersions", capabilities.getJSONArray("coreCdiVersions"))
                    .put("sessionJwt", flags.getBoolean("sessionJwt"))
                    .put("preparationNonce", nonce).put("runId", runId)
                    .put("publishedFirst", true).put("candidateContinued", true)
                    .put("scopedCleanup", true).put("observed", observed.toJson());
            Path directory = Paths.get(reportDirectory, "upgrade-observations");
            Files.createDirectories(directory);
            Files.write(directory.resolve(flow.name() + ".json"), row.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        System.out.println("LIVE_UPGRADE observations=" + observed.toJson() + " PASS");
    }

    private static String expectedVersion(String phase) {
        return System.getProperty("upgrade.expected" + phase + "Version", System.getProperty("upgrade.expectedTokenVersion"));
    }

    private String expectedHandoffVersion() throws Exception {
        String json = System.getProperty("upgrade.expectedPublishedHandoffVersions");
        if (json == null) {
            assertFalse("Historical route requires explicit per-flow handoff versions", login.equals("/login-2.18"));
            String scalar = expectedVersion("PublishedHandoff");
            if (System.getenv("ST_UPGRADE_MODE") != null) {
                assertNotNull("Owned upgrade requires handoff versions", scalar);
                assertTrue("Invalid scalar handoff version", scalar.matches("[2-5]"));
            }
            return flow == Flow.SIGN_OUT ? PublishedUpgradeScenario.TokenEvidence.ABSENT : scalar;
        }
        JSONObject versions = new JSONObject(json);
        assertEquals("Exact four handoff keys required", Flow.values().length, versions.length());
        for (Flow selected : Flow.values()) {
            Object value = versions.get(selected.name());
            assertTrue("Handoff versions must be strings", value instanceof String);
            assertTrue("Invalid handoff for " + selected, selected == Flow.SIGN_OUT
                    ? value.equals(PublishedUpgradeScenario.TokenEvidence.ABSENT) : ((String) value).matches("[2-5]"));
        }
        return versions.getString(flow.name());
    }

    private static boolean contains(JSONArray values, String target) throws Exception {
        for (int i = 0; i < values.length(); i++) if (target.equals(values.getString(i))) return true;
        return false;
    }

    private static JSONObject fetch(String url) throws Exception {
        OkHttpClient client = new OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build();
        try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
            assertEquals("Missing live capability route " + url, 200, response.code());
            assertNotNull(response.body());
            return new JSONObject(response.body().string());
        } finally { client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow(); }
    }
}
