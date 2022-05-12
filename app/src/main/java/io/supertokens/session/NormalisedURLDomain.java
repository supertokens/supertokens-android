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

package io.supertokens.session;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NormalisedURLDomain {
    private String value;

    public NormalisedURLDomain(String url) throws MalformedURLException {
        this.value = normaliseURLDomainOrThrowError(url);
    }

    public String getAsStringDangerous() {
        return this.value;
    }

    static boolean isAnIpAddress(String ipaddress) {
        Pattern pattern = Pattern.compile("^(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$");
        Matcher matcher = pattern.matcher(ipaddress);

        return matcher.matches();
    }

    static String normaliseURLDomainOrThrowError(String input, boolean ignoreProtocol) throws MalformedURLException {
        String trimmedInput = input.trim();
        String output = "";

        try {
            if (!trimmedInput.startsWith("http://") && !trimmedInput.startsWith("https://")) {
                throw new MalformedURLException("Error converting to a proper URL");
            }

            URL urlObj = new URL(trimmedInput);

            String portSuffix = "";

            if (urlObj.getPort() != -1) {
                portSuffix = ":" + urlObj.getPort();
            }

            if (ignoreProtocol) {
                if (urlObj.getHost().startsWith("localhost") || isAnIpAddress(urlObj.getHost())) {
                    output = "http://" + urlObj.getHost() + portSuffix;
                } else {
                    output = "https://" + urlObj.getHost() + portSuffix;
                }
            } else {
                output = urlObj.getProtocol() + "://" + urlObj.getHost() + portSuffix;
            }

            return output;
        } catch (Exception ignored) {}

        if (trimmedInput.startsWith("/")) {
            throw new MalformedURLException("Please provide a valid domain name");
        }

        // not a valid URL
        if (trimmedInput.indexOf(".") == 0) {
            trimmedInput = trimmedInput.substring(1);
        }

        // If the input contains a . it means they have given a domain name.
        // So we try assuming that they have given a domain name
        if (
                (trimmedInput.indexOf(".") != -1 || trimmedInput.startsWith("localhost")) &&
                        !trimmedInput.startsWith("http://") &&
                        !trimmedInput.startsWith("https://")
        ) {
            trimmedInput = "https://" + trimmedInput;

            try {
                new URL(trimmedInput);
                return normaliseURLDomainOrThrowError(trimmedInput, true);
            } catch (Exception ignored) {}
        }

        throw new MalformedURLException("Please provide a valid domain name");
    }

    static String normaliseURLDomainOrThrowError(String input) throws MalformedURLException {
        return normaliseURLDomainOrThrowError(input, false);
    }
}
