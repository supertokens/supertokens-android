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
import java.net.URI;

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
        String cookieDomain;

        // TODO NEMI: Handle pre API and on handle event
        public NormalisedInputType(
                String apiDomain,
                String apiBasePath,
                int sessionExpiredStatusCode,
                String cookieDomain
        ) {
            this.apiDomain = apiDomain;
            this.apiBasePath = apiBasePath;
            this.sessionExpiredStatusCode = sessionExpiredStatusCode;
            this.cookieDomain = cookieDomain;
        }

        public static String normaliseURLDomainOrThrowError(String input) throws MalformedURLException {
            return new NormalisedURLDomain(input).getAsStringDangerous();
        }

        public static String normaliseURLPathOrThrowError(String input) throws MalformedURLException {
            return new NormalisedURLPath(input).getAsStringDangerous();
        }

        static String sessionScopeHelper(String sessionScope) throws MalformedURLException {
            String trimmedSessionScope = sessionScope.trim().toLowerCase();

            // first we convert it to a URL so that we can use the URL class
            if (trimmedSessionScope.startsWith(".")) {
                trimmedSessionScope = trimmedSessionScope.substring(1);
            }

            if (!trimmedSessionScope.startsWith("http://") && !trimmedSessionScope.startsWith("https://")) {
                trimmedSessionScope = "http://" + trimmedSessionScope;
            }

            try {
                URI urlObj = new URI(trimmedSessionScope);
                trimmedSessionScope = urlObj.getHost();

                // remove leading dot
                if (trimmedSessionScope.startsWith(".")) {
                    trimmedSessionScope = trimmedSessionScope.substring(1);
                }

                return trimmedSessionScope;
            } catch (Exception e) {
                throw new MalformedURLException("Please provide a valid sessionScope");
            }
        }

        public static String normaliseSessionScopeOrThrowError(String sessionScope) throws MalformedURLException {
            String noDotNormalised = sessionScopeHelper(sessionScope);

            if (noDotNormalised.equals("localhost") || NormalisedURLDomain.isAnIpAddress(noDotNormalised)) {
                return noDotNormalised;
            }

            if (sessionScope.startsWith(".")) {
                return "." + noDotNormalised;
            }

            return noDotNormalised;
        }

        public static NormalisedInputType normaliseInputOrThrowError(
                String apiDomain,
                @Nullable  String apiBasePath,
                @Nullable  Integer sessionExpiredStatusCode,
                @Nullable  String cookieDomain
        ) throws MalformedURLException {
            String _apiDomain = normaliseURLDomainOrThrowError(apiDomain);
            String _apiBasePath = normaliseURLPathOrThrowError("/auth");

            if (apiBasePath != null) {
                _apiBasePath = normaliseURLPathOrThrowError(apiBasePath);
            }

            int _sessionExpiredStatusCode = 401;
            if (sessionExpiredStatusCode != null) {
                _sessionExpiredStatusCode = sessionExpiredStatusCode;
            }

            String _cookieDomain = null;
            if (cookieDomain != null) {
                _cookieDomain = normaliseSessionScopeOrThrowError(cookieDomain);
            }

            return new NormalisedInputType(_apiDomain, _apiBasePath, _sessionExpiredStatusCode, _cookieDomain);
        }
    }
}
