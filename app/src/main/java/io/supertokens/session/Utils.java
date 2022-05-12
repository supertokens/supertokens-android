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

import org.jetbrains.annotations.TestOnly;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.AbstractCollection;
import java.util.Iterator;

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
        CustomHeaderProvider customHeaderMapper;

        // TODO NEMI: Handle pre API and on handle event
        public NormalisedInputType(
                String apiDomain,
                String apiBasePath,
                int sessionExpiredStatusCode,
                String cookieDomain,
                CustomHeaderProvider customHeaderMapper
        ) {
            this.apiDomain = apiDomain;
            this.apiBasePath = apiBasePath;
            this.sessionExpiredStatusCode = sessionExpiredStatusCode;
            this.cookieDomain = cookieDomain;
            this.customHeaderMapper = customHeaderMapper;
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

        @TestOnly
        public static String normaliseSessionScopeOrThrowErrorForTests(String sessionScope) throws MalformedURLException {
            return normaliseSessionScopeOrThrowError(sessionScope);
        }

        private static String normaliseSessionScopeOrThrowError(String sessionScope) throws MalformedURLException {
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
                @Nullable  String cookieDomain,
                @Nullable CustomHeaderProvider customHeaderProvider
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

            String _cookieDomain = null;
            if (cookieDomain != null) {
                _cookieDomain = normaliseSessionScopeOrThrowError(cookieDomain);
            }

            CustomHeaderProvider _customHeaderProvider = new CustomHeaderProvider.DefaultCustomHeaderMapper();
            if (customHeaderProvider != null) {
                _customHeaderProvider = customHeaderProvider;
            }

            return new NormalisedInputType(_apiDomain, _apiBasePath, _sessionExpiredStatusCode, _cookieDomain, _customHeaderProvider);
        }
    }

    public static String join(AbstractCollection<String> s, String delimiter) {
        if (s == null || s.isEmpty()) return "";
        Iterator<String> iter = s.iterator();
        StringBuilder builder = new StringBuilder(iter.next());
        while( iter.hasNext() )
        {
            builder.append(delimiter).append(iter.next());
        }
        return builder.toString();
    }

    public static String getHostWithProtocolFromURL(URL url) {
        String host = url.getHost();

        String portSuffix = "";

        if (url.getPort() != -1) {
            portSuffix = ":" + url.getPort();
        }

        return host + portSuffix;
    }

    private static boolean isNumeric(String string) {
        try {
            Integer.parseInt(string);
            return true;
        } catch (Exception ignored) {}

        try {
            Float.parseFloat(string);
            return true;
        } catch (Exception ignored) {}

        return false;
    }

    public static boolean shouldDoInterceptionBasedOnUrl(String toCheckUrl, String apiDomain, @Nullable String cookieDomain) throws MalformedURLException {
        String _toCheckUrl = new NormalisedURLDomain(toCheckUrl).getAsStringDangerous();
        URL url = new URL(_toCheckUrl);
        String domain = url.getHost();

        if (cookieDomain == null) {
            domain = url.getPort() == -1 ? domain : domain + ":" + url.getPort();
            String _apiDomain = new NormalisedURLDomain(apiDomain).getAsStringDangerous();
            URL apiDomainUrl = new URL(_apiDomain);
            return domain.equals((apiDomainUrl.getPort() == -1 ? apiDomainUrl.getHost() : apiDomainUrl.getHost() + ":" + apiDomainUrl.getPort()));
        } else {
            String normalisedCookieDomain = NormalisedInputType.normaliseSessionScopeOrThrowError(cookieDomain);

            if (cookieDomain.split(":").length > 1) {
                // means port may be provided
                String portString = cookieDomain.split((":"))[cookieDomain.split(":").length - 1];
                if (isNumeric(portString)) {
                    normalisedCookieDomain += ":" + portString;
                    domain = url.getPort() == -1 ? domain : domain + ":" + url.getPort();
                }
            }

            if (cookieDomain.startsWith(".")) {
                return ("." + domain).endsWith(normalisedCookieDomain);
            } else {
                return domain.equals(normalisedCookieDomain);
            }
        }
    }
}
