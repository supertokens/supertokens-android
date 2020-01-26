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
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings({"Convert2Diamond"})
public class SuperTokens {
    static int sessionExpiryStatusCode = 440;
    @SuppressWarnings("unused")
    static String apiDomain;
    static boolean isInitCalled = false;
    @SuppressWarnings("unused")
    static final String TAG = "io.supertokens.session";
    static String refreshTokenEndpoint;
    static WeakReference<Context> contextWeakReference;
    static Map<String, String> refreshAPICustomHeaders = new HashMap<>();


    @SuppressWarnings("unused")
    public static void init(Context applicationContext, @NonNull String refreshTokenEndpoint, @Nullable Integer sessionExpiryStatusCode,
                            @Nullable Map<String, String> refreshAPICustomHeaders) throws MalformedURLException {
        if ( SuperTokens.isInitCalled ) {
            return;
        }
        contextWeakReference = new WeakReference<Context>(applicationContext);
        SuperTokens.refreshTokenEndpoint = refreshTokenEndpoint;
        if (refreshAPICustomHeaders != null) {
            SuperTokens.refreshAPICustomHeaders = refreshAPICustomHeaders;
        }
        if ( sessionExpiryStatusCode != null ) {
            SuperTokens.sessionExpiryStatusCode = sessionExpiryStatusCode;
        }

        SuperTokens.apiDomain = SuperTokens.getApiDomain(refreshTokenEndpoint);
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
            return TextUtils.join("/", apiDomainArray);
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
