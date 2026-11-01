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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.TestOnly;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Map;

public class SuperTokens {
    static String refreshTokenUrl;
    static String signOutUrl;
    static volatile boolean isInitCalled = false;
    static String rid;
    static Utils.NormalisedInputType config;
    static WeakReference<Context> contextWeakReference;

    @SuppressWarnings("unused")
    private static void init(
            Context applicationContext,
            @NonNull String apiDomain,
            @Nullable String apiBasePath,
            @Nullable Integer sessionExpiredStatusCode,
            @Nullable Integer maxRetryAttemptsForSessionRefresh,
            @Nullable String sessionTokenBackendDomain,
            @Nullable String tokenTransferMethod,
            @Nullable CustomHeaderProvider customHeaderProvider,
            @Nullable EventHandler eventHandler
    ) throws MalformedURLException {
        SessionCommitResult result;
        SessionState.beginMutation();
        try {
            if (isInitCalled) {
                try {
                    android.util.Log.w("SuperTokens", "Already initialized; ignoring repeated build. Use reconfigure() to change configuration.");
                } catch (RuntimeException ignored) {
                    // Logging must not turn the legacy no-op into a failure.
                }
                return;
            }
            Utils.NormalisedInputType values = Utils.NormalisedInputType.normaliseInputOrThrowError(
                    apiDomain, apiBasePath, sessionExpiredStatusCode, maxRetryAttemptsForSessionRefresh,
                    sessionTokenBackendDomain, tokenTransferMethod, customHeaderProvider, eventHandler);
            // The legacy testapp resets only isInitCalled and supplies a fresh preferences context.
            if (config != null) SessionState.resetForTests();
            result = configureLocked(applicationContext, values, false);
        } finally { SessionState.endMutation(); }
        SessionState.dispatch(result);
    }

    private static SessionCommitResult configure(Context context, Utils.NormalisedInputType values,
                                                  boolean reconfigure) {
        SessionCommitResult result;
        SessionState.beginMutation();
        try {
            result = configureLocked(context, values, reconfigure);
        } finally { SessionState.endMutation(); }
        SessionState.dispatch(result);
        return result;
    }

    private static SessionCommitResult configureLocked(Context context, Utils.NormalisedInputType values,
                                                        boolean reconfigure) {
        SessionCommitResult result = SessionState.configure(context, values, reconfigure);
        SessionState.Configuration captured = SessionState.configuration();
        Utils.NormalisedInputType active = captured.values;
        SuperTokens.config = new Utils.NormalisedInputType(active.apiDomain, active.apiBasePath,
                active.sessionExpiredStatusCode, active.maxRetryAttemptsForSessionRefresh,
                active.sessionTokenBackendDomain, active.tokenTransferMethod,
                active.customHeaderMapper, active.eventHandler);
        Context applicationContext = context.getApplicationContext();
        contextWeakReference = new WeakReference<>(applicationContext == null ? context : applicationContext);
        SuperTokens.refreshTokenUrl = captured.refreshTokenUrl;
        SuperTokens.signOutUrl = captured.signOutUrl;
        SuperTokens.rid = "session";
        SuperTokens.isInitCalled = true;
        return result;
    }

    @TestOnly
    public static void resetForTests() {
        SessionState.beginMutation();
        try {
            SessionState.resetForTests();
            SuperTokens.isInitCalled = false;
            SuperTokens.config = null;
            SuperTokens.refreshTokenUrl = null;
            SuperTokens.signOutUrl = null;
            SuperTokens.rid = null;
            SuperTokens.contextWeakReference = null;

        } finally { SessionState.endMutation(); }
    }

    @TestOnly
    public static void setTokenTransferMethod(String tokenTransferMethod) {
        SessionState.beginMutation();
        try {
            SessionState.setTokenTransferMethodForTests(tokenTransferMethod);
            config.tokenTransferMethod = tokenTransferMethod;

        } finally { SessionState.endMutation(); }
    }

    static String getApiDomain(@NonNull String url) throws MalformedURLException {
        if ( url.startsWith("http://") || url.startsWith("https://") ) {
            String[] splitArray = url.split("/");
            ArrayList<String> apiDomainArray = new ArrayList<String>();
            for(int i=0; i<=2; i++) {
                try {
                    apiDomainArray.add(splitArray[i]);
                } catch(IndexOutOfBoundsException e) {
                    throw new MalformedURLException("Invalid URL provided for refresh token endpoint");
                }
            }
            return Utils.join(apiDomainArray, "/");
        } else {
            throw new MalformedURLException("Refresh token endpoint must start with http or https");
        }
    }

    @SuppressWarnings("unused")
    public static boolean doesSessionExist(Context context) {
        if (!isInitCalled) {
            JSONObject front = FrontToken.getTokenInfo(context);
            if (front == null) return false;
            try {
                return front.getLong("ate") >= System.currentTimeMillis();
            } catch (JSONException e) {
                return false;
            }
        }
        SessionSnapshot captured = getSessionSnapshot(context);
        return resolveLegacyAccessToken(context, captured.getOwner(), false, null).isSuccess();
    }

    @SuppressWarnings("unused")
    public static void signOut(Context context) throws IOException, IllegalAccessException, SuperTokensGeneralError {
        final SessionRequestTag tag = captureRequestTag(context);
        final SessionState.Configuration captured = tag.configuration;
        SessionResolution resolution = resolveLegacyAccessToken(context, tag.getOwner(), false, null);
        if (!resolution.isSuccess()) {
            if (resolution.getStatus() == SessionResolution.Status.STALE_OWNER || resolution.pendingReplacement) return;
            if (resolution.legacyHandlerFailure) {
                // Published signOut treats a failed proactive session check as absent, without clearing credentials.
                SessionState.dispatch(new LegacyEventBatch(captured.values.eventHandler, EventHandler.EventType.SIGN_OUT));
                return;
            }
            if (resolution.getError() != null) throw resolution.getError();
            if (resolution.getStatus() != SessionResolution.Status.NO_SESSION) {
                throw new IOException("Could not resolve session for sign out");
            }
            SessionState.notifyAbsentSignOutIfCurrent(new SessionRequestTag(resolution.getSnapshot(), captured));
            return;
        }

        SessionRequestTag responseTag = new SessionRequestTag(resolution.getSnapshot(), captured);
        Map<String, String> provided = captured.values.customHeaderMapper
                .getRequestHeaders(CustomHeaderProvider.RequestType.SIGN_OUT);
        Map<String, String> headers = provided == null ? java.util.Collections.<String, String>emptyMap()
                : new java.util.HashMap<>(provided);
        int attempts = 0;
        while (signOutRequest(context, responseTag, headers)) {
            if (!SessionState.isCurrent(tag.getOwner(), captured)) return;
            if (attempts >= captured.values.maxRetryAttemptsForSessionRefresh) {
                throw new IllegalAccessException(SessionTransport.retryLimitMessage(captured.signOutUrl,
                        captured.values.maxRetryAttemptsForSessionRefresh));
            }
            resolution = RefreshCoordinator.resolve(context, tag.getOwner(),
                    SessionTransport.access(responseTag.getSnapshot()) == null,
                    SessionTransport.access(responseTag.getSnapshot()),
                    SuperTokensHttpURLConnection.refreshTransport(), responseTag.getSnapshot().getRefreshTokenRevision(), true);
            if (!resolution.isSuccess()) {
                if (resolution.getStatus() == SessionResolution.Status.STALE_OWNER || resolution.pendingReplacement) return;
                if (resolution.getError() != null) throw resolution.getError();
                if (resolution.getStatus() == SessionResolution.Status.NO_SESSION) return;
                throw new IOException("Could not resolve session for sign out");
            }
            responseTag = SessionTransport.nextWire(tag, false);
            if (responseTag == null) return;
            attempts++;
        }
    }

    // Return a retry decision only after closing the rejected response; its headers cannot clear state.
    private static boolean signOutRequest(Context context, SessionRequestTag responseTag,
                                          Map<String, String> headers)
            throws IOException, IllegalAccessException, SuperTokensGeneralError {
        SessionState.Configuration captured = responseTag.configuration;
        URL origin = new URL(captured.signOutUrl);
        URL destination = origin;
        String method = "POST";
        for (int redirects = 0; ; redirects++) {
            if (!SessionTransport.isResponseInScope(origin.toString(), destination.toString(), captured)) {
                throw new IOException("Sign out redirect outside captured session scope");
            }
            SignOutRedirect result = signOutWire(context, responseTag, headers, destination, method, redirects < 20);
            if (result.location == null) return result.retry;
            destination = new URL(destination, result.location);
            if (result.code == 301 || result.code == 302 || result.code == 303) method = "GET";
        }
    }

    private static final class SignOutRedirect {
        final String location;
        final int code;
        final boolean retry;

        SignOutRedirect(String location, int code, boolean retry) {
            this.location = location;
            this.code = code;
            this.retry = retry;
        }
    }

    private static SignOutRedirect signOutWire(Context context, SessionRequestTag responseTag,
                                               Map<String, String> headers, URL destination, String method,
                                               boolean mayRedirect)
            throws IOException, IllegalAccessException, SuperTokensGeneralError {
        SessionState.Configuration captured = responseTag.configuration;
        if (!SessionState.isCurrent(responseTag.getOwner(), captured)) return new SignOutRedirect(null, 0, false);
        HttpURLConnection connection = (HttpURLConnection) destination.openConnection();
        java.io.InputStream responseBody = null;
        try {
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout((int) RefreshCoordinator.NETWORK_TIMEOUT_MS);
            connection.setReadTimeout((int) RefreshCoordinator.NETWORK_TIMEOUT_MS);
            if (method.equals("POST")) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(0);
            }
            for (Map.Entry<String, String> header : headers.entrySet()) {
                String name = header.getKey();
                if (name.equalsIgnoreCase("Authorization") || name.equalsIgnoreCase(Constants.CSRF_HEADER_KEY)
                        || name.equalsIgnoreCase("rid") || name.equalsIgnoreCase("st-auth-mode")
                        || name.equalsIgnoreCase("Host") || name.equalsIgnoreCase("Content-Length")
                        || name.equalsIgnoreCase("Transfer-Encoding")) continue;
                connection.setRequestProperty(name, header.getValue());
            }
            connection.setRequestProperty("rid", "session");
            connection.setRequestProperty("st-auth-mode", captured.values.tokenTransferMethod);
            SessionTokens tokens = responseTag.getSnapshot().getInternalTokens();
            String authorization = SessionTransport.authorization(responseTag.getSnapshot(), false);
            if (authorization != null) connection.setRequestProperty("Authorization", authorization);
            if (tokens != null && tokens.getAntiCSRF() != null) {
                connection.setRequestProperty(Constants.CSRF_HEADER_KEY, tokens.getAntiCSRF());
            }
            // Ambient cookie jars remain outside process-owner credential fencing.
            if (!SessionState.isCurrent(responseTag.getOwner(), captured)) return new SignOutRedirect(null, 0, false);
            if (method.equals("POST")) connection.getOutputStream().close();
            int responseCode = connection.getResponseCode();
            if (!SessionTransport.isResponseInScope(destination.toString(), connection.getURL().toString(), captured)) {
                throw new IOException("Sign out response outside captured session scope");
            }
            if (responseCode == 301 || responseCode == 302 || responseCode == 303
                    || responseCode == 307 || responseCode == 308) {
                if (!SessionState.isCurrent(responseTag.getOwner(), captured)) return new SignOutRedirect(null, 0, false);
                String location = connection.getHeaderField("Location");
                if (location == null || location.trim().isEmpty()) throw new IOException("Sign out redirect missing Location");
                if (!mayRedirect) throw new IOException("Sign out redirect limit exceeded");
                URL next = new URL(destination, location);
                if (!SessionTransport.isResponseInScope(captured.signOutUrl, next.toString(), captured)) {
                    throw new IOException("Sign out redirect outside captured session scope");
                }
                SuperTokensHttpURLConnection.manuallySetCookiesFromResponse(destination, connection, captured);
                if (!SessionState.isCurrent(responseTag.getOwner(), captured)) return new SignOutRedirect(null, 0, false);
                return new SignOutRedirect(location, responseCode, false);
            }
            SessionResponse response = new SessionResponse(responseCode, connection.getResponseMessage(),
                    connection.getHeaderFields());
            responseBody = responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (responseCode == captured.values.sessionExpiredStatusCode) {
                if (!SessionState.isCurrent(responseTag.getOwner(), captured)) return new SignOutRedirect(null, 0, false);
                try {
                    if (!response.isRemoval()) {
                        return new SignOutRedirect(null, 0, true);
                    }
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid sign out response", e);
                }
                if (!applySignOutResponse(context, responseTag, response, false)) return new SignOutRedirect(null, 0, false);
                if (responseTag.getAcceptedSnapshot() == null) {
                    throw new IOException("Sign out removal was superseded by newer session credentials");
                }
                SuperTokensHttpURLConnection.manuallySetCookiesFromResponse(destination, connection, captured);
                return new SignOutRedirect(null, 0, false);
            }
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("Sign out failed with response code " + responseCode);
            }
            // Published sign-out commits response headers even when body parsing subsequently fails.
            // A stale response still has legacy body/error semantics, but cannot mutate the replacement session.
            if (applySignOutResponse(context, responseTag, response, false)) {
                SuperTokensHttpURLConnection.manuallySetCookiesFromResponse(destination, connection, captured);
            }
            try {
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line).append('\n');
                    }
                }
                JSONObject jsonBody = new JSONObject(new JSONTokener(body.toString()));
                String status = jsonBody.getString("status");
                if (status.equals("GENERAL_ERROR")) {
                    throw new SuperTokensGeneralError(jsonBody.getString("message"));
                }
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            if (responseTag.getAcceptedSnapshot() == null) applySignOutResponse(context, responseTag, response, true);
            return new SignOutRedirect(null, 0, false);
        } finally {
            java.io.InputStream stream = responseBody == null ? connection.getErrorStream() : responseBody;
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // Preserve the sign-out outcome if closing the body fails.
                }
            }
            connection.disconnect();
        }
    }

    private static boolean applySignOutResponse(Context context, SessionRequestTag tag,
                                             SessionResponse response, boolean confirmed) throws IOException {
        try {
            response = response.forSignOut();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid sign out response", e);
        }
        SessionCommitResult result = confirmed
                ? SessionState.applyConfirmedSignOutResponse(context, tag, response)
                : SessionState.applyOrdinaryResponse(context, tag, response);
        SessionState.dispatch(result);
        if (result.getStatus() == SessionCommitResult.Status.STALE_OWNER) return false;
        if (!result.isSuccess()) {
            if (result.getPersistenceError() != null) throw result.getPersistenceError();
            throw new IOException("Could not accept sign out response");
        }
        SessionSnapshot accepted = tag.getAcceptedSnapshot();
        SessionOwner owner = accepted == null ? tag.getOwner() : accepted.getOwner();
        return SessionState.isCurrent(owner, tag.configuration);
    }

    public static boolean attemptRefreshingSession(Context context) throws IOException, IllegalAccessException {
        if (!SuperTokens.isInitCalled) {
            throw new IllegalAccessException("SuperTokens.init function not called");
        }

        SessionRequestTag captured = SessionState.capture();
        LegacyEventBatch absent = SessionState.absentUnauthorised(captured);
        SessionResolution result = resolveLegacyAccessToken(context, captured.getOwner(), true, null);
        if (result.getStatus() == SessionResolution.Status.NO_SESSION) SessionState.dispatch(absent);
        if (result.getStatus() == SessionResolution.Status.STALE_OWNER || result.pendingReplacement) return false;
        if (result.getError() != null) throw result.getError();
        return result.isSuccess();
    }

    public static String getUserId(Context context) {
        JSONObject tokenInfo = FrontToken.getTokenInfo(context);
        if (tokenInfo == null) {
            throw new IllegalStateException("No session exists");
        }

        try {
            return tokenInfo.getString("uid");
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static JSONObject getAccessTokenPayloadSecurely(Context context) throws IOException {
        if (!isInitCalled) {
            JSONObject front = FrontToken.getTokenInfo(context);
            if (front == null) throw new IllegalStateException("No session exists");
            try {
                if (front.getLong("ate") < System.currentTimeMillis()) {
                    // Published pre-init refresh fails this guard before any network request.
                    throw new IllegalStateException(new IllegalAccessException("SuperTokens.init function not called"));
                }
                return front.getJSONObject("up");
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
        SessionSnapshot captured = getSessionSnapshot(context);
        if (captured.getStatus() != SessionSnapshot.Status.AVAILABLE) {
            throw new IllegalStateException("No session exists");
        }
        SessionResolution result = resolveLegacyAccessToken(context, captured.getOwner(), false, null);
        if (result.isSuccess()) return result.getSnapshot().getAccessTokenPayload();
        if (result.getStatus() == SessionResolution.Status.STALE_OWNER) {
            throw new IOException("Could not refresh session");
        }
        if (result.getError() != null) throw result.getError();
        throw new IOException("Could not refresh session");
    }

    public static String getAccessToken(Context context) {
        if (!isInitCalled) return doesSessionExist(context)
                ? Utils.getTokenForHeaderAuth(Utils.TokenType.ACCESS, context) : null;
        SessionSnapshot captured = getSessionSnapshot(context);
        SessionResolution result = resolveLegacyAccessToken(context, captured.getOwner(), false, null);
        return result.isSuccess() ? SessionTransport.access(result.getSnapshot()) : null;
    }

    public static SessionSnapshot getSessionSnapshot(Context context) {
        return SessionState.snapshot();
    }

    public static SessionCommitResult installSession(Context context, SessionOwner expectedOwner,
                                                       SessionTokens tokens) {
        return installSession(context, expectedOwner, tokens, null);
    }

    public static SessionCommitResult installSession(Context context, SessionOwner expectedOwner,
                                                       SessionTokens tokens, String adoptionCorrelation) {
        SessionCommitResult result = SessionState.install(context, expectedOwner, tokens, adoptionCorrelation);
        SessionState.dispatch(result);
        return result;
    }

    public static SessionCommitResult clearSession(Context context, SessionOwner expectedOwner) {
        SessionCommitResult result = SessionState.clear(context, expectedOwner);
        SessionState.dispatch(result);
        return result;
    }

    public static SessionResolution resolveAccessToken(Context context, SessionOwner expectedOwner,
                                                       boolean forceRefresh, @Nullable String failedAccessToken) {
        return RefreshCoordinator.resolve(context, expectedOwner, forceRefresh, failedAccessToken,
                SuperTokensHttpURLConnection.refreshTransport());
    }

    private static SessionResolution resolveLegacyAccessToken(Context context, SessionOwner expectedOwner,
                                                              boolean forceRefresh, String failedAccessToken) {
        return RefreshCoordinator.resolve(context, expectedOwner, forceRefresh, failedAccessToken,
                SuperTokensHttpURLConnection.refreshTransport(), true);
    }

    public static SessionRequestTag captureRequestTag(Context context) {
        return SessionState.capture();
    }

    public static SessionSubscription observeSessions(Context context, SessionObserver observer) {
        return SessionState.observe(observer);
    }

    public static class Builder {
        String apiDomain;
        Context applicationContext;
        String apiBasePath;
        Integer sessionExpiredStatusCode;
        Integer maxRetryAttemptsForSessionRefresh;
        String sessionTokenBackendDomain;
        CustomHeaderProvider customHeaderProvider;
        EventHandler eventHandler;
        String tokenTransferMethod;

        public Builder(Context applicationContext, String apiDomain) {
            this.apiDomain = apiDomain;
            this.applicationContext = applicationContext;
        }

        public Builder apiBasePath(String apiBasePath) {
            this.apiBasePath = apiBasePath;
            return this;
        }

        public Builder sessionExpiredStatusCode(Integer sessionExpiredStatusCode) {
            this.sessionExpiredStatusCode = sessionExpiredStatusCode;
            return this;
        }

        public Builder maxRetryAttemptsForSessionRefresh(Integer maxRetryAttemptsForSessionRefresh) {
            this.maxRetryAttemptsForSessionRefresh = maxRetryAttemptsForSessionRefresh;
            return this;
        }

        public Builder sessionTokenBackendDomain(String cookieDomain) {
            this.sessionTokenBackendDomain = cookieDomain;
            return this;
        }

        public Builder customHeaderProvider(CustomHeaderProvider customHeaderProvider) {
            this.customHeaderProvider = customHeaderProvider;
            return this;
        }

        public Builder eventHandler(EventHandler eventHandler) {
            this.eventHandler = eventHandler;
            return this;
        }

        public Builder tokenTransferMethod(String tokenTransferMethod) {
            this.tokenTransferMethod = tokenTransferMethod;
            return this;
        }

        public void build() throws MalformedURLException {
            SuperTokens.init(applicationContext, apiDomain, apiBasePath, sessionExpiredStatusCode, maxRetryAttemptsForSessionRefresh, sessionTokenBackendDomain, tokenTransferMethod, customHeaderProvider, eventHandler);
        }

        public SessionCommitResult reconfigure() throws MalformedURLException {
            return SuperTokens.configure(applicationContext,
                    Utils.NormalisedInputType.normaliseInputOrThrowError(apiDomain, apiBasePath,
                            sessionExpiredStatusCode, maxRetryAttemptsForSessionRefresh,
                            sessionTokenBackendDomain, tokenTransferMethod, customHeaderProvider,
                            eventHandler), true);
        }
    }
}
