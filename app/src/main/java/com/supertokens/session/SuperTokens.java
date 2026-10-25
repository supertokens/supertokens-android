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
        Utils.NormalisedInputType values = Utils.NormalisedInputType.normaliseInputOrThrowError(
                apiDomain,
                apiBasePath,
                sessionExpiredStatusCode,
                maxRetryAttemptsForSessionRefresh,
                sessionTokenBackendDomain,
                tokenTransferMethod,
                customHeaderProvider,
                eventHandler
        );
        configure(applicationContext, values, false);
    }

    private static SessionCommitResult configure(Context context, Utils.NormalisedInputType values,
                                                  boolean reconfigure) {
        SessionCommitResult result;
        synchronized (SuperTokens.class) {
            result = SessionState.configure(context, values, reconfigure);
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
        }
        SessionState.dispatch();
        return result;
    }

    @TestOnly
    public static void resetForTests() {
        SessionState.resetForTests();
        SuperTokens.isInitCalled = false;
        SuperTokens.config = null;
        SuperTokens.refreshTokenUrl = null;
        SuperTokens.signOutUrl = null;
        SuperTokens.rid = null;
        SuperTokens.contextWeakReference = null;
    }

    @TestOnly
    public static void setTokenTransferMethod(String tokenTransferMethod) {
        Utils.NormalisedInputType active = SessionState.configuration().values;
        configure(contextWeakReference.get(), new Utils.NormalisedInputType(active.apiDomain,
                active.apiBasePath, active.sessionExpiredStatusCode,
                active.maxRetryAttemptsForSessionRefresh, active.sessionTokenBackendDomain,
                tokenTransferMethod, active.customHeaderMapper, active.eventHandler), true);
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
        if (!isInitCalled) return false;
        SessionSnapshot captured = getSessionSnapshot(context);
        return resolveAccessToken(context, captured.getOwner(), false, null).isSuccess();
    }

    @SuppressWarnings("unused")
    public static void signOut(Context context) throws IOException, IllegalAccessException, SuperTokensGeneralError {
        final SessionRequestTag tag = captureRequestTag(context);
        final SessionState.Configuration captured = tag.configuration;
        SessionResolution resolution = resolveAccessToken(context, tag.getOwner(), false, null);
        if (!resolution.isSuccess()) {
            if (resolution.getError() != null) throw resolution.getError();
            if (resolution.getStatus() != SessionResolution.Status.NO_SESSION) {
                throw new IOException("Could not resolve session for sign out");
            }
            SessionState.notifyAbsentSignOut(new SessionRequestTag(resolution.getSnapshot(), captured));
            return;
        }

        SessionRequestTag responseTag = new SessionRequestTag(resolution.getSnapshot(), captured);
        Map<String, String> provided = captured.values.customHeaderMapper
                .getRequestHeaders(CustomHeaderProvider.RequestType.SIGN_OUT);
        Map<String, String> headers = provided == null ? java.util.Collections.emptyMap()
                : new java.util.HashMap<>(provided);
        if (!SessionState.isCurrent(responseTag.getOwner(), captured)) throw new StaleSessionException();
        HttpURLConnection connection = (HttpURLConnection) new URL(captured.signOutUrl).openConnection();
        java.io.InputStream responseBody = null;
        try {
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout((int) RefreshCoordinator.NETWORK_TIMEOUT_MS);
            connection.setReadTimeout((int) RefreshCoordinator.NETWORK_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
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
            if (tokens != null && tokens.getAccessToken() != null) {
                connection.setRequestProperty("Authorization", "Bearer " + tokens.getAccessToken());
            }
            if (tokens != null && tokens.getAntiCSRF() != null) {
                connection.setRequestProperty(Constants.CSRF_HEADER_KEY, tokens.getAntiCSRF());
            }
            // Ambient cookie jars remain outside process-owner credential fencing.
            if (!SessionState.isCurrent(responseTag.getOwner(), captured)) throw new StaleSessionException();
            connection.getOutputStream().close();
            int responseCode = connection.getResponseCode();
            if (!SessionState.isCurrent(responseTag.getOwner(), captured)) throw new StaleSessionException();
            SessionResponse response = new SessionResponse(responseCode, connection.getResponseMessage(),
                    connection.getHeaderFields());
            responseBody = responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (responseCode == captured.values.sessionExpiredStatusCode) {
                try {
                    if (!response.isRemoval()) {
                        throw new IOException("Sign out failed with response code " + responseCode);
                    }
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid sign out response", e);
                }
                SuperTokensHttpURLConnection.manuallySetCookiesFromResponse(connection.getURL(), connection);
                applySignOutResponse(context, responseTag, response);
                return;
            }
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("Sign out failed with response code " + responseCode);
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
                if (!status.equals("OK")) throw new IOException("Unexpected sign out status");
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            SuperTokensHttpURLConnection.manuallySetCookiesFromResponse(connection.getURL(), connection);
            applySignOutResponse(context, responseTag, response);
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

    private static void applySignOutResponse(Context context, SessionRequestTag tag,
                                             SessionResponse response) throws IOException {
        SessionCommitResult result = SessionState.applyOrdinaryResponse(context, tag, response);
        SessionState.dispatch();
        if (result.getStatus() == SessionCommitResult.Status.STALE_OWNER) throw new StaleSessionException();
        if (!result.isSuccess()) {
            if (result.getPersistenceError() != null) throw result.getPersistenceError();
            throw new IOException("Could not accept sign out response");
        }
        SessionSnapshot accepted = tag.getAcceptedSnapshot();
        SessionOwner owner = accepted == null ? tag.getOwner() : accepted.getOwner();
        if (!SessionState.isCurrent(owner, tag.configuration)) throw new StaleSessionException();
    }

    public static boolean attemptRefreshingSession(Context context) throws IOException, IllegalAccessException {
        if (!SuperTokens.isInitCalled) {
            throw new IllegalAccessException("SuperTokens.init function not called");
        }

        SessionSnapshot captured = getSessionSnapshot(context);
        SessionResolution result = resolveAccessToken(context, captured.getOwner(), true, null);
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
        SessionSnapshot captured = getSessionSnapshot(context);
        if (captured.getStatus() != SessionSnapshot.Status.AVAILABLE) {
            throw new IllegalStateException("No session exists");
        }
        SessionResolution result = resolveAccessToken(context, captured.getOwner(), false, null);
        if (result.isSuccess()) return result.getSnapshot().getAccessTokenPayload();
        if (result.getError() != null) throw result.getError();
        throw new IOException("Could not refresh session");
    }

    public static String getAccessToken(Context context) {
        if (!isInitCalled) return null;
        SessionSnapshot captured = getSessionSnapshot(context);
        SessionResolution result = resolveAccessToken(context, captured.getOwner(), false, null);
        return result.isSuccess() ? result.getAccessToken() : null;
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
        SessionState.dispatch();
        return result;
    }

    public static SessionCommitResult clearSession(Context context, SessionOwner expectedOwner) {
        SessionCommitResult result = SessionState.clear(context, expectedOwner);
        SessionState.dispatch();
        return result;
    }

    public static SessionResolution resolveAccessToken(Context context, SessionOwner expectedOwner,
                                                       boolean forceRefresh, @Nullable String failedAccessToken) {
        return RefreshCoordinator.resolve(context, expectedOwner, forceRefresh, failedAccessToken,
                SuperTokensHttpURLConnection.refreshTransport());
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
