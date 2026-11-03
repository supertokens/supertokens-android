/*
 * Copyright (c) 2020, VRAI Labs and/or its affiliates. All rights reserved.
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
 */
package com.supertokens.session;

import android.content.Context;
import androidx.annotation.Nullable;

class AntiCSRF {
    static String getToken(Context context, @Nullable String associatedAccessTokenUpdate) {
        return associatedAccessTokenUpdate == null ? null : SessionStore.read(context).antiCsrfToken;
    }

    static void removeToken(Context context) {
        SessionStore.legacy(context, Constants.CSRF_TOKEN_PREFS_KEY, null);
    }

    static void setToken(Context context, @Nullable String associatedAccessTokenUpdate, String token) {
        if (associatedAccessTokenUpdate != null) SessionStore.legacy(context, Constants.CSRF_TOKEN_PREFS_KEY, token);
    }
}
