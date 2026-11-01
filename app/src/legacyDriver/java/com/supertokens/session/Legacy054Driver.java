package com.supertokens.session;

import android.content.Context;
import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Compiled separately against the checksum-pinned published SDK. */
public final class Legacy054Driver implements Closeable {
    private final Context context;
    private OkHttpClient client;

    public Legacy054Driver(Context context) {
        this.context = context;
        client = new OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS)
                .addInterceptor(new SuperTokensInterceptor()).build();
    }

    public void build(String apiDomain, String transferMethod) throws Exception {
        new SuperTokens.Builder(context, apiDomain).tokenTransferMethod(transferMethod).build();
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
        new SuperTokens.Builder(context, apiDomain).tokenTransferMethod("header")
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
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
    }
}
