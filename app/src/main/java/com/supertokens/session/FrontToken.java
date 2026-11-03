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
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.Charset;

public class FrontToken {
    static final Object tokenLock = new Object();

    private static String getFrontTokenFromStorage(Context context) {
        return SessionStore.read(context).frontToken;
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
                SuperTokens.SessionSnapshot snapshot = SessionStore.read(context);
                String frontToken = snapshot.frontToken;
                if (!snapshot.doesSessionExist()) return null;
                if (frontToken == null) {
                    if (snapshot.doesSessionExist()) {
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

    static boolean payloadChanged(String oldToken, String newToken) {
        try {
            return !parseFrontToken(oldToken).getJSONObject("up").toString()
                    .equals(parseFrontToken(newToken).getJSONObject("up").toString());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    @Deprecated
    public static void removeToken(Context context) {
        SessionStore.legacy(context, Constants.FRONT_TOKEN_PREFS_KEY, null);
    }

    @Deprecated
    public static void setToken(Context context, String frontToken) {
        SessionStore.legacy(context, Constants.FRONT_TOKEN_PREFS_KEY, frontToken, false, false);
    }

    @Deprecated
    public static void setItem(Context context, String frontToken) {
        SessionStore.legacy(context, Constants.FRONT_TOKEN_PREFS_KEY, frontToken);
    }

    public static boolean doesTokenExist(Context context) {
        String frontToken = FrontToken.getFrontTokenFromStorage(context);
        return frontToken != null;
    }
}
