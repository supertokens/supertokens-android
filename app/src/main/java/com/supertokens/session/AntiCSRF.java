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

//TODO verify about locking

class AntiCSRF {
    private static AntiCSRFTokenInfo antiCSRFTokenInfo;

    static String getToken(Context context, @Nullable String associatedAccessTokenUpdate) {
        if ( associatedAccessTokenUpdate == null ) {
            antiCSRFTokenInfo = null;
            return null;
        }

        if ( antiCSRFTokenInfo == null ) {
            SharedPreferences sharedPreferences = getSharedPreferences(context);
            String antiCSRF = sharedPreferences.getString(getSharedPrefsAntiCSRFKey(context), null);
            if ( antiCSRF == null ) {
                return null;
            }

            antiCSRFTokenInfo = new AntiCSRFTokenInfo(antiCSRF, associatedAccessTokenUpdate);
        } else if ( antiCSRFTokenInfo.associatedAccessTokenUpdate != null && !antiCSRFTokenInfo.associatedAccessTokenUpdate.equals(associatedAccessTokenUpdate) ) {
            antiCSRFTokenInfo = null;
            return getToken(context, associatedAccessTokenUpdate);
        }

        return antiCSRFTokenInfo.antiCSRF;
    }

    static void removeToken(Context context) {
        SharedPreferences sharedPreferences = getSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove(getSharedPrefsAntiCSRFKey(context));
        editor.apply();

        antiCSRFTokenInfo = null;
    }

    static void setToken(Context context, @Nullable String associatedAccessTokenUpdate, String antiCSRFToken) {
        if ( associatedAccessTokenUpdate == null ) {
            antiCSRFTokenInfo = null;
            return;
        }

        SharedPreferences sharedPreferences = getSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(getSharedPrefsAntiCSRFKey(context), antiCSRFToken);
        editor.apply();

        antiCSRFTokenInfo = new AntiCSRFTokenInfo(antiCSRFToken, associatedAccessTokenUpdate);
    }

    private static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE);
    }

    private static String getSharedPrefsAntiCSRFKey(Context context) {
        return Constants.CSRF_TOKEN_PREFS_KEY;
    }

    static class AntiCSRFTokenInfo {
        String antiCSRF;
        String associatedAccessTokenUpdate;

        AntiCSRFTokenInfo(String antiCSRF, String associatedAccessTokenUpdate) {
            this.antiCSRF = antiCSRF;
            this.associatedAccessTokenUpdate = associatedAccessTokenUpdate;
        }
    }
}
