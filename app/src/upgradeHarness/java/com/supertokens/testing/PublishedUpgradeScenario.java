package com.supertokens.testing;

import android.content.Context;
import com.supertokens.session.EventHandler;
import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.SessionOwner;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensInterceptor;
import java.lang.reflect.Method;
import java.net.CookieHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.CookieJar;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

/** One flow implementation shared by synthetic JVM and externally provisioned live FDI tests. */
public final class PublishedUpgradeScenario {
    public enum Mode { HEADER, COOKIE, EXPOSED_COOKIE }
    public enum Flow { LOGIN, REFRESH, SIGN_OUT, PAYLOAD_UPDATE }

    public static final class TokenEvidence {
        public static final String ABSENT = "ABSENT";
        public final String publishedLoginVersion, publishedHandoffVersion, candidateRefreshVersion;

        private TokenEvidence(String login, String handoff, String refreshed) {
            publishedLoginVersion = login;
            publishedHandoffVersion = handoff;
            candidateRefreshVersion = refreshed;
        }

        public void assertVersions(String login, String handoff, String refreshed) {
            assertPhase("publishedLogin", login, publishedLoginVersion);
            assertPhase("publishedHandoff", handoff, publishedHandoffVersion);
            assertPhase("candidateRefresh", refreshed, candidateRefreshVersion);
        }

        public JSONObject toJson() throws Exception {
            return new JSONObject().put("schemaVersion", 2)
                    .put("publishedLogin", phase(publishedLoginVersion))
                    .put("publishedHandoff", phase(publishedHandoffVersion))
                    .put("candidateRefresh", phase(candidateRefreshVersion));
        }

        private static JSONObject phase(String version) throws Exception {
            boolean absent = ABSENT.equals(version);
            return new JSONObject().put("status", absent ? "ABSENT" : "PRESENT")
                    .put("tokenVersion", absent ? JSONObject.NULL : version);
        }

        private static void assertPhase(String phase, String expected, String actual) {
            if (expected == null) return;
            check(expected.matches("[2-5]|ABSENT"), "Invalid expected version for " + phase + ": " + expected);
            check(expected.equals(actual), phase + " expected=" + expected + " observed=" + actual);
        }
    }

    public static final class Routes {
        public final String domain, login, protectedPath, payload, loginBody, fdi;
        public Routes(String domain, String login, String protectedPath, String payload, String loginBody, String fdi) {
            this.domain = domain; this.login = login; this.protectedPath = protectedPath;
            this.payload = payload; this.loginBody = loginBody; this.fdi = fdi;
        }
    }

    private PublishedUpgradeScenario() { }

    public static void resetCandidate() throws Exception {
        Method reset = SuperTokens.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    public static TokenEvidence run(Context context, Mode mode, Flow flow, Routes routes, Runnable restartStorage) throws Exception {
        CookieHandler previous = CookieHandler.getDefault();
        OkHttpClient candidate = null;
        boolean candidateEntered = false;
        String executionPhase = "setup";
        try {
            resetCandidate();
            UpgradeCookies oldCookies = new UpgradeCookies(context);
            CookieHandler.setDefault(mode == Mode.HEADER ? new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_NONE) : oldCookies);
            String transfer = mode == Mode.HEADER ? "header" : "cookie";
            boolean exists;
            String uid = null, access = null, payload = null;
            String loginVersion;
            String handoffVersion = TokenEvidence.ABSENT;
            try (Legacy054Harness legacy = new Legacy054Harness(context)) {
                legacy.invoke("buildForUpgrade", new Class<?>[]{String.class, String.class, CookieJar.class, String.class},
                        routes.domain, transfer, mode == Mode.HEADER ? CookieJar.NO_COOKIES : oldCookies.jar, routes.fdi);
                legacyRequest(legacy, routes, routes.login, "POST", routes.loginBody);
                loginVersion = tokenVersion((String) legacy.invoke("getAccessToken"), oldCookies, routes.domain);
                System.out.println("UPGRADE observed publishedLoginVersion=" + loginVersion + " mode=" + mode + " flow=" + flow);
                executionPhase = "published";
                if (flow == Flow.REFRESH) check(Boolean.TRUE.equals(legacy.invoke("refresh")), "published refresh failed");
                if (flow == Flow.PAYLOAD_UPDATE) {
                    legacyRequest(legacy, routes, routes.payload, "POST", "{\"upgradeClaim\":\"published\"}");
                    check("published".equals(new JSONObject((String) legacy.invoke("getAccessTokenPayloadSecurely"))
                            .optString("upgradeClaim")), "published payload update missing");
                }
                if (flow == Flow.SIGN_OUT) legacy.invoke("signOut");
                exists = (Boolean) legacy.invoke("doesSessionExist");
                check(exists == (flow != Flow.SIGN_OUT), "published session state disagrees with flow");
                if (exists) {
                    uid = (String) legacy.invoke("getUserId");
                    access = (String) legacy.invoke("getAccessToken");
                    payload = (String) legacy.invoke("getAccessTokenPayloadSecurely");
                    if (mode == Mode.EXPOSED_COOKIE) check(access != null, "backend did not expose cookie access token");
                    handoffVersion = tokenVersion(access, oldCookies, routes.domain);
                }
                System.out.println("UPGRADE observed publishedHandoffVersion=" + handoffVersion + " mode=" + mode + " flow=" + flow);
            }
            candidateEntered = true;
            executionPhase = "candidate";
            restartStorage.run();
            resetCandidate();
            UpgradeCookies newCookies = new UpgradeCookies(context);
            CookieHandler.setDefault(mode == Mode.HEADER ? new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_NONE) : newCookies);
            final List<EventHandler.EventType> events = new ArrayList<>();
            new SuperTokens.Builder(context, routes.domain).tokenTransferMethod(transfer)
                    .customHeaderProvider(type -> routes.fdi == null ? java.util.Collections.emptyMap()
                            : java.util.Collections.singletonMap("fdi-version", routes.fdi))
                    .eventHandler(events::add).build();
            candidate = new OkHttpClient.Builder().cookieJar(mode == Mode.HEADER ? CookieJar.NO_COOKIES : newCookies.jar).callTimeout(8, TimeUnit.SECONDS)
                    .addInterceptor(new SuperTokensInterceptor()).build();
            check(SuperTokens.doesSessionExist(context) == exists, "candidate existence differs from published");
            if (!exists) {
                check(SuperTokens.getAccessToken(context) == null, "logout retained access token");
                request(candidate, routes, routes.login, "POST", routes.loginBody);
                check(SuperTokens.doesSessionExist(context), "login unreachable after published logout");
            } else {
                check(uid.equals(SuperTokens.getUserId(context)), "import changed uid");
                check(equal(access, SuperTokens.getAccessToken(context)), "import changed exposed access");
                check(new JSONObject(payload).toString().equals(SuperTokens.getAccessTokenPayloadSecurely(context).toString()),
                        "import changed payload");
            }
            request(candidate, routes, routes.protectedPath, "GET", null);
            SessionOwner owner = SuperTokens.getSessionSnapshot(context).getOwner();
            check(owner != null, "candidate session has no owner before continuity checks");
            check(SuperTokens.attemptRefreshingSession(context), "candidate refresh failed");
            String refreshedVersion = tokenVersion(SuperTokens.getAccessToken(context), newCookies, routes.domain);
            System.out.println("UPGRADE observed candidateRefreshVersion=" + refreshedVersion);
            check(owner == SuperTokens.getSessionSnapshot(context).getOwner(), "refresh replaced owner");
            request(candidate, routes, routes.protectedPath, "GET", null);
            events.clear();
            request(candidate, routes, routes.payload, "POST", "{\"upgradeClaim\":\"candidate\"}");
            check(owner == SuperTokens.getSessionSnapshot(context).getOwner(), "payload update replaced owner");
            check("candidate".equals(SuperTokens.getAccessTokenPayloadSecurely(context).optString("upgradeClaim")),
                    "candidate payload update missing");
            check(events.size() == 1 && events.get(0) == EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED,
                    "payload update event mismatch: " + events);
            SuperTokens.signOut(context);
            check(!SuperTokens.doesSessionExist(context), "candidate signout failed");
            TokenEvidence evidence = new TokenEvidence(loginVersion, handoffVersion, refreshedVersion);
            System.out.println("UPGRADE mode=" + mode + " flow=" + flow + " fdi=" + routes.fdi + " observations=" + evidence.toJson());
            return evidence;
        } finally {
            System.out.println("UPGRADE_EXECUTION " + new JSONObject()
                    .put("nonce", System.getenv("ST_ARTIFACT_NONCE"))
                    .put("mode", mode.name()).put("flow", flow.name())
                    .put("phase", executionPhase).put("candidateEntered", candidateEntered));
            if (candidate != null) {
                candidate.dispatcher().cancelAll(); candidate.connectionPool().evictAll();
                candidate.dispatcher().executorService().shutdownNow();
            }
            resetCandidate();
            CookieHandler.setDefault(previous);
        }
    }

    @SuppressWarnings("unchecked")
    private static void legacyRequest(Legacy054Harness legacy, Routes routes, String path, String method, String body) throws Exception {
        Map<String, Object> response = (Map<String, Object>) legacy.invoke("upgradeRequest",
                new Class<?>[]{String.class, String.class, String.class, String.class}, routes.domain + path, method, body, routes.fdi);
        check(Integer.valueOf(200).equals(response.get("status")), "published " + path + " status=" + response.get("status"));
    }

    private static void request(OkHttpClient client, Routes routes, String path, String method, String json) throws Exception {
        Request.Builder request = new Request.Builder().url(routes.domain + path).method(method,
                json == null ? null : RequestBody.create(MediaType.parse("application/json"), json));
        if (routes.fdi != null) request.header("fdi-version", routes.fdi);
        try (Response response = client.newCall(request.build()).execute()) {
            check(response.code() == 200, "candidate " + path + " status=" + response.code());
        }
    }

    private static boolean equal(Object a, Object b) { return a == null ? b == null : a.equals(b); }

    private static String tokenVersion(String exposed, UpgradeCookies cookies, String domain) throws Exception {
        String token = exposed;
        if (token == null) for (okhttp3.Cookie cookie : cookies.jar.loadForRequest(okhttp3.HttpUrl.get(domain))) {
            if (cookie.name().equals("sAccessToken")) token = cookie.value();
        }
        check(token != null && token.split("\\.").length == 3, "no observable access JWT for version evidence");
        JSONObject header = new JSONObject(new String(android.util.Base64.decode(token.split("\\.")[0], android.util.Base64.URL_SAFE), "UTF-8"));
        if (header.has("version")) {
            String version = header.getString("version");
            check(version.matches("[2-5]"), "unrecognised access token version: " + version);
            return version;
        }
        check(header.has("kid") && "JWT".equals(header.optString("typ")) && "RS256".equals(header.optString("alg")),
                "unrecognised unversioned JWT header");
        return "5";
    }

    private static void check(boolean success, String message) { if (!success) throw new AssertionError(message); }
}
