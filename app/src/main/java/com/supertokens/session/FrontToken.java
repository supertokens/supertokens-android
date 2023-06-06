/*
 * Copyright (c) 2022, VRAI Labs and/or its affiliates. All rights reserved.
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
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.Charset;

public class FrontToken {
    static final Object tokenLock = new Object();
    private static String frontTokenInMemory;
    private static final String FRONT_TOKEN_NAME = "sFrontToken";

    private static String getFrontTokenFromStorage(Context context) {
        if (frontTokenInMemory == null) {
            frontTokenInMemory = Utils.getSharedPreferences(context).getString(Constants.FRONT_TOKEN_PREFS_KEY, null);
        }

        // If it is still null then there was no value in storage
        if (frontTokenInMemory == null) {
            return null;
        }

        return frontTokenInMemory;
    }

    private static String getFrontToken(Context context) {
        if (Utils.getLocalSessionState(context).status == Utils.LocalSessionStateStatus.NOT_EXISTS) {
            return null;
        }

        return getFrontTokenFromStorage(context);
    }

    private static JSONObject parseFrontToken(String frontTokenDecoded) {
        try {
            JSONObject jsonObject = new JSONObject(new String(Base64.decode(frontTokenDecoded, Base64.DEFAULT), Charset.forName("UTF-8")));
            return jsonObject;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static JSONObject getTokenInfo(Context context) {
        synchronized (tokenLock) {
            while (true) {
                String frontToken = getFrontToken(context);
                if (frontToken == null) {
                    Utils.LocalSessionState localSessionState = Utils.getLocalSessionState(context);
                    if (localSessionState.status == Utils.LocalSessionStateStatus.EXISTS) {
                        try {
                            tokenLock.wait();
                        } catch (InterruptedException ignored) {}
                    } else {
                        return null;
                    }
                } else {
                    return parseFrontToken(frontToken);
                }
            }
        }
    }

    public static JSONObject getToken(Context context) throws JSONException {
        return getTokenInfo(context);
    }

    private static void setFrontTokenToStorage(Context context, String frontToken) {
        SharedPreferences.Editor editor = Utils.getSharedPreferences(context).edit();
        editor.putString(Constants.FRONT_TOKEN_PREFS_KEY, frontToken);
        editor.apply();
        frontTokenInMemory = frontToken;
    }

    private static void setFrontToken(Context context, String frontToken) {
        String oldToken = getFrontTokenFromStorage(context);

        if (oldToken != null && frontToken != null) {
            try {
                JSONObject oldPayload = parseFrontToken(oldToken).getJSONObject("up");
                JSONObject newPayload = parseFrontToken(frontToken).getJSONObject("up");

                if (!oldPayload.toString().equals(newPayload.toString())) {
                    SuperTokens.config.eventHandler.handleEvent(EventHandler.EventType.ACCESS_TOKEN_PAYLOAD_UPDATED);
                }
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }

        setFrontTokenToStorage(context, frontToken);
    }

    private static void removeTokenFromStorage(Context context) {
        SharedPreferences.Editor editor = Utils.getSharedPreferences(context).edit();
        editor.remove(Constants.FRONT_TOKEN_PREFS_KEY);
        editor.apply();
        frontTokenInMemory = null;
    }

    public static void removeToken(Context context) {
        synchronized (tokenLock) {
            removeTokenFromStorage(context);
            // We are clearing all stored tokens here, because:
            // 1. removing FrontToken signals that the session is being cleared
            // 2. you can only have a single active session - this means that all tokens can be cleared from all auth-modes if one is being cleared
            // 3. some proxies remove the empty headers used to clear the other tokens (i.e.: https://github.com/supertokens/supertokens-website/issues/218)
            Utils.setToken(Utils.TokenType.ACCESS, "", context);
            Utils.setToken(Utils.TokenType.REFRESH, "", context);
            tokenLock.notifyAll();
        }
    }

    public static void setToken(Context context, String frontToken) {
        synchronized (tokenLock) {
            setFrontToken(context, frontToken);
            tokenLock.notifyAll();
        }
    }

    public static void setItem(Context context, String frontToken) {
        // We update the refresh attempt info here as well, since this means that we've updated the session in some way
        // This could be both by a refresh call or if the access token was updated in a custom endpoint
        // By saving every time the access token has been updated, we cause an early retry if
        // another request has failed with a 401 with the previous access token and the token still exists.
        // Check the start and end of onUnauthorisedResponse
        // As a side-effect we reload the anti-csrf token to check if it was changed by another tab.
        Utils.saveLastAccessTokenUpdate(context);
        if (frontToken.equalsIgnoreCase("remove")) {
            FrontToken.removeToken(context);
            return;
        }

        FrontToken.setFrontToken(context, frontToken);
    }

    public static boolean doesTokenExist(Context context) {
        String frontToken = FrontToken.getFrontTokenFromStorage(context);
        return frontToken != null;
    }
}
