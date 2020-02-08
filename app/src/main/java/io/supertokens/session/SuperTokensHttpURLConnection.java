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

package io.supertokens.session;

import android.content.Context;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@SuppressWarnings("unused")
public class SuperTokensHttpURLConnection {
    private static final Object refreshTokenLock = new Object();
    private static final ReentrantReadWriteLock refreshAPILock = new ReentrantReadWriteLock();

    public static HttpURLConnection newRequest(URL url, SuperTokensHttpURLConnectionCallback preConnectCallback) throws IllegalAccessException, IOException {
        if ( !SuperTokens.isInitCalled ) {
            throw new IllegalAccessException("SuperTokens.init function needs to be called before using newRequest");
        }

        Context applicationContext = SuperTokens.contextWeakReference.get();
        if ( applicationContext == null ) {
            throw new IllegalAccessException("Context is null");
        }
        try {
            while (true) {
                HttpURLConnection connection;
                String preRequestIdRefreshToken;
                int responseCode;
                // TODO: write comment as to why we have this lock here. Do we also have this lock for iOS and website package?
                refreshAPILock.readLock().lock();
                try {
                    connection = (HttpURLConnection) url.openConnection();

                    // Add antiCSRF token, if present in storage, to the request headers
                    preRequestIdRefreshToken = IdRefreshToken.getToken(applicationContext);
                    String antiCSRFToken = AntiCSRF.getToken(applicationContext, preRequestIdRefreshToken);

                    if (antiCSRFToken != null) {
                        connection.setRequestProperty(applicationContext.getString(R.string.supertokensAntiCSRFHeaderKey), antiCSRFToken);
                    }

                    // Add package information to headers
                    connection.setRequestProperty(applicationContext.getString(R.string.supertokensNameHeaderKey), Utils.PACKAGE_PLATFORM);
                    connection.setRequestProperty(applicationContext.getString(R.string.supertokensVersionHeaderKey), BuildConfig.VERSION_NAME);

                    // Get the default cookie manager that is used, if null set a new one
                    if (CookieManager.getDefault() == null) {
                        // Passing null for cookie policy to use default
                        throw new IllegalAccessException("Please initialise a CookieManager.\n" +
                                "For example: new CookieManager(new SuperTokensPersistentCookieStore(context), null).\n" +
                                "SuperTokens provides a persistent cookie store called SuperTokensPersistentCookieStore.\n" +
                                "For more information visit our documentation.");
                    }
                    // This will allow the user to set headers or modify request in anyway they want
                    if (preConnectCallback != null) {
                        preConnectCallback.doAction(connection);
                    }

                    connection.connect();

                    // Get the cookies from the response and store the idRefreshToken to storage
                    String idRefreshToken = connection.getHeaderField(applicationContext.getString(R.string.supertokensIdRefreshHeaderKey));
                    if (idRefreshToken != null) {
                        IdRefreshToken.setToken(applicationContext, idRefreshToken);
                    }

                    responseCode = connection.getResponseCode();
                } finally {
                    refreshAPILock.readLock().unlock();
                }

                if (responseCode == SuperTokens.sessionExpiryStatusCode) {
                    // Network call threw UnauthorisedAccess, try to call the refresh token endpoint and retry original call
                    boolean retry = SuperTokensHttpURLConnection.handleUnauthorised(applicationContext, preRequestIdRefreshToken);
                    if (!retry) {
                        return connection;
                    }
                } else if (responseCode == -1) {
                    // If the response code is -1 then the response was not a valid HTTP response, return the output of the users execution
                    return connection;
                } else {
                    // Store the anti-CSRF token from the response headers
                    String responseAntiCSRFToken = connection.getHeaderField(applicationContext.getString(R.string.supertokensAntiCSRFHeaderKey));
                    if ( responseAntiCSRFToken != null ) {
                        AntiCSRF.setToken(applicationContext, IdRefreshToken.getToken(applicationContext), responseAntiCSRFToken);
                    }
                    return connection;
                }
            }
        } finally {
            if ( IdRefreshToken.getToken(applicationContext) == null ) {
                AntiCSRF.removeToken(applicationContext);
            }
        }
    }

    private static boolean handleUnauthorised(Context applicationContext, String preRequestIdRefreshToken) throws IOException {
        if ( preRequestIdRefreshToken == null ) {
            String idRefreshToken = IdRefreshToken.getToken(applicationContext);
            return idRefreshToken != null;
        }

        Utils.Unauthorised unauthorisedResponse = onUnauthorisedResponse(SuperTokens.refreshTokenEndpoint,preRequestIdRefreshToken, applicationContext);

        if ( unauthorisedResponse.status == Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED ) {
            return false;
        } else if (unauthorisedResponse.status == Utils.Unauthorised.UnauthorisedStatus.API_ERROR) {
            throw unauthorisedResponse.error;
        }

        return true;
    }

    private static Utils.Unauthorised onUnauthorisedResponse(String refreshTokenEndpoint, String preRequestIdRefreshToken, Context applicationContext) {
        // this is intentionally not put in a loop because the loop in other projects is because locking has a timeout
        HttpURLConnection refreshTokenConnection = null;
        try {
            refreshAPILock.writeLock().lock();
            String postLockIdRefreshToken = IdRefreshToken.getToken(applicationContext);
            if ( postLockIdRefreshToken == null ) {
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
            }

            if ( !postLockIdRefreshToken.equals(preRequestIdRefreshToken) ) {
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.RETRY);
            }

            URL refreshTokenUrl = new URL(refreshTokenEndpoint);
            refreshTokenConnection = (HttpURLConnection) refreshTokenUrl.openConnection();
            refreshTokenConnection.setRequestMethod("POST");

            // Add package information to headers
            refreshTokenConnection.setRequestProperty(applicationContext.getString(R.string.supertokensNameHeaderKey), Utils.PACKAGE_PLATFORM);
            refreshTokenConnection.setRequestProperty(applicationContext.getString(R.string.supertokensVersionHeaderKey), BuildConfig.VERSION_NAME);
            for (Map.Entry<String,String> entry: SuperTokens.refreshAPICustomHeaders.entrySet()) {
                refreshTokenConnection.setRequestProperty(entry.getKey(), entry.getValue());
            }

            if (CookieManager.getDefault() == null) {
                throw new IllegalAccessException("Please initialise a CookieManager.\n" +
                        "For example: new CookieManager(new SuperTokensPersistentCookieStore(context), null).\n" +
                        "SuperTokens provides a persistent cookie store called SuperTokensPersistentCookieStore.\n" +
                        "For more information visit our documentation.");
            }
            refreshTokenConnection.connect();

            boolean removeIdRefreshToken = true;
            String idRefreshToken = refreshTokenConnection.getHeaderField(applicationContext.getString(R.string.supertokensIdRefreshHeaderKey));
            if (idRefreshToken != null) {
                IdRefreshToken.setToken(applicationContext, idRefreshToken);
                removeIdRefreshToken = false;
            }

            if (refreshTokenConnection.getResponseCode() == SuperTokens.sessionExpiryStatusCode && removeIdRefreshToken) {
                IdRefreshToken.setToken(applicationContext, "remove");
            }

            if (refreshTokenConnection.getResponseCode() != 200) {
                throw new IOException(refreshTokenConnection.getResponseMessage());
            }

            String idRefreshAfterResponse = IdRefreshToken.getToken(applicationContext);
            if (idRefreshAfterResponse == null) {
                // removed by server
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
            }

            String responseAntiCSRFToken = refreshTokenConnection.getHeaderField(applicationContext.getString(R.string.supertokensAntiCSRFHeaderKey));
            if (responseAntiCSRFToken != null) {
                AntiCSRF.setToken(applicationContext, IdRefreshToken.getToken(applicationContext), responseAntiCSRFToken);
            }

            return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.RETRY);
        } catch (Exception e) {
            IOException ioe = new IOException(e);
            if (e instanceof IOException) {
                ioe = (IOException) e;
            }
            String idRefreshToken = IdRefreshToken.getToken(applicationContext);
            if ( idRefreshToken == null ) {
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
            }

            return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.API_ERROR, ioe);

        } finally {
            refreshAPILock.writeLock().unlock();
            if ( refreshTokenConnection != null ) {
                refreshTokenConnection.disconnect();
            }
        }
    }

//    /**
//     *
//     * @return
//     * @throws {@link IllegalAccessException} if SuperTokens.init is not called or application context is null
//     * @throws {@link IOException} if request fails
//     */
//    public static boolean attemptRefreshingSession() throws IllegalAccessException, IOException {
//        if ( !SuperTokens.isInitCalled ) {
//            throw new IllegalAccessException("SuperTokens.init function needs to be called before using attemptRefreshingSession");
//        }
//
//        Context applicationContext = SuperTokens.contextWeakReference.get();
//        if ( applicationContext == null ) {
//            throw new IllegalAccessException("Context is null");
//        }
//
//        try {
//            String preRequestIdRefreshToken = IdRefreshToken.getToken(applicationContext);
//            return handleUnauthorised(applicationContext, preRequestIdRefreshToken);
//        } finally {
//            String idRefreshToken = IdRefreshToken.getToken(applicationContext);
//            if ( idRefreshToken == null ) {
//                AntiCSRF.removeToken(applicationContext);
//            }
//        }
//    }

    public interface SuperTokensHttpURLConnectionCallback {
        void doAction(HttpURLConnection con) throws IOException;
    }

}
