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
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@SuppressWarnings("unused")
public class SuperTokensHttpURLConnection {
    private static final Object refreshTokenLock = new Object();
    private static final ReentrantReadWriteLock refreshAPILock = new ReentrantReadWriteLock();

    private static void setAuthorizationHeaderIfRequired(SuperTokensCustomHttpURLConnection connection, Context context) {
        Map<String, String> headersToSet = Utils.getAuthorizationHeaderIfRequired(context);
        for (Map.Entry<String, String> entry: headersToSet.entrySet()) {
            connection.setRequestProperty(entry.getKey(), entry.getValue(), true);
        }
    }

    private static void setAuthorizationHeaderIfRequiredForRefresh(HttpURLConnection connection, Context context) {
        Map<String, String> headersToSet = Utils.getAuthorizationHeaderIfRequired(true, context);
        for (Map.Entry<String, String> entry: headersToSet.entrySet()) {
            connection.setRequestProperty(entry.getKey(), entry.getValue());
        }
    }

    private static void manuallySetCookiesFromResponse(URL url, HttpURLConnection connection) throws IOException, IllegalAccessException {
        /*
            Android has a bug where it does not set cookies when the API url path
            does not start with the cookie path

            To fix this we read the Set-Cookie header and then for each cookie we create
            a URL using the same path as the cookie path and the same domain as the URL
            of the original request.

            We then set the cookies using CookieManager.getDefault. Note: We dont need to
            check for CookieManager.getDefault being null because newRequest checks this before
            using connection.connect
         */
        List<String> cookies = connection.getHeaderFields().get("Set-Cookie");

        if (cookies != null) {
            // We check if one of the cookies is the access token and throw if the Cookie Manager has not
            // been set.
            for (int i = 0; i < cookies.size(); i++) {
                HttpCookie currentCookie = HttpCookie.parse(cookies.get(i)).get(0);

                // The backend may respond with cookies instead of headers even if auth mode is
                // set to headers. In this case we should throw if a CookieManager is not set
                if (currentCookie.getName().equals("sAccessToken") && CookieManager.getDefault() == null) {
                    throw new IllegalAccessException("Please initialise a CookieManager.\n" +
                            "For example: new CookieManager(new SuperTokensPersistentCookieStore(context), null).\n" +
                            "SuperTokens provides a persistent cookie store called SuperTokensPersistentCookieStore.\n" +
                            "For more information visit our documentation.");
                }
            }

            for (int i = 0; i < cookies.size(); i++) {
                HttpCookie currentCookie = HttpCookie.parse(cookies.get(i)).get(0);

                String pathToUse = "/";

                if (currentCookie.getPath() != null) {
                    pathToUse = currentCookie.getPath();
                }

                String urlDomain = new NormalisedURLDomain(url.toString()).getAsStringDangerous();

                URL urlToUse = new URL(urlDomain + pathToUse);

                Map<String, List<String>> fakeSetCookieHeader = new HashMap<>();
                List<String> fakeCookieValue = new ArrayList<>();
                fakeCookieValue.add(cookies.get(i));

                fakeSetCookieHeader.put("Set-Cookie", fakeCookieValue);

                try {
                    CookieManager.getDefault().put(urlToUse.toURI(), fakeSetCookieHeader);
                } catch (URISyntaxException e) {
                    // Should not come here
                    throw new IllegalStateException("Could not parse response correctly");
                }
            }
        }
    }

    public static HttpURLConnection newRequest(URL url, PreConnectCallback preConnectCallback) throws IllegalAccessException, IOException {
        if ( !SuperTokens.isInitCalled ) {
            throw new IllegalAccessException("SuperTokens.init function needs to be called before using newRequest");
        }

        Context applicationContext = SuperTokens.contextWeakReference.get();
        if ( applicationContext == null ) {
            throw new IllegalAccessException("Context is null");
        }

        boolean doNotDoInterception = !Utils.shouldDoInterceptionBasedOnUrl(url.toString(), SuperTokens.config.apiDomain, SuperTokens.config.sessionTokenBackendDomain);

        if (doNotDoInterception) {
            String errorMessage = "Trying to call newRequest with a URL that cannot be handled by SuperTokens.\n";
            errorMessage += "If you are trying to use SuperTokens with multiple subdomains for your APIs, make sure you are setting cookieDomain in SuperTokens.init correctly";
            throw new IllegalAccessException(errorMessage);
        }

        try {
            int sessionRefreshAttempts = 0;
            while (true) {
                HttpURLConnection connection;
                SuperTokensCustomHttpURLConnection customConnection;
                Utils.LocalSessionState preRequestLocalSessionState;
                int responseCode;
                // TODO: write comment as to why we have this lock here. Do we also have this lock for iOS and website package?
                refreshAPILock.readLock().lock();
                try {
                    connection = (HttpURLConnection) url.openConnection();
                    customConnection = new SuperTokensCustomHttpURLConnection(connection, applicationContext);

                    // Add antiCSRF token, if present in storage, to the request headers
                    preRequestLocalSessionState = Utils.getLocalSessionState(applicationContext);
                    String antiCSRFToken = AntiCSRF.getToken(applicationContext, preRequestLocalSessionState.lastAccessTokenUpdate);

                    if (antiCSRFToken != null) {
                        customConnection.setRequestProperty(Constants.CSRF_HEADER_KEY, antiCSRFToken);
                    }

                    if (CookieManager.getDefault() == null && SuperTokens.config.tokenTransferMethod.equals("cookie")) {
                        throw new IllegalAccessException("Please initialise a CookieManager.\n" +
                                "For example: new CookieManager(new SuperTokensPersistentCookieStore(context), null).\n" +
                                "SuperTokens provides a persistent cookie store called SuperTokensPersistentCookieStore.\n" +
                                "For more information visit our documentation.");
                    }

                    if (customConnection.getRequestProperty("rid") == null) {
                        customConnection.setRequestProperty("rid", "anti-csrf");
                    }

                    customConnection.setRequestProperty("st-auth-mode", SuperTokens.config.tokenTransferMethod);
                    setAuthorizationHeaderIfRequired(customConnection, applicationContext);

                    // This will allow the user to set headers or modify request in anyway they want
                    // TODO NEMI: Replace this with pre api hook when implemented
                    if (preConnectCallback != null) {
                        preConnectCallback.doAction(customConnection);
                    }

                    customConnection.connect();

                    responseCode = customConnection.getResponseCode();
                    Utils.saveTokenFromHeaders(customConnection, applicationContext);
                    manuallySetCookiesFromResponse(url, customConnection);

                    Utils.fireSessionUpdateEventsIfNecessary(
                            preRequestLocalSessionState.status == Utils.LocalSessionStateStatus.EXISTS,
                            responseCode,
                            customConnection.getHeaderField(Constants.FRONT_TOKEN_HEADER_KEY)
                    );
                } finally {
                    refreshAPILock.readLock().unlock();
                }

                if (responseCode == SuperTokens.config.sessionExpiredStatusCode) {
                    /**
                     * An API may return a 401 error response even with a valid session, causing a session refresh loop in the interceptor.
                     * To prevent this infinite loop, we break out of the loop after retrying the original request a specified number of times.
                     * The maximum number of retry attempts is defined by maxRetryAttemptsForSessionRefresh config variable.
                     */
                    if (sessionRefreshAttempts >= SuperTokens.config.maxRetryAttemptsForSessionRefresh) {
                        String errorMsg = "Received a 401 response from " + url + ". Attempted to refresh the session and retry the request with the updated session tokens " + SuperTokens.config.maxRetryAttemptsForSessionRefresh + " times, but each attempt resulted in a 401 error. The maximum session refresh limit has been reached. Please investigate your API. To increase the session refresh attempts, update maxRetryAttemptsForSessionRefresh in the config.";
                        System.err.println(errorMsg);
                        throw new IllegalAccessException(errorMsg);
                    }

                    // Network call threw UnauthorisedAccess, try to call the refresh token endpoint and retry original call
                    Utils.Unauthorised unauthorisedResponse = SuperTokensHttpURLConnection.onUnauthorisedResponse(preRequestLocalSessionState, applicationContext);

                    sessionRefreshAttempts++;

                    if (unauthorisedResponse.status != Utils.Unauthorised.UnauthorisedStatus.RETRY) {

                        if (unauthorisedResponse.error != null) {
                            throw unauthorisedResponse.error;
                        }

                        return customConnection;
                    }
                } else if (responseCode == -1) {
                    // If the response code is -1 then the response was not a valid HTTP response, return the output of the users execution
                    return customConnection;
                } else {
                    return customConnection;
                }
                customConnection.disconnect();
            }
        } finally {
            if ( Utils.getLocalSessionState(applicationContext).status == Utils.LocalSessionStateStatus.NOT_EXISTS ) {
                AntiCSRF.removeToken(applicationContext);
                FrontToken.removeToken(applicationContext);
            }
        }
    }

    static Utils.Unauthorised onUnauthorisedResponse(Utils.LocalSessionState preRequestLocalSessionState, Context applicationContext) {
        // this is intentionally not put in a loop because the loop in other projects is because locking has a timeout
        HttpURLConnection refreshTokenConnection = null;
        try {
            refreshAPILock.writeLock().lock();
            Utils.LocalSessionState postLockLocalSessionState = Utils.getLocalSessionState(applicationContext);
            if ( postLockLocalSessionState.status == Utils.LocalSessionStateStatus.NOT_EXISTS ) {
                SuperTokens.config.eventHandler.handleEvent(EventHandler.EventType.UNAUTHORISED);
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
            }

            if ( postLockLocalSessionState.status != preRequestLocalSessionState.status ||
                    (postLockLocalSessionState.status == Utils.LocalSessionStateStatus.EXISTS &&
                            preRequestLocalSessionState.status == Utils.LocalSessionStateStatus.EXISTS &&
                            postLockLocalSessionState.lastAccessTokenUpdate != preRequestLocalSessionState.lastAccessTokenUpdate) ) {
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.RETRY);
            }

            URL refreshTokenUrl = new URL(SuperTokens.refreshTokenUrl);
            refreshTokenConnection = (HttpURLConnection) refreshTokenUrl.openConnection();
            refreshTokenConnection.setRequestMethod("POST");

            if (preRequestLocalSessionState.status == Utils.LocalSessionStateStatus.EXISTS) {
                String antiCSRFToken = AntiCSRF.getToken(applicationContext, preRequestLocalSessionState.lastAccessTokenUpdate);

                if (antiCSRFToken != null) {
                    refreshTokenConnection.setRequestProperty(Constants.CSRF_HEADER_KEY, antiCSRFToken);
                }
            }

            refreshTokenConnection.setRequestProperty("rid", SuperTokens.rid);
            refreshTokenConnection.setRequestProperty("fdi-version", Utils.join(Version.supported_fdi, ","));
            refreshTokenConnection.setRequestProperty("st-auth-mode", SuperTokens.config.tokenTransferMethod);
            setAuthorizationHeaderIfRequiredForRefresh(refreshTokenConnection, applicationContext);

            Map<String, String> customRefreshHeaders = SuperTokens.config.customHeaderMapper.getRequestHeaders(CustomHeaderProvider.RequestType.REFRESH);
            if (customRefreshHeaders != null) {
                for (Map.Entry<String, String> entry : customRefreshHeaders.entrySet()) {
                    refreshTokenConnection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            refreshTokenConnection.connect();

            Utils.saveTokenFromHeaders(new SuperTokensCustomHttpURLConnection(refreshTokenConnection, applicationContext), applicationContext);
            manuallySetCookiesFromResponse(refreshTokenUrl, refreshTokenConnection);

            final int responseCode = refreshTokenConnection.getResponseCode();

            boolean isUnauthorised = responseCode == SuperTokens.config.sessionExpiredStatusCode;

            if (isUnauthorised && refreshTokenConnection.getHeaderField(Constants.FRONT_TOKEN_HEADER_KEY) != null) {
                FrontToken.setItem(applicationContext, "remove");
            }

            String frontTokenInHeaders = refreshTokenConnection.getHeaderField(Constants.FRONT_TOKEN_HEADER_KEY);

            Utils.fireSessionUpdateEventsIfNecessary(
                    preRequestLocalSessionState.status == Utils.LocalSessionStateStatus.EXISTS,
                    responseCode,
                    frontTokenInHeaders == null ? "remove" : frontTokenInHeaders
            );

            if (responseCode >= 300) {
                throw new IOException(refreshTokenConnection.getResponseMessage());
            }

            if (Utils.getLocalSessionState(applicationContext).status == Utils.LocalSessionStateStatus.NOT_EXISTS) {
                // The execution should never come here.. but just in case.
                // removed by server. So we logout
                // we do not send "UNAUTHORISED" event here because
                // this is a result of the refresh API returning a session expiry, which
                // means that the frontend did not know for sure that the session existed
                // in the first place.
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
            }

            SuperTokens.config.eventHandler.handleEvent(EventHandler.EventType.REFRESH_SESSION);
            return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.RETRY);
        } catch (Exception e) {
            IOException ioe = new IOException(e);
            if (e instanceof IOException) {
                ioe = (IOException) e;
            }

            if ( Utils.getLocalSessionState(applicationContext).status == Utils.LocalSessionStateStatus.NOT_EXISTS ) {
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
            }

            return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.API_ERROR, ioe);

        } finally {
            refreshAPILock.writeLock().unlock();
            if ( refreshTokenConnection != null ) {
                refreshTokenConnection.disconnect();
            }

            if (Utils.getLocalSessionState(applicationContext).status == Utils.LocalSessionStateStatus.NOT_EXISTS) {
                AntiCSRF.removeToken(applicationContext);
                FrontToken.removeToken(applicationContext);
            }
        }
    }

    public interface PreConnectCallback {
        void doAction(HttpURLConnection con) throws IOException;
    }
}
