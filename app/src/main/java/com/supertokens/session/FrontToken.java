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

import org.json.JSONException;
import org.json.JSONObject;

public class FrontToken {
    static final Object tokenLock = new Object();

    static JSONObject getTokenInfo(Context context) {
        if (!SuperTokens.isInitCalled) return null;
        return SessionState.snapshot().getFrontToken();
    }

    public static JSONObject getToken(Context context) throws JSONException {
        return getTokenInfo(context);
    }

    /** @deprecated Use SuperTokens.clearSession with an expected owner. */
    @Deprecated
    public static void removeToken(Context context) {
        SharedPreferences.Editor editor = Utils.getSharedPreferences(context).edit();
        editor.remove(Constants.FRONT_TOKEN_PREFS_KEY);
        editor.remove(Constants.CSRF_TOKEN_PREFS_KEY);
        editor.remove("st-storage-item-" + Constants.ACCESS_TOKEN_PREFS_KEY);
        editor.remove("st-storage-item-" + Constants.REFRESH_TOKEN_PREFS_KEY);
        editor.apply();
        SessionState.legacyStorageChanged(context);
        SessionState.dispatch();
    }

    /** @deprecated Use SuperTokens.installSession for an atomic tuple. */
    @Deprecated
    public static void setToken(Context context, String frontToken) {
        Utils.getSharedPreferences(context).edit()
                .putString(Constants.FRONT_TOKEN_PREFS_KEY, frontToken).apply();
        SessionState.legacyStorageChanged(context);
        SessionState.dispatch();
    }

    /** @deprecated Use SuperTokens.installSession or clearSession. */
    @Deprecated
    public static void setItem(Context context, String frontToken) {
        SharedPreferences.Editor editor = Utils.getSharedPreferences(context).edit();
        editor.putString("st-storage-item-" + Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY,
                Long.toString(System.currentTimeMillis()));
        editor.remove("st-storage-item-sIRTFrontend");
        if (frontToken.equalsIgnoreCase("remove")) {
            editor.remove(Constants.FRONT_TOKEN_PREFS_KEY);
            editor.remove(Constants.CSRF_TOKEN_PREFS_KEY);
            editor.remove("st-storage-item-" + Constants.ACCESS_TOKEN_PREFS_KEY);
            editor.remove("st-storage-item-" + Constants.REFRESH_TOKEN_PREFS_KEY);
        } else {
            editor.putString(Constants.FRONT_TOKEN_PREFS_KEY, frontToken);
        }
        editor.apply();
        SessionState.legacyStorageChanged(context);
        SessionState.dispatch();
    }

    public static boolean doesTokenExist(Context context) {
        return getTokenInfo(context) != null;
    }
}
