package com.supertokens.upgrade;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensHttpURLConnection;
import com.supertokens.session.SuperTokensPersistentCookieStore;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs inside the app DEX, including in optimized builds; instrumentation contains no SDK calls. */
public final class R8Smoke {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String PROCESS = UUID.randomUUID().toString();
    private final Context context;
    private final SharedPreferences receipt;
    private final String backend;
    private final String run;
    private SuperTokensPersistentCookieStore cookies;

    private R8Smoke(Context context, Bundle args) {
        this.context = context;
        receipt = context.getSharedPreferences("r8-receipt", 0);
        backend = required(args, "backend");
        run = required(args, "runId");
    }

    public static void run(Context context, Bundle args) throws Exception {
        new R8Smoke(context, args).stage(required(args, "stage"));
    }

    private void stage(String stage) throws Exception {
        CookieHandler previous = CookieHandler.getDefault();
        try {
            cookies = new SuperTokensPersistentCookieStore(context);
            CookieHandler.setDefault(new CookieManager(cookies, CookiePolicy.ACCEPT_ALL));
            if (!stage.equals("seed")) {
                check(run.equals(receipt.getString("runId", null)), "Wrong run receipt");
                check(backend.equals(receipt.getString("backend", null)), "Wrong backend receipt");
                check(!PROCESS.equals(receipt.getString("process", null)), "Expected new process");
                // Validate persisted serialization before any SDK initialization/network mutation.
                check(receipt.getString("probeDigest", "").equals(cookieDigest("r8Probe")), "Prior serialized cookie migration failed");
                check(receipt.getString("accessDigest", "").equals(cookieDigest("sAccessToken")), "Persisted access cookie changed");
                check(receipt.getString("refreshDigest", "").equals(cookieDigest("sRefreshToken")), "Persisted refresh cookie changed");
            }
            JSONObject capability = request("/test/capabilities", null, false);
            check(capability.getBoolean("mappedGoogleSession") && capability.getBoolean("compatible"), "Backend unavailable");
            new SuperTokens.Builder(context, backend).tokenTransferMethod("cookie").build();
            if (stage.equals("seed")) {
                check(!receipt.contains("runId") || receipt.getBoolean("cleanupDone", false), "Pending R8 fixture cleanup");
                check(!SuperTokens.doesSessionExist(context), "Seed requires logged-out fixture app");
                check(receipt.edit().clear().putString("runId", run).putString("backend", backend)
                        .putString("owners", "[]").putBoolean("cleanupDone", false).commit(), "Receipt commit failed");
                HttpCookie probe = new HttpCookie("r8Probe", run);
                probe.setPath("/"); probe.setMaxAge(86400); probe.setHttpOnly(true);
                cookies.add(new URI(backend + "/"), probe);
                login("seed");
                save("seeded");
            } else if (stage.equals("mapping-a") || stage.equals("mapping-b")) {
                check((stage.equals("mapping-a") ? "seeded" : "mapping-a").equals(receipt.getString("phase", null)), "Wrong prior phase");
                protectedRequest();
                refresh();
                login(stage);
                refresh();
                save(stage);
            } else if (stage.equals("cleanup")) {
                check(!receipt.contains("pendingRequestId"), "Creation outcome unknown; preserve receipt for scoped reconciliation");
                if (SuperTokens.doesSessionExist(context)) SuperTokens.signOut(context);
                check(!SuperTokens.doesSessionExist(context), "Sign-out failed");
                JSONArray owners = new JSONArray(receipt.getString("owners", "[]"));
                while (owners.length() > 0) {
                    check(request("/test/cleanup-owner", new JSONObject().put("fixtureId", owners.getString(0)), false)
                            .getBoolean("cleaned"), "Scoped cleanup failed");
                    JSONArray remaining = new JSONArray();
                    for (int i = 1; i < owners.length(); i++) remaining.put(owners.getString(i));
                    owners = remaining;
                    check(receipt.edit().putString("owners", owners.toString()).commit(), "Cleanup receipt commit failed");
                }
                for (HttpCookie cookie : cookies.getCookies()) {
                    if (cookie != null && cookie.getName().equals("r8Probe")) cookies.remove(new URI(backend + "/"), cookie);
                }
                flush();
                check(receipt.edit().putBoolean("cleanupDone", true).putString("phase", "cleaned")
                        .putString("process", PROCESS).commit(), "Cleanup commit failed");
            } else throw new AssertionError("Unknown R8 stage");
        } finally { CookieHandler.setDefault(previous); }
    }

    private void login(String label) throws Exception {
        String requestId = run + "-" + label;
        check(!receipt.contains("pendingRequestId"), "Unresolved creation request");
        check(receipt.edit().putString("pendingRequestId", requestId).putString("pendingRequestEmail", requestId + "@r8.example").commit(), "Pending commit failed");
        JSONObject owner = request("/test/email-change-owner", new JSONObject().put("recipe", "google")
                .put("email", requestId + "@r8.example").put("requestId", requestId), true);
        JSONArray owners = new JSONArray(receipt.getString("owners", "[]"));
        owners.put(owner.getString("fixtureId"));
        check(receipt.edit().putString("owners", owners.toString()).putString("userId", owner.getString("userId"))
                .putString("handle", owner.getString("handle")).remove("pendingRequestId").remove("pendingRequestEmail").commit(), "Owner commit failed");
        check(owner.getBoolean("google"), "Expected real mapped owner");
        protectedRequest();
    }

    private void protectedRequest() throws Exception {
        check(SuperTokens.doesSessionExist(context), "Session absent");
        check(receipt.getString("userId", "").equals(SuperTokens.getUserId(context)), "Wrong local user");
        check(SuperTokens.getAccessToken(context) == null, "Expected cookie transfer");
        JSONObject actual = request("/test/protected", null, true);
        check(receipt.getString("userId", "").equals(actual.getString("userId")), "Wrong backend user");
        check(receipt.getString("handle", "").equals(actual.getString("handle")), "Wrong backend session");
    }

    private void refresh() throws Exception {
        String before = cookieDigest("sRefreshToken");
        check(!before.isEmpty(), "Missing refresh cookie");
        check(SuperTokens.attemptRefreshingSession(context), "Refresh failed");
        check(!before.equals(cookieDigest("sRefreshToken")), "Refresh did not rotate");
        protectedRequest();
    }

    private String cookieDigest(String name) throws Exception {
        for (HttpCookie cookie : cookies.getCookies()) {
            check(cookie != null, "Serialized cookie decoded to null");
            if (cookie.getName().equals(name)) {
                if (name.equals("r8Probe")) {
                    check(cookie.isHttpOnly() && "/".equals(cookie.getPath()) && cookie.getMaxAge() > 0, "Probe attributes changed");
                }
                byte[] bytes = MessageDigest.getInstance("SHA-256").digest(cookie.getValue().getBytes(UTF8));
                StringBuilder text = new StringBuilder();
                for (byte item : bytes) text.append(String.format("%02x", item & 255));
                return text.toString();
            }
        }
        return "";
    }

    private void save(String phase) throws Exception {
        flush();
        String access = cookieDigest("sAccessToken"), refresh = cookieDigest("sRefreshToken"), probe = cookieDigest("r8Probe");
        check(!access.isEmpty() && !refresh.isEmpty() && !probe.isEmpty(), "Missing persisted cookie");
        check(receipt.edit().putString("phase", phase).putString("process", PROCESS).putString("accessDigest", access)
                .putString("refreshDigest", refresh).putString("probeDigest", probe).commit(), "Save failed");
    }

    private void flush() {
        check(context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit().commit(), "Session flush failed");
        check(context.getSharedPreferences("supertokens-cookie-shared-prefs", 0).edit().commit(), "Cookie flush failed");
    }

    private JSONObject request(String path, final JSONObject body, boolean managed) throws Exception {
        URL url = new URL(backend + path);
        SuperTokensHttpURLConnection.PreConnectCallback prepare = new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override public void doAction(HttpURLConnection connection) throws java.io.IOException {
                connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(false);
                if (body != null) {
                    connection.setRequestMethod("POST"); connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    OutputStream output = connection.getOutputStream();
                    try { output.write(body.toString().getBytes(UTF8)); } finally { output.close(); }
                }
            }
        };
        HttpURLConnection connection;
        if (managed) connection = SuperTokensHttpURLConnection.newRequest(url, prepare);
        else { connection = (HttpURLConnection) url.openConnection(); prepare.doAction(connection); }
        try {
            check(connection.getResponseCode() == 200, "Unexpected HTTP status for " + path);
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    check(bytes.size() + count <= 65536, "Oversized fixture response");
                    bytes.write(buffer, 0, count);
                }
            } finally { input.close(); }
            return new JSONObject(new String(bytes.toByteArray(), UTF8));
        } finally { connection.disconnect(); }
    }

    private static String required(Bundle args, String key) {
        String value = args.getString(key);
        check(value != null && !value.isEmpty(), "Missing argument: " + key);
        return value;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
