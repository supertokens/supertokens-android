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

import androidx.annotation.Nullable;

class AntiCSRF {
    static String getToken(Context context, @Nullable String associatedAccessTokenUpdate) {
        if (associatedAccessTokenUpdate == null || !SuperTokens.isInitCalled) return null;
        return SessionState.antiCSRF(associatedAccessTokenUpdate);
    }

    @Deprecated
    static void removeToken(Context context) {
        SessionState.dispatch(SessionState.editLegacy(context,
                new SessionStorage.LegacyEdit(new String[]{Constants.CSRF_TOKEN_PREFS_KEY}, new String[]{null}), false));
    }

    @Deprecated
    static void setToken(Context context, @Nullable String associatedAccessTokenUpdate, String antiCSRFToken) {
        if (associatedAccessTokenUpdate == null) return;
        SessionState.dispatch(SessionState.editLegacy(context,
                new SessionStorage.LegacyEdit(new String[]{Constants.CSRF_TOKEN_PREFS_KEY}, new String[]{antiCSRFToken}), false));
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
