package compatibility;

import android.content.Context;
import com.supertokens.session.BuildConfig;
import com.supertokens.session.CustomHeaderProvider;
import com.supertokens.session.EventHandler;
import com.supertokens.session.FrontToken;
import com.supertokens.session.NormalisedURLDomain;
import com.supertokens.session.NormalisedURLPath;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensCustomHttpURLConnection;
import com.supertokens.session.SuperTokensGeneralError;
import com.supertokens.session.SuperTokensHttpURLConnection;
import com.supertokens.session.SuperTokensInterceptor;
import com.supertokens.session.SuperTokensPersistentCookieStore;
import com.supertokens.session.Utils;
import com.supertokens.session.Version;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;

public final class OldSdkConsumer {
    public static void exerciseAbsentSession(Context context) throws Exception {
        new SuperTokens.Builder(context, "https://example.com")
                .apiBasePath("/auth").sessionExpiredStatusCode(401)
                .maxRetryAttemptsForSessionRefresh(10).sessionTokenBackendDomain("example.com")
                .customHeaderProvider(type -> Collections.emptyMap())
                .eventHandler(event -> { })
                .tokenTransferMethod("header").build();
        if (SuperTokens.doesSessionExist(context)) throw new AssertionError("Unexpected session");
        if (SuperTokens.getAccessToken(context) != null) throw new AssertionError("Unexpected access token");
        try {
            SuperTokens.getUserId(context);
            throw new AssertionError("Expected an absent-session error");
        } catch (IllegalStateException expected) {
            // The published API throws when no session exists.
        }
        try {
            SuperTokens.getAccessTokenPayloadSecurely(context);
            throw new AssertionError("Expected an absent-session payload error");
        } catch (IllegalStateException expected) { }
        if (FrontToken.getToken(context) != null || FrontToken.doesTokenExist(context)) {
            throw new AssertionError("Unexpected front token");
        }
        EventHandler handler = new EventHandler.DefaultEventHandler();
        for (EventHandler.EventType type : new EventHandler.EventType[]{
                EventHandler.EventType.SIGN_OUT, EventHandler.EventType.REFRESH_SESSION,
                EventHandler.EventType.SESSION_CREATED, EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED,
                EventHandler.EventType.UNAUTHORISED}) {
            handler.handleEvent(EventHandler.EventType.valueOf(type.name()));
        }
        if (EventHandler.EventType.values().length < 5) throw new AssertionError("Missing events");
        CustomHeaderProvider headers = new CustomHeaderProvider.DefaultCustomHeaderMapper();
        headers.getRequestHeaders(CustomHeaderProvider.RequestType.REFRESH);
        headers.getRequestHeaders(CustomHeaderProvider.RequestType.SIGN_OUT);
        CustomHeaderProvider.RequestType.valueOf(CustomHeaderProvider.RequestType.values()[0].name());
        Utils.TokenType.valueOf(Utils.TokenType.values()[0].name());
        new SuperTokensGeneralError("consumer").getMessage();
        new SuperTokens();
        new FrontToken();
        new Utils();
        new SuperTokensHttpURLConnection();
        new SuperTokensInterceptor();
        new Version();
        new BuildConfig();
        String build = BuildConfig.LIBRARY_PACKAGE_NAME + BuildConfig.BUILD_TYPE + BuildConfig.DEBUG;
        if (build.isEmpty()) throw new AssertionError("Missing build metadata");
        if (Version.supported_fdi.isEmpty()) throw new AssertionError("Missing FDI versions");
        new NormalisedURLDomain("https://example.com").getAsStringDangerous();
        NormalisedURLPath path = new NormalisedURLPath("/auth");
        path.startWith(new NormalisedURLPath("/"));
        path.appendPath(new NormalisedURLPath("/session")).getAsStringDangerous();
        Utils.NormalisedInputType.normaliseSessionScopeOrThrowErrorForTests(".example.com");
        Utils.NormalisedInputType.normaliseInputOrThrowError("https://example.com", "/auth",
                401, 10, "example.com", "header", headers, handler);
        new Utils.NormalisedInputType("https://example.com", "/auth", 401, 10,
                "example.com", "header", headers, handler);
        Utils.getStorageName(Utils.TokenType.ACCESS);
        Utils.getStorageName(Utils.TokenType.REFRESH);
        Utils.getLocalSessionState(context);
        Utils.getTokenForHeaderAuth(Utils.TokenType.ACCESS, context);
        Utils.getAuthorizationHeaderIfExists(false, context);
        Utils.getIgnoreCase(Collections.singletonMap("Header", "value"), "header");
        Utils.join(new ArrayList<String>(), ",");
        Utils.getHostWithProtocolFromURL(new URL("https://example.com"));
        Utils.shouldDoInterceptionBasedOnUrl("https://example.com/path", "https://example.com", null);
    }

    // Compiled linkage probes; the absent-session smoke never performs network I/O or session mutation.
    public static void exerciseNetworkAndMutationSurface(Context context, URL url,
            HttpURLConnection delegate, okhttp3.Response response, okhttp3.Interceptor.Chain chain) throws Exception {
        SuperTokens.attemptRefreshingSession(context);
        SuperTokens.signOut(context);
        SuperTokens.setTokenTransferMethod("header");
        SuperTokens.resetForTests();
        FrontToken.setToken(context, "front");
        FrontToken.setItem(context, "remove");
        FrontToken.removeToken(context);
        Utils.storeInStorage("consumer", "value", context);
        Utils.getFromStorage("consumer", context);
        Utils.saveLastAccessTokenUpdate(context);
        Utils.setToken(Utils.TokenType.ACCESS, "access", context);
        Utils.setToken(Utils.TokenType.REFRESH, "refresh", context);
        Utils.saveTokenFromHeaders(response, context);
        Utils.fireSessionUpdateEventsIfNecessary(false, 200, "front");
        SuperTokensHttpURLConnection.PreConnectCallback callback = connection -> connection.setRequestProperty("consumer", "old");
        callback.doAction(delegate);
        SuperTokensHttpURLConnection.newRequest(url, callback).disconnect();
        new SuperTokensInterceptor().intercept(chain).close();
        SuperTokensPersistentCookieStore cookies = new SuperTokensPersistentCookieStore(context);
        URI uri = url.toURI();
        HttpCookie cookie = new HttpCookie("consumer", "value");
        cookies.add(uri, cookie);
        cookies.get(uri);
        cookies.getCookies();
        cookies.getURIs();
        cookies.remove(uri, cookie);
        cookies.removeAll();
        SuperTokensCustomHttpURLConnection connection = new SuperTokensCustomHttpURLConnection(delegate, context);
        new SuperTokensCustomHttpURLConnection(delegate, context, true).getWasAuthHeaderRemovedInitially();
        Utils.saveTokenFromHeaders(connection, context);
        connection.usingProxy();
        connection.setFixedLengthStreamingMode(0);
        connection.setFixedLengthStreamingMode(0L);
        connection.setChunkedStreamingMode(0);
        connection.setInstanceFollowRedirects(false);
        connection.getInstanceFollowRedirects();
        connection.setRequestMethod("GET");
        connection.getRequestMethod();
        connection.setConnectTimeout(1);
        connection.getConnectTimeout();
        connection.setReadTimeout(1);
        connection.getReadTimeout();
        connection.setDoInput(true);
        connection.getDoInput();
        connection.setDoOutput(false);
        connection.getDoOutput();
        connection.setAllowUserInteraction(false);
        connection.getAllowUserInteraction();
        connection.setUseCaches(false);
        connection.getUseCaches();
        connection.setIfModifiedSince(0);
        connection.getIfModifiedSince();
        connection.setDefaultUseCaches(false);
        connection.getDefaultUseCaches();
        connection.setRequestProperty("consumer", "old", true);
        connection.setRequestPropertyIgnoringOverride("consumer", "old");
        connection.setRequestProperty("consumer", "old");
        connection.addRequestProperty("consumer", "old");
        connection.getRequestProperty("consumer");
        connection.getRequestProperties();
        connection.connect();
        connection.getResponseCode();
        connection.getResponseMessage();
        connection.getURL();
        connection.getPermission();
        connection.getHeaderFieldKey(0);
        connection.getHeaderField(0);
        connection.getHeaderField("consumer");
        connection.getHeaderFields();
        connection.getHeaderFieldDate("date", 0);
        connection.getHeaderFieldInt("length", 0);
        connection.getHeaderFieldLong("length", 0);
        connection.getContentLength();
        connection.getContentLengthLong();
        connection.getContentType();
        connection.getContentEncoding();
        connection.getExpiration();
        connection.getDate();
        connection.getLastModified();
        connection.getContent();
        connection.getContent(new Class<?>[]{String.class});
        connection.getInputStream();
        connection.getOutputStream();
        connection.getErrorStream();
        connection.toString();
        connection.disconnect();
    }
}
