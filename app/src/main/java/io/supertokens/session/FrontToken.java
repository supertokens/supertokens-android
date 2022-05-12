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

package io.supertokens.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.HttpCookie;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

public class FrontToken {
    static final Object tokenLock = new Object();
    private static String frontTokenInMemory;
    private static final String FRONT_TOKEN_NAME = "sFrontToken";

    private static String getFrontTokenFromStorage(Context context) {
        if (frontTokenInMemory == null) {
            frontTokenInMemory = getSharedPreferences(context).getString(context.getString(R.string.supertokensFrontTokenSharedPrefsKey), null);
        }

        // If it is still null then there was no value in storage
        if (frontTokenInMemory == null) {
            return null;
        }

        HttpCookie cookie = HttpCookie.parse(frontTokenInMemory).get(0);

        if (cookie.hasExpired()) {
            return null;
        }

        return cookie.getValue();
    }

    private static String getFrontToken(Context context) {
        if (IdRefreshToken.getToken(context) == null) {
            return null;
        }

        return getFrontTokenFromStorage(context);
    }

    private static JSONObject parseFrontToken(String frontTokenDecoded) throws JSONException {
        JSONObject jsonObject = new JSONObject(new String(Base64.decode(frontTokenDecoded, Base64.DEFAULT), Charset.forName("UTF-8")));
        return jsonObject;
    }

    private static JSONObject getTokenInfo(Context context) throws JSONException {
        synchronized (tokenLock) {
            while (true) {
                String frontToken = getFrontToken(context);
                if (frontToken == null) {
                    String idRefresh = IdRefreshToken.getToken(context);
                    if (idRefresh != null) {
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
        String expires = "Thu, 01 Jan 1970 00:00:01 GMT";
        String cookieVal = "";

        if (frontToken != null) {
            cookieVal = frontToken;
            expires = null;
        }

        if (expires != null) {
            String toStore = FRONT_TOKEN_NAME + "=" + cookieVal + ";expires=" + expires + ";path=/;samesite=lax";
            SharedPreferences.Editor editor = getSharedPreferences(context).edit();
            editor.putString(context.getString(R.string.supertokensFrontTokenSharedPrefsKey), toStore);
            editor.apply();
            frontTokenInMemory = toStore;
        } else {
            String toStore = FRONT_TOKEN_NAME + "=" + cookieVal + ";expires=Fri, 31 Dec 9999 23:59:59 GMT;path=/;samesite=lax";
            SharedPreferences.Editor editor = getSharedPreferences(context).edit();
            editor.putString(context.getString(R.string.supertokensFrontTokenSharedPrefsKey), toStore);
            editor.apply();
            frontTokenInMemory = toStore;
        }
    }

    private static void setFrontToken(Context context, String frontToken) throws JSONException {
        String oldToken = getFrontTokenFromStorage(context);

        if (oldToken != null && frontToken != null) {
            JSONObject oldPayload = parseFrontToken(oldToken).getJSONObject("up");
            JSONObject newPayload = parseFrontToken(frontToken).getJSONObject("up");

            if (!oldPayload.toString().equals(newPayload.toString())) {
                // TODO NEMI: CALL ON HANDLE EVENT
            }
        }

        setFrontTokenToStorage(context, frontToken);
    }

    private static void removeTokenFromStorage(Context context) {
        SharedPreferences.Editor editor = getSharedPreferences(context).edit();
        editor.remove(context.getString(R.string.supertokensFrontTokenSharedPrefsKey));
        editor.apply();
        frontTokenInMemory = null;
    }

    public static void removeToken(Context context) {
        synchronized (tokenLock) {
            removeTokenFromStorage(context);
            tokenLock.notifyAll();
        }
    }

    public static void setToken(Context context, String frontToken) throws JSONException {
        synchronized (tokenLock) {
            setFrontToken(context, frontToken);
            tokenLock.notifyAll();
        }
    }

    private static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(context.getString(R.string.supertokensSharedPrefsKey), Context.MODE_PRIVATE);
    }
}
