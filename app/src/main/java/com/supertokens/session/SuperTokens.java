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
    static boolean isInitCalled = false;
    static String rid;
    static Utils.NormalisedInputType config;
    static WeakReference<Context> contextWeakReference;


    @SuppressWarnings("unused")
    public static void init(
            Context applicationContext,
            @NonNull String apiDomain,
            @Nullable String apiBasePath,
            @Nullable Integer sessionExpiredStatusCode,
            @Nullable String cookieDomain,
            @Nullable CustomHeaderProvider customHeaderProvider,
            @Nullable EventHandler eventHandler
    ) throws MalformedURLException {
        if ( SuperTokens.isInitCalled ) {
            return;
        }

        SuperTokens.config = Utils.NormalisedInputType.normaliseInputOrThrowError(
                apiDomain,
                apiBasePath,
                sessionExpiredStatusCode,
                cookieDomain,
                customHeaderProvider,
                eventHandler
        );
        contextWeakReference = new WeakReference<Context>(applicationContext);
        SuperTokens.refreshTokenUrl = SuperTokens.config.apiDomain + SuperTokens.config.apiBasePath + "/session/refresh";
        SuperTokens.signOutUrl = SuperTokens.config.apiDomain + SuperTokens.config.apiBasePath + "/signout";
        SuperTokens.rid = "session";
        SuperTokens.isInitCalled = true;
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
        String idRefreshToken = IdRefreshToken.getToken(context);
        return idRefreshToken != null;
    }

    @SuppressWarnings("unused")
    public static void signOut(Context context) throws IOException, IllegalAccessException, SuperTokensGeneralError {
        if (!doesSessionExist(context)) {
            SuperTokens.config.eventHandler.handleEvent(EventHandler.EventType.SIGN_OUT);
            return;
        }

        URL signOutUrl = new URL(SuperTokens.signOutUrl);
        HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(signOutUrl, new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
                con.setRequestProperty("rid", SuperTokens.rid);
                Map<String, String> customHeaders = SuperTokens.config.customHeaderMapper.getRequestHeaders(CustomHeaderProvider.RequestType.SIGN_OUT);
                if (customHeaders != null) {
                    for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
                        con.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }
            }
        });

        int responseCode = connection.getResponseCode();

        if (responseCode == SuperTokens.config.sessionExpiredStatusCode) {
            // refresh must have already sent session expiry event
            return;
        }

        if (responseCode >= 300) {
            throw new IOException("Sign out failed with response code " + responseCode);
        }

        try {
            StringBuilder stringBuilder = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String line;

            while((line = reader.readLine()) != null) {
                stringBuilder.append(line + "\n");
            }

            String jsonString = stringBuilder.toString();

            JSONObject jsonBody = new JSONObject(new JSONTokener(jsonString));

            String statusFromResponse = jsonBody.getString("status");

            if (statusFromResponse.equals("GENERAL_ERROR")) {
                String messageFromResponse = jsonBody.getString("message");

                throw new SuperTokensGeneralError(messageFromResponse);
            }
        } catch (JSONException e) {
            /*
             * Error when converting the body to json or reading from it
             */
            throw new IllegalStateException(e);
        }

        return;
    }

    public static boolean attemptRefreshingSession(Context context) throws IOException {
        String preRequestIdRefreshToken = IdRefreshToken.getToken(context);
        Utils.Unauthorised unauthorisedResponse = SuperTokensHttpURLConnection.onUnauthorisedResponse(preRequestIdRefreshToken, context);

        if (unauthorisedResponse.status == Utils.Unauthorised.UnauthorisedStatus.API_ERROR) {
            throw unauthorisedResponse.error;
        }

        return unauthorisedResponse.status == Utils.Unauthorised.UnauthorisedStatus.RETRY;
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
        JSONObject tokenInfo = FrontToken.getTokenInfo(context);
        if (tokenInfo == null) {
            throw new IllegalStateException("No session exists");
        }

        try {
            long accessTokenExpiry = tokenInfo.getLong("ate");

            if (accessTokenExpiry < System.currentTimeMillis()) {
                boolean retry = attemptRefreshingSession(context);
                if (retry) {
                    return getAccessTokenPayloadSecurely(context);
                } else {
                    throw new IOException("Could not refresh session");
                }
            }

            return tokenInfo.getJSONObject("up");
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
