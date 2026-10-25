/*
 * Copyright (c) 2020, VRAI Labs and/or its affiliates. All rights reserved.
 *
 * This software is licensed under the Apache License, Version 2.0 (the
 * "License") as published by the Apache Software Foundation.
 *
 * You may not use this file except in compliance with the License. You may
 * obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.supertokens.session;

import android.content.Context;
import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unused")
public class SuperTokensHttpURLConnection {
    public static HttpURLConnection newRequest(URL url, PreConnectCallback callback)
            throws IllegalAccessException, IOException {
        return newRequest(url, callback, null);
    }

    public static HttpURLConnection newRequest(URL url, PreConnectCallback callback,
                                               SessionRequestTag requestTag)
            throws IllegalAccessException, IOException {
        if (!SuperTokens.isInitCalled) throw new IllegalAccessException("SuperTokens.init function needs to be called before using newRequest");
        Context context = SuperTokens.contextWeakReference.get();
        if (context == null) throw new IllegalAccessException("Context is null");
        SessionRequestTag logical = requestTag == null ? SessionState.capture() : requestTag;
        SessionState.Configuration config = logical.configuration;
        if (!Utils.shouldDoInterceptionBasedOnUrl(url.toString(), config.values.apiDomain,
                config.values.sessionTokenBackendDomain)) {
            throw new IllegalAccessException("Trying to call newRequest with a URL that cannot be handled by SuperTokens");
        }
        if (url.toString().equals(config.refreshTokenUrl)) {
            HttpURLConnection raw = (HttpURLConnection) url.openConnection();
            try {
                if (callback != null) callback.doAction(raw);
                raw.connect();
                return raw;
            } catch (IOException | RuntimeException e) {
                raw.disconnect();
                throw e;
            }
        }
        requireCookieManager(config);
        SessionRequestTag wire = new SessionRequestTag(logical.getSnapshot(), config);
        int attempts = 0;
        while (true) {
            SessionTransport.requireCurrent(logical);
            SuperTokensCustomHttpURLConnection connection = new SuperTokensCustomHttpURLConnection(
                    (HttpURLConnection) url.openConnection(), context, logical, wire, requestTag != null);
            try {
                connection.setRequestPropertyIgnoringOverride("rid", "anti-csrf");
                connection.setRequestPropertyIgnoringOverride("st-auth-mode", config.values.tokenTransferMethod);
                String auth = SessionTransport.authorization(wire.getSnapshot(), false);
                String anti = SessionTransport.antiCSRF(wire.getSnapshot());
                if (auth != null) connection.setRequestPropertyIgnoringOverride("Authorization", auth);
                if (anti != null) connection.setRequestPropertyIgnoringOverride(Constants.CSRF_HEADER_KEY, anti);
                if (callback != null) callback.doAction(connection);
                SessionTransport.requireCurrent(logical);
                connection.connect();
                int code = connection.getResponseCode();
                boolean managed = connection.isManagedAuthorization();
                boolean current = !managed || SessionTransport.apply(context, logical, wire,
                        SessionResponse.fromHttp(connection), requestTag != null);
                manuallySetCookiesFromResponse(url, connection);
                if (!managed || !current || code != config.values.sessionExpiredStatusCode
                        || !SessionState.isCurrent(logical.getOwner(), config)) return connection;
                if (attempts >= config.values.maxRetryAttemptsForSessionRefresh) {
                    throw new IllegalAccessException(SessionTransport.retryLimitMessage(url.toString(),
                            config.values.maxRetryAttemptsForSessionRefresh));
                }
                SessionResolution resolution = RefreshCoordinator.resolve(context, logical.getOwner(),
                        SessionTransport.access(wire.getSnapshot()) == null,
                        SessionTransport.access(wire.getSnapshot()), (ctx, configuration, captured) -> {
                            if (configuration != config) throw new StaleSessionException();
                            return refresh(ctx, configuration, captured);
                        }, wire.getSnapshot().getRefreshTokenRevision());
                if (!resolution.isSuccess()) {
                    if (resolution.getError() != null) throw resolution.getError();
                    return connection;
                }
                wire = SessionTransport.nextWire(logical);
                attempts++;
            } catch (IOException | IllegalAccessException | RuntimeException e) {
                connection.disconnect();
                if (e instanceof IllegalStateException && e.getCause() instanceof IOException) {
                    throw (IOException) e.getCause();
                }
                throw e;
            }
            connection.disconnect();
        }
    }

    static RefreshCoordinator.Transport refreshTransport() {
        return SuperTokensHttpURLConnection::refresh;
    }

    static SessionResponse refresh(Context context, SessionState.Configuration configuration,
                                   SessionSnapshot captured) throws IOException {
        RefreshDeadline deadline = new RefreshDeadline();
        SessionRequestTag tag = new SessionRequestTag(captured, configuration);
        SessionTransport.requireCurrent(tag);
        URL url = new URL(configuration.refreshTokenUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        java.util.concurrent.ScheduledFuture<?> timeout = null;
        try {
            requireCookieManager(configuration);
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout((int) RefreshCoordinator.NETWORK_TIMEOUT_MS);
            connection.setReadTimeout((int) RefreshCoordinator.NETWORK_TIMEOUT_MS);
            // Streaming prevents HttpURLConnection from transparently replaying this POST.
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
            connection.setRequestProperty("rid", SuperTokens.rid);
            connection.setRequestProperty("fdi-version", Utils.join(Version.supported_fdi, ","));
            connection.setRequestProperty("st-auth-mode", configuration.values.tokenTransferMethod);
            String auth = SessionTransport.authorization(captured, true);
            String anti = SessionTransport.antiCSRF(captured);
            if (auth != null) connection.setRequestProperty("Authorization", auth);
            if (anti != null) connection.setRequestProperty(Constants.CSRF_HEADER_KEY, anti);
            Map<String, String> headers = configuration.values.customHeaderMapper
                    .getRequestHeaders(CustomHeaderProvider.RequestType.REFRESH);
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase("Authorization")
                            && !SessionTransport.matches(entry.getValue(), auth)) {
                        throw new RefreshCoordinator.ConfigurationException("Refresh callback changed authorization");
                    }
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            SessionTransport.requireCurrent(tag);
            int remaining = (int) deadline.remainingMillis();
            connection.setConnectTimeout(remaining);
            connection.setReadTimeout(remaining);
            timeout = deadline.cancelAfterDeadline(connection::disconnect);
            connection.getOutputStream().close();
            int code = connection.getResponseCode();
            SessionResponse response = new SessionResponse(code, connection.getResponseMessage(), connection.getHeaderFields());
            manuallySetCookiesFromResponse(url, connection);
            deadline.remainingMillis();
            return response;
        } catch (IllegalAccessException e) {
            throw new RefreshCoordinator.ConfigurationException(e.getMessage());
        } finally {
            RefreshDeadline.finished(timeout);
            connection.disconnect();
        }
    }

    static Utils.Unauthorised onUnauthorisedResponse(Utils.LocalSessionState previous, Context context) {
        SessionRequestTag tag = SessionState.capture();
        if (previous.status == Utils.LocalSessionStateStatus.NOT_EXISTS) {
            return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
        }
        if (previous.lastAccessTokenUpdate == null || !previous.lastAccessTokenUpdate.equals(
                SessionState.lastAccessTokenUpdate(tag.getOwner()))) {
            return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.API_ERROR,
                    new StaleSessionException());
        }
        SessionResolution result = RefreshCoordinator.resolve(context, tag.getOwner(), true, null,
                SuperTokensHttpURLConnection::refresh);
        if (result.isSuccess()) return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.RETRY);
        if (result.getError() != null) return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.API_ERROR, result.getError());
        return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
    }

    private static void requireCookieManager(SessionState.Configuration config) throws IllegalAccessException {
        if (CookieManager.getDefault() == null && "cookie".equals(config.values.tokenTransferMethod)) {
            throw new IllegalAccessException("Please initialise a CookieManager with a SuperTokensPersistentCookieStore");
        }
    }

    static void manuallySetCookiesFromResponse(URL url, HttpURLConnection connection)
            throws IOException, IllegalAccessException {
        for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
            if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase("Set-Cookie")) continue;
            for (String value : entry.getValue()) {
                // Some JDK handlers hide already-consumed HttpOnly cookies as empty headers.
                if (value == null || value.trim().isEmpty()) continue;
                for (HttpCookie cookie : HttpCookie.parse(value)) {
                    if (CookieManager.getDefault() == null) {
                        // Expired cookies remove server state; they do not establish cookie authentication.
                        if (cookie.getName().equals("sAccessToken") && !cookie.hasExpired()) {
                            throw new IllegalAccessException("Please initialise a CookieManager with a SuperTokensPersistentCookieStore");
                        }
                        continue;
                    }
                    // Android misses cookies when the response URL does not start with their path.
                    String path = cookie.getPath() == null ? "/" : cookie.getPath();
                    URL cookieUrl = new URL(new NormalisedURLDomain(url.toString()).getAsStringDangerous() + path);
                    try {
                        CookieManager.getDefault().put(cookieUrl.toURI(),
                                Collections.singletonMap("Set-Cookie", Collections.singletonList(value)));
                    } catch (URISyntaxException e) {
                        throw new IOException("Could not parse cookie URL", e);
                    }
                }
            }
        }
    }

    public interface PreConnectCallback {
        void doAction(HttpURLConnection connection) throws IOException;
    }
}
