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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.util.ArrayList;

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
            @Nullable CustomHeaderProvider customHeaderProvider
    ) throws MalformedURLException {
        if ( SuperTokens.isInitCalled ) {
            return;
        }

        SuperTokens.config = Utils.NormalisedInputType.normaliseInputOrThrowError(
                apiDomain,
                apiBasePath,
                sessionExpiredStatusCode,
                cookieDomain,
                customHeaderProvider
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
}
