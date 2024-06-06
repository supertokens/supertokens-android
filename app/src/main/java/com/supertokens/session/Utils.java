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
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.jetbrains.annotations.TestOnly;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import okhttp3.Response;

public class Utils {
    static final String PACKAGE_PLATFORM = "android";

    static class Unauthorised {
        UnauthorisedStatus status;
        IOException error;

        enum UnauthorisedStatus {
            SESSION_EXPIRED,
            API_ERROR,
            RETRY,
        }

        Unauthorised(UnauthorisedStatus status) {
            this.status = status;
        }

        Unauthorised(UnauthorisedStatus status, IOException error) {
            this.status = status;
            this.error = error;
        }
    }

    public enum TokenType {
        ACCESS, REFRESH
    }

    enum LocalSessionStateStatus {
        NOT_EXISTS, EXISTS
    }

    static class LocalSessionState {
        LocalSessionStateStatus status;
        @Nullable
        String lastAccessTokenUpdate;

        LocalSessionState(LocalSessionStateStatus status, @Nullable String lastAccessTokenUpdate) {
            this.status = status;
            this.lastAccessTokenUpdate = lastAccessTokenUpdate;
        }
    }

    public static class NormalisedInputType {
        String apiDomain;
        String apiBasePath;
        int sessionExpiredStatusCode;

        /**
         * This specifies the maximum number of times the interceptor will attempt to refresh
         * the session  when a 401 Unauthorized response is received. If the number of retries
         * exceeds this limit, no further attempts will be made to refresh the session, and
         * and an error will be thrown.
         */
        int maxRetryAttemptsForSessionRefresh;
        String sessionTokenBackendDomain;
        CustomHeaderProvider customHeaderMapper;
        EventHandler eventHandler;
        String tokenTransferMethod;

        // TODO NEMI: Handle pre API and on handle event
        public NormalisedInputType(
                String apiDomain,
                String apiBasePath,
                int sessionExpiredStatusCode,
                int maxRetryAttemptsForSessionRefresh,
                String sessionTokenBackendDomain,
                String tokenTransferMethod,
                CustomHeaderProvider customHeaderMapper,
                EventHandler eventHandler) {
            this.apiDomain = apiDomain;
            this.apiBasePath = apiBasePath;
            this.sessionExpiredStatusCode = sessionExpiredStatusCode;
            this.maxRetryAttemptsForSessionRefresh = maxRetryAttemptsForSessionRefresh;
            this.sessionTokenBackendDomain = sessionTokenBackendDomain;
            this.customHeaderMapper = customHeaderMapper;
            this.eventHandler = eventHandler;
            this.tokenTransferMethod = tokenTransferMethod;
        }

        static String sessionScopeHelper(String sessionScope) throws MalformedURLException {
            String trimmedSessionScope = sessionScope.trim().toLowerCase();

            // first we convert it to a URL so that we can use the URL class
            if (trimmedSessionScope.startsWith(".")) {
                trimmedSessionScope = trimmedSessionScope.substring(1);
            }

            if (!trimmedSessionScope.startsWith("http://") && !trimmedSessionScope.startsWith("https://")) {
                trimmedSessionScope = "http://" + trimmedSessionScope;
            }

            try {
                URI urlObj = new URI(trimmedSessionScope);
                trimmedSessionScope = urlObj.getHost();

                return trimmedSessionScope;
            } catch (Exception e) {
                throw new MalformedURLException("Please provide a valid sessionScope");
            }
        }

        @TestOnly
        public static String normaliseSessionScopeOrThrowErrorForTests(String sessionScope)
                throws MalformedURLException {
            return normaliseSessionScopeOrThrowError(sessionScope);
        }

        private static String normaliseSessionScopeOrThrowError(String sessionScope) throws MalformedURLException {
            sessionScope = sessionScope.trim().toLowerCase();
            String noDotNormalised = sessionScopeHelper(sessionScope);

            if (noDotNormalised.equals("localhost") || NormalisedURLDomain.isAnIpAddress(noDotNormalised)) {
                return noDotNormalised;
            }

            if (sessionScope.startsWith(".")) {
                return "." + noDotNormalised;
            }

            return noDotNormalised;
        }

        public static NormalisedInputType normaliseInputOrThrowError(
                String apiDomain,
                @Nullable String apiBasePath,
                @Nullable Integer sessionExpiredStatusCode,
                @Nullable Integer maxRetryAttemptsForSessionRefresh,
                @Nullable String sessionTokenBackendDomain,
                @Nullable String tokenTransferMethod,
                @Nullable CustomHeaderProvider customHeaderProvider,
                @Nullable EventHandler eventHandler) throws MalformedURLException {
            String _apiDomain = new NormalisedURLDomain(apiDomain).getAsStringDangerous();
            String _apiBasePath = new NormalisedURLPath("/auth").getAsStringDangerous();

            if (apiBasePath != null) {
                _apiBasePath = new NormalisedURLPath(apiBasePath).getAsStringDangerous();
            }

            int _sessionExpiredStatusCode = 401;
            if (sessionExpiredStatusCode != null) {
                _sessionExpiredStatusCode = sessionExpiredStatusCode;
            }

            int _maxRetryAttemptsForSessionRefresh = 10;
            if (maxRetryAttemptsForSessionRefresh != null) {
                _maxRetryAttemptsForSessionRefresh = maxRetryAttemptsForSessionRefresh;
            }

            String _sessionTokenBackendDomain = null;
            if (sessionTokenBackendDomain != null) {
                _sessionTokenBackendDomain = normaliseSessionScopeOrThrowError(sessionTokenBackendDomain);
            }

            CustomHeaderProvider _customHeaderProvider = new CustomHeaderProvider.DefaultCustomHeaderMapper();
            if (customHeaderProvider != null) {
                _customHeaderProvider = customHeaderProvider;
            }

            EventHandler _eventHandler = new EventHandler.DefaultEventHandler();
            if (eventHandler != null) {
                _eventHandler = eventHandler;
            }

            String _tokenTransferMethod = "header";

            if (tokenTransferMethod != null && tokenTransferMethod.equalsIgnoreCase("cookie")) {
                _tokenTransferMethod = tokenTransferMethod;
            }

            return new NormalisedInputType(_apiDomain, _apiBasePath, _sessionExpiredStatusCode, _maxRetryAttemptsForSessionRefresh,
                    _sessionTokenBackendDomain, _tokenTransferMethod, _customHeaderProvider, _eventHandler);
        }
    }

    public static void storeInStorage(String name, String value, Context context) {
        String storageKey = "st-storage-item-" + name;
        SharedPreferences sharedPreferences = getSharedPreferences(context);

        if (value.isEmpty()) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.remove(storageKey);
            editor.apply();
            return;
        }

        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(storageKey, value)
                .apply();
    }

    public static void saveLastAccessTokenUpdate(Context context) {
        String now = "" + System.currentTimeMillis();

        storeInStorage(Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY, now, context);
        storeInStorage("sIRTFrontend", "", context);
    }

    public static String getFromStorage(String name, Context context) {
        return getSharedPreferences(context).getString("st-storage-item-" + name, null);
    }

    public static LocalSessionState getLocalSessionState(Context context) {
        String lastAccessTokenUpdate = getFromStorage(Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY, context);
        boolean frontTokenExists = FrontToken.doesTokenExist(context);

        if (frontTokenExists && lastAccessTokenUpdate != null) {
            return new LocalSessionState(LocalSessionStateStatus.EXISTS, lastAccessTokenUpdate);
        }

        return new LocalSessionState(LocalSessionStateStatus.NOT_EXISTS, null);
    }

    public static String getStorageName(TokenType tokenType) {
        if (tokenType == TokenType.ACCESS) {
            return Constants.ACCESS_TOKEN_PREFS_KEY;
        }

        return Constants.REFRESH_TOKEN_PREFS_KEY;
    }

    public static void setToken(TokenType tokenType, String value, Context context) {
        String name = getStorageName(tokenType);
        storeInStorage(name, value, context);
    }

    public static void saveTokenFromHeaders(SuperTokensCustomHttpURLConnection connection, Context context) {
        String refreshToken = connection.getHeaderField(Constants.REFRESH_TOKEN_HEADER_KEY);

        if (refreshToken != null) {
            setToken(TokenType.REFRESH, refreshToken, context);
        }

        String accessToken = connection.getHeaderField(Constants.ACCESS_TOKEN_HEADER_KEY);

        if (accessToken != null) {
            setToken(TokenType.ACCESS, accessToken, context);
        }

        String frontToken = connection.getHeaderField(Constants.FRONT_TOKEN_HEADER_KEY);

        if (frontToken != null) {
            FrontToken.setItem(context, frontToken);
        }

        String antiCSRF = connection.getHeaderField(Constants.CSRF_HEADER_KEY);
        if (antiCSRF != null) {
            LocalSessionState localSessionState = getLocalSessionState(context);
            AntiCSRF.setToken(context, localSessionState.lastAccessTokenUpdate, antiCSRF);
        }
    }

    public static void saveTokenFromHeaders(Response response, Context context) {
        String refreshToken = response.header(Constants.REFRESH_TOKEN_HEADER_KEY);

        if (refreshToken != null) {
            setToken(TokenType.REFRESH, refreshToken, context);
        }

        String accessToken = response.header(Constants.ACCESS_TOKEN_HEADER_KEY);

        if (accessToken != null) {
            setToken(TokenType.ACCESS, accessToken, context);
        }

        String frontToken = response.header(Constants.FRONT_TOKEN_HEADER_KEY);

        if (frontToken != null) {
            FrontToken.setItem(context, frontToken);
        }

        String antiCSRF = response.header(Constants.CSRF_HEADER_KEY);
        if (antiCSRF != null) {
            LocalSessionState localSessionState = getLocalSessionState(context);
            AntiCSRF.setToken(context, localSessionState.lastAccessTokenUpdate, antiCSRF);
        }
    }

    public static String getTokenForHeaderAuth(TokenType tokenType, Context context) {
        String name = getStorageName(tokenType);
        return getFromStorage(name, context);
    }

    public static Map<String, String> getAuthorizationHeaderIfRequired(Context context) {
        return getAuthorizationHeaderIfRequired(false, context);
    }

    // Checks if a key exists in a map regardless of case
    public static <T> T getIgnoreCase(Map<String, T> map, String key) {
        for (Map.Entry<String, T> entry : map.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key))
                return entry.getValue();
        }
        return null;
    }

    public static Map<String, String> getAuthorizationHeaderIfRequired(boolean addRefreshToken, Context context) {
        // We set the Authorization header even if the tokenTransferMethod preference
        // set in the config is cookies
        // since the active session may be using cookies. By default, we want to allow
        // users to continue these sessions.
        // The new session preference should be applied at the start of the next
        // session, if the backend allows it.
        Map<String, String> headers = new HashMap<>();
        String accessToken = getTokenForHeaderAuth(TokenType.ACCESS, context);
        String refreshToken = getTokenForHeaderAuth(TokenType.REFRESH, context);

        // We don't always need the refresh token because that's only required by the
        // refresh call
        // Still, we only add the Authorization header if both are present, because we
        // are planning to add an option to expose the
        // access token to the frontend while using cookie based auth - so that users
        // can get the access token to use
        if (accessToken != null && refreshToken != null) {
            if (getIgnoreCase(headers, "Authorization") != null) {
                // no-op
            } else {
                String tokenToAdd = addRefreshToken ? refreshToken : accessToken;
                headers.put("Authorization", "Bearer " + tokenToAdd);
            }
        }

        return headers;
    }

    public static void fireSessionUpdateEventsIfNecessary(
            boolean wasLoggedIn,
            int status,
            String frontTokenHeaderFromResponse) {
        // In case we've received a 401 that didn't clear the session (e.g.: we've sent
        // no session token, or we should try refreshing)
        // then onUnauthorised will handle firing the UNAUTHORISED event if necessary
        // In some rare cases (where we receive a 401 that also clears the session) this
        // will fire the event twice.
        // This may be considered a bug, but it is the existing behaviour before the
        // rework
        if (frontTokenHeaderFromResponse == null) {
            return;
        }

        boolean frontTokenExistsAfter = !frontTokenHeaderFromResponse.equalsIgnoreCase("remove");

        if (wasLoggedIn) {
            // we check for wasLoggedIn cause we don't want to fire an event
            // unnecessarily on first app load or if the user tried
            // to query an API that returned 401 while the user was not logged in...
            if (!frontTokenExistsAfter) {
                if (status == SuperTokens.config.sessionExpiredStatusCode) {
                    SuperTokens.config.eventHandler.handleEvent(EventHandler.EventType.UNAUTHORISED);
                } else {
                    SuperTokens.config.eventHandler.handleEvent(EventHandler.EventType.SIGN_OUT);
                }
            }
        } else if (frontTokenExistsAfter) {
            SuperTokens.config.eventHandler.handleEvent(EventHandler.EventType.SESSION_CREATED);
        }
    }

    public static String join(AbstractCollection<String> s, String delimiter) {
        if (s == null || s.isEmpty())
            return "";
        Iterator<String> iter = s.iterator();
        StringBuilder builder = new StringBuilder(iter.next());
        while (iter.hasNext()) {
            builder.append(delimiter).append(iter.next());
        }
        return builder.toString();
    }

    public static String getHostWithProtocolFromURL(URL url) {
        String host = url.getHost();

        String portSuffix = "";

        if (url.getPort() != -1) {
            portSuffix = ":" + url.getPort();
        }

        return host + portSuffix;
    }

    private static boolean isNumeric(String string) {
        try {
            Integer.parseInt(string);
            return true;
        } catch (Exception ignored) {
        }

        try {
            Float.parseFloat(string);
            return true;
        } catch (Exception ignored) {
        }

        return false;
    }

    public static boolean shouldDoInterceptionBasedOnUrl(String toCheckUrl, String apiDomain,
            @Nullable String cookieDomain) throws MalformedURLException {
        String _toCheckUrl = new NormalisedURLDomain(toCheckUrl).getAsStringDangerous();
        URL url = new URL(_toCheckUrl);
        String domain = url.getHost();

        boolean apiDomainAndInputDomainMatch = false;
        if (!apiDomain.equals("")) {
            String _apiDomain = new NormalisedURLDomain(apiDomain).getAsStringDangerous();
            URL apiDomainUrlObj = new URL(_apiDomain);
            apiDomainAndInputDomainMatch = apiDomainUrlObj.getHost().equals(domain);
        }

        if (cookieDomain == null || apiDomainAndInputDomainMatch) {
            return apiDomainAndInputDomainMatch;
        } else {
            String normalisedCookieDomain = NormalisedInputType.normaliseSessionScopeOrThrowError(cookieDomain);

            return matchesDomainOrSubdomain(domain, normalisedCookieDomain);
        }
    }

    private static boolean matchesDomainOrSubdomain(String hostname, String str) {
        String[] parts = hostname.split("\\.");

        for (int i = 0; i < parts.length; i++) {
            StringBuilder subdomainCandidate = new StringBuilder();
            for (int j = i; j < parts.length; j++) {
                subdomainCandidate.append(parts[j]);
                if (j < parts.length - 1) {
                    subdomainCandidate.append(".");
                }
            }
            if (subdomainCandidate.toString().equals(str) || ("." + subdomainCandidate.toString()).equals(str)) {
                return true;
            }
        }

        return false;
    }

    static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE);
    }
}
