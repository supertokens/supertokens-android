package com.supertokens.session.legacy054;

import java.util.Base64;
import com.supertokens.session.SessionTestFixture;
import com.supertokens.testing.PublishedUpgradeScenario;
import com.supertokens.testing.PublishedUpgradeScenario.Flow;
import com.supertokens.testing.PublishedUpgradeScenario.Mode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class Legacy054UpgradeTest extends SessionTestFixture {
    @Parameterized.Parameters(name = "{0}-{1}-synthetic-v{2}")
    public static Collection<Object[]> rows() {
        List<Object[]> rows = new ArrayList<>();
        for (Mode mode : Mode.values()) for (Flow flow : Flow.values()) for (int version = 2; version <= 5; version++) {
            rows.add(new Object[]{mode, flow, version});
        }
        return rows;
    }

    private final Mode mode;
    private final Flow flow;
    private final int version;
    public Legacy054UpgradeTest(Mode mode, Flow flow, int version) {
        this.mode = mode; this.flow = flow; this.version = version;
    }
    @Override protected boolean initializeOnSetup() { return false; }

    @Test public void publishedFlowContinuesAfterCandidateRestart() throws Exception {
        isolateUpgradeCookies();
        Backend backend = new Backend(mode, version, version, version);
        server.setDispatcher(backend);
        PublishedUpgradeScenario.TokenEvidence observed = PublishedUpgradeScenario.run(context, mode, flow, new PublishedUpgradeScenario.Routes(
                server.url("/").toString().replaceAll("/$", ""), "/login", "/protected", "/update-jwt", "{}", "1.16"),
                this::flushAndRestartPreferences);
        observed.assertVersions("" + version, flow == Flow.SIGN_OUT ? "ABSENT" : "" + version, "" + version);
        assertTrue("both generations must reach the refresh endpoint", backend.refreshes >= (flow == Flow.REFRESH ? 2 : 1));
        assertTrue("no protected request after logout before relogin", backend.unauthorised == 0);
        System.out.println("SYNTHETIC version=" + version + " mode=" + mode + " flow=" + flow);
    }

    static final class Backend extends Dispatcher {
        private final Mode mode;
        private final int loginVersion, refreshVersion, payloadVersion;
        private int version;
        private String access, refresh, front;
        private String claim = "initial";
        private int generation, refreshes, unauthorised;
        private boolean active;

        Backend(Mode mode, int loginVersion, int refreshVersion, int payloadVersion) {
            this.mode = mode;
            this.loginVersion = loginVersion;
            this.refreshVersion = refreshVersion;
            this.payloadVersion = payloadVersion;
        }

        @Override public synchronized MockResponse dispatch(RecordedRequest request) {
            try {
                assertEquals("explicit FDI member must reach every wire", "1.16", request.getHeader("fdi-version"));
                String path = request.getPath();
                if (path.equals("/login")) {
                    version = loginVersion;
                    active = true; claim = "initial"; rotate();
                    return sessionResponse(true);
                }
                if (path.equals("/auth/session/refresh")) {
                    refreshes++;
                    if (!authorised(request, true)) return denied();
                    version = refreshVersion;
                    rotate(); return sessionResponse(true);
                }
                if (!authorised(request, false)) return denied();
                if (path.equals("/auth/signout")) {
                    active = false;
                    return new MockResponse().setBody("{\"status\":\"OK\"}").addHeader("front-token", "remove")
                            .addHeader("Set-Cookie", "sAccessToken=; Path=/; Max-Age=0")
                            .addHeader("Set-Cookie", "sRefreshToken=; Path=/; Max-Age=0");
                }
                if (path.equals("/update-jwt")) {
                    version = Math.max(version, payloadVersion);
                    claim = new JSONObject(request.getBody().readUtf8()).getString("upgradeClaim");
                    String retainedRefresh = refresh;
                    rotate();
                    refresh = retainedRefresh;
                    return sessionResponse(false);
                }
                return new MockResponse().setBody("protected");
            } catch (Exception error) { throw new AssertionError(error); }
        }

        private MockResponse denied() { unauthorised++; return new MockResponse().setResponseCode(401); }

        private boolean authorised(RecordedRequest request, boolean refreshing) {
            if (!active) return false;
            String expected = refreshing ? refresh : access;
            if (mode == Mode.HEADER) return ("Bearer " + expected).equals(request.getHeader("Authorization"));
            assertNull("cookie flows must not fabricate bearer credentials", request.getHeader("Authorization"));
            String cookies = request.getHeader("Cookie");
            return cookies != null && cookies.contains((refreshing ? "sRefreshToken=" : "sAccessToken=") + expected);
        }

        private void rotate() throws Exception {
            generation++;
            JSONObject payload = new JSONObject().put("sessionHandle", "upgrade-session").put("upgradeClaim", claim);
            if (version == 2) payload.put("userId", "upgrade-user").put("expiryTime", 4102444800000L)
                    .put("timeCreated", 1700000000000L).put("userData", new JSONObject().put("upgradeClaim", claim));
            else payload.put("sub", "upgrade-user").put("exp", 4102444800L).put("iat", 1700000000L);
            if (version >= 4) payload.put("tId", "public");
            if (version >= 5) payload.put("rsub", "recipe-user");
            access = encode(new JSONObject().put("alg", "RS256").put("typ", "JWT").put("version", "" + version).toString(), true)
                    + "." + encode(payload.toString(), true) + ".signature" + generation;
            refresh = "refresh-" + generation;
            front = encode(new JSONObject().put("uid", "upgrade-user").put("ate", 4102444800000L)
                    .put("up", version == 2 ? new JSONObject().put("upgradeClaim", claim) : payload).toString(), false);
        }

        private MockResponse sessionResponse(boolean rotateRefresh) {
            MockResponse response = new MockResponse().setBody("{\"status\":\"OK\"}")
                    .addHeader("front-token", front).addHeader("anti-csrf", "csrf");
            if (mode == Mode.HEADER) {
                response.addHeader("st-access-token", access);
                if (rotateRefresh) response.addHeader("st-refresh-token", refresh);
            } else {
                response.addHeader("Set-Cookie", "sAccessToken=" + access + "; Path=/; Max-Age=3600");
                if (rotateRefresh) response.addHeader("Set-Cookie", "sRefreshToken=" + refresh + "; Path=/; Max-Age=3600");
                if (mode == Mode.EXPOSED_COOKIE) response.addHeader("st-access-token", access);
            }
            return response;
        }
    }

    private static String encode(String value, boolean url) throws Exception {
        return (url ? Base64.getUrlEncoder().withoutPadding() : Base64.getEncoder()).encodeToString(value.getBytes("UTF-8"));
    }
}
