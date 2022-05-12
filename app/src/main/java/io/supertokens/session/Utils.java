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

import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.MalformedURLException;

public class Utils {
    static final String PACKAGE_PLATFORM = "android";
    static class Unauthorised {
        UnauthorisedStatus status;
        IOException error;

        enum UnauthorisedStatus {
            SESSION_EXPIRED,
            API_ERROR,
            RETRY,
        }

        Unauthorised(UnauthorisedStatus status) {
            this.status = status;
        }

        Unauthorised(UnauthorisedStatus status, IOException error) {
            this.status = status;
            this.error = error;
        }
    }

    public static class NormalisedInputType {
        String apiDomain;
        String apiBasePath;
        int sessionExpiredStatusCode;

        // TODO NEMI: Handle pre API and on handle event
        public NormalisedInputType(
                String apiDomain,
                String apiBasePath,
                int sessionExpiredStatusCode
        ) {
            this.apiDomain = apiDomain;
            this.apiBasePath = apiBasePath;
            this.sessionExpiredStatusCode = sessionExpiredStatusCode;
        }

        public static NormalisedInputType normaliseInputOrThrowError(
                String apiDomain,
                @Nullable  String apiBasePath,
                @Nullable  Integer sessionExpiredStatusCode,
                @Nullable  String cookieDomain
        ) throws MalformedURLException {
            String _apiDomain = new NormalisedURLDomain(apiDomain).getAsStringDangerous();
            String _apiBasePath = new NormalisedURLPath("/auth").getAsStringDangerous();

            if (apiBasePath != null) {
                _apiBasePath = new NormalisedURLPath(apiBasePath).getAsStringDangerous();
            }

            int _sessionExpiredStatusCode = 401;
            if (sessionExpiredStatusCode != null) {
                _sessionExpiredStatusCode = sessionExpiredStatusCode;
            }

            return new NormalisedInputType(_apiDomain, _apiBasePath, _sessionExpiredStatusCode);
        }
    }
}
