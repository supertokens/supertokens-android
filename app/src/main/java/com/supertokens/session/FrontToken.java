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

    static JSONObject getTokenInfo(Context context) {
        if (!SuperTokens.isInitCalled) {
            if (Utils.getLocalSessionState(context).status == Utils.LocalSessionStateStatus.NOT_EXISTS) return null;
            String raw = getStoredToken(context);
            if (raw == null) return null;
            try {
                return new JSONObject(new String(Base64.decode(raw, Base64.DEFAULT), Charset.forName("UTF-8")));
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
        return SessionState.snapshot().getFrontToken();
    }

    private static String getStoredToken(Context context) {
        return SessionState.storedFrontToken(context);
    }

    public static JSONObject getToken(Context context) throws JSONException {
        return getTokenInfo(context);
    }

    /** @deprecated Use SuperTokens.clearSession with an expected owner. */
    @Deprecated
    public static void removeToken(Context context) {
        SessionState.dispatch(SessionState.editLegacy(context,
                new SessionStorage.LegacyEdit(new String[]{Constants.FRONT_TOKEN_PREFS_KEY,
                        Constants.CSRF_TOKEN_PREFS_KEY, "st-storage-item-" + Constants.ACCESS_TOKEN_PREFS_KEY,
                        "st-storage-item-" + Constants.REFRESH_TOKEN_PREFS_KEY},
                        new String[]{null, null, null, null}), false));
    }

    /** @deprecated Use SuperTokens.installSession for an atomic tuple. */
    @Deprecated
    public static void setToken(Context context, String frontToken) {
        SessionState.dispatch(SessionState.editLegacy(context,
                new SessionStorage.LegacyEdit(new String[]{Constants.FRONT_TOKEN_PREFS_KEY},
                        new String[]{frontToken}), false));
    }

    /** @deprecated Use SuperTokens.installSession or clearSession. */
    @Deprecated
    public static void setItem(Context context, String frontToken) {
        boolean remove = frontToken.equalsIgnoreCase("remove");
        String[] keys = remove ? new String[]{"st-storage-item-" + Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY,
                "st-storage-item-sIRTFrontend", Constants.FRONT_TOKEN_PREFS_KEY, Constants.CSRF_TOKEN_PREFS_KEY,
                "st-storage-item-" + Constants.ACCESS_TOKEN_PREFS_KEY, "st-storage-item-" + Constants.REFRESH_TOKEN_PREFS_KEY}
                : new String[]{"st-storage-item-" + Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY,
                "st-storage-item-sIRTFrontend", Constants.FRONT_TOKEN_PREFS_KEY};
        String now = Long.toString(System.currentTimeMillis());
        String[] values = remove ? new String[]{now, null, null, null, null, null}
                : new String[]{now, null, frontToken};
        SessionState.dispatch(SessionState.editLegacy(context, new SessionStorage.LegacyEdit(keys, values), true));
    }

    public static boolean doesTokenExist(Context context) {
        if (!SuperTokens.isInitCalled) return getStoredToken(context) != null;
        return getTokenInfo(context) != null;
    }
}
