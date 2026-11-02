package com.supertokens.session;

import android.content.Context;
import java.io.Closeable;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/** Compiled separately against the checksum-pinned published SDK. */
public final class Legacy054Driver implements Closeable {
    private final Context context;
    private OkHttpClient client;
    private CookieHandler previousCookies;
    private CookieManager cookies;

    public Legacy054Driver(Context context) {
        this.context = context;
        client = new OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS)
                .addInterceptor(new SuperTokensInterceptor()).build();
    }

    public void build(String apiDomain, String transferMethod) throws Exception {
        new SuperTokens.Builder(context, apiDomain).tokenTransferMethod(transferMethod).build();
    }

    public void setReadTimeoutMillis(long millis) {
        client = client.newBuilder().readTimeout(millis, TimeUnit.MILLISECONDS).build();
    }

    /** Opt-in because CookieHandler is process-global, including across isolated class loaders. */
    public void useCookies() {
        if (cookies != null) throw new IllegalStateException("Cookies already installed");
        previousCookies = CookieHandler.getDefault();
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cookies);
        final CookieManager manager = cookies;
        client = client.newBuilder().cookieJar(new CookieJar() {
            @Override public void saveFromResponse(HttpUrl url, List<Cookie> responseCookies) {
                List<String> headers = new ArrayList<>();
                for (Cookie cookie : responseCookies) headers.add(cookie.toString());
                try {
                    manager.put(url.uri(), java.util.Collections.singletonMap("Set-Cookie", headers));
                } catch (IOException error) {
                    throw new IllegalStateException("Could not store cookies", error);
                }
            }
            @Override public List<Cookie> loadForRequest(HttpUrl url) {
                List<Cookie> result = new ArrayList<>();
                try {
                    Map<String, List<String>> headers = manager.get(url.uri(), java.util.Collections.<String, List<String>>emptyMap());
                    for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                        if (!"Cookie".equalsIgnoreCase(header.getKey())) continue;
                        for (String value : header.getValue()) for (String pair : value.split(";\\s*")) {
                            if (pair.startsWith("$")) continue;
                            Cookie parsed = Cookie.parse(url, pair);
                            if (parsed != null) result.add(parsed);
                        }
                    }
                } catch (IOException error) {
                    throw new IllegalStateException("Could not read cookies", error);
                }
                return result;
            }
        }).build();
    }

    public void buildForUpgrade(String apiDomain, String transferMethod, okhttp3.CookieJar cookies,
                                final String fdiVersion) throws Exception {
        client = client.newBuilder().cookieJar(cookies).build();
        new SuperTokens.Builder(context, apiDomain).tokenTransferMethod(transferMethod)
                .customHeaderProvider(new CustomHeaderProvider() {
                    @Override public Map<String, String> getRequestHeaders(RequestType type) {
                        return fdiVersion == null ? java.util.Collections.<String, String>emptyMap()
                                : java.util.Collections.singletonMap("fdi-version", fdiVersion);
                    }
                }).build();
    }

    public Map<String, Object> upgradeRequest(String url, String method, String json, String fdiVersion) throws IOException {
        Request.Builder request = new Request.Builder().url(url).method(method, json == null ? null
                : okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), json));
        if (fdiVersion != null) request.header("fdi-version", fdiVersion);
        try (Response response = client.newCall(request.build()).execute()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", response.code());
            result.put("body", response.body() == null ? null : response.body().string());
            return result;
        }
    }

    public void buildWithEvents(String apiDomain, final java.util.List<String> events,
                                final String throwOn) throws Exception {
        buildWithEventsForTransfer(apiDomain, "header", events, throwOn);
    }

    public void buildWithEventsForTransfer(String apiDomain, String transferMethod,
                                          final java.util.List<String> events, final String throwOn) throws Exception {
        new SuperTokens.Builder(context, apiDomain).tokenTransferMethod(transferMethod)
                .eventHandler(new EventHandler() {
                    @Override public void handleEvent(EventType type) {
                        events.add(type.name() + ":" + Thread.currentThread().getId());
                        if (type.name().equals(throwOn)) throw new IllegalStateException("oracle-handler");
                    }
                }).build();
    }

    public boolean isInitialized() { return SuperTokens.isInitCalled; }

    public Map<String, String> normalizeUrls(String apiDomain, String apiBasePath) throws Exception {
        Utils.NormalisedInputType normalized = Utils.NormalisedInputType.normaliseInputOrThrowError(
                apiDomain, apiBasePath, null, null, null, null, null, null);
        Map<String, String> urls = new LinkedHashMap<>();
        urls.put("apiDomain", normalized.apiDomain);
        urls.put("apiBasePath", normalized.apiBasePath);
        urls.put("refresh", normalized.apiDomain + normalized.apiBasePath + "/session/refresh");
        urls.put("signOut", normalized.apiDomain + normalized.apiBasePath + "/signout");
        return urls;
    }

    public boolean doesSessionExist() { return SuperTokens.doesSessionExist(context); }
    public String getUserId() { return SuperTokens.getUserId(context); }
    public String getAccessToken() { return SuperTokens.getAccessToken(context); }
    public String getTokenForHeaderAuth() { return Utils.getTokenForHeaderAuth(Utils.TokenType.ACCESS, context); }
    public String getAuthorizationHeaderIfExists(boolean refresh) { return Utils.getAuthorizationHeaderIfExists(refresh, context); }

    public String getAccessTokenPayloadSecurely() throws Exception {
        return SuperTokens.getAccessTokenPayloadSecurely(context).toString();
    }

    public boolean refresh() throws Exception {
        return SuperTokens.attemptRefreshingSession(context);
    }

    public int requestURLConnection(String url) throws Exception {
        java.net.HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(new java.net.URL(url), null);
        try { return connection.getResponseCode(); } finally { connection.disconnect(); }
    }

    public void signOut() throws Exception { SuperTokens.signOut(context); }

    public void setFrontToken(String value) { FrontToken.setToken(context, value); }
    public void setFrontItem(String value) { FrontToken.setItem(context, value); }
    public void store(String name, String value) { Utils.storeInStorage(name, value, context); }
    public Map<String, Object> probeMarkerCollision(String first, String second) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int attempt = 1; attempt <= 100; attempt++) {
            long start = System.currentTimeMillis();
            FrontToken.setItem(context, first);
            String firstMarker = Utils.getFromStorage(Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY, context);
            FrontToken.setItem(context, second);
            String secondMarker = Utils.getFromStorage(Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY, context);
            result.put("start", start);
            result.put("end", System.currentTimeMillis());
            result.put("firstMarker", firstMarker);
            result.put("secondMarker", secondMarker);
            result.put("attempts", attempt);
            result.put("collision", firstMarker.equals(secondMarker));
            if (firstMarker.equals(secondMarker)) break;
        }
        return result;
    }

    public Map<String, Object> request(String url) throws IOException {
        try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", response.code());
            result.put("body", response.body() == null ? null : response.body().string());
            return result;
        }
    }

    @Override public void close() {
        if (cookies != null && CookieHandler.getDefault() == cookies) CookieHandler.setDefault(previousCookies);
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
    }
}
