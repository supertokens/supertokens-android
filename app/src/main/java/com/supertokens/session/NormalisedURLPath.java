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

import java.net.MalformedURLException;
import java.net.URL;

public class NormalisedURLPath {
    private String value;

    public NormalisedURLPath(String url) throws MalformedURLException {
        this.value = normaliseURLPathOrThrowError(url);
    }

    public boolean startWith(NormalisedURLPath other) {
        return this.value.startsWith(other.value);
    }

    public NormalisedURLPath appendPath(NormalisedURLPath other) throws MalformedURLException {
        return new NormalisedURLPath(this.value + other.value);
    }

    public String getAsStringDangerous() {
        return this.value;
    }

    static boolean isDomainGiven(String input) throws MalformedURLException {
        // If no dot, return false
        if (input.indexOf(".") == -1 || input.startsWith("/")) {
            return false;
        }

        try {
            URL url = new URL(input);
            return url.getHost().indexOf(".") != -1;
        } catch (Exception ignored) {}

        try {
            URL url = new URL("http://" + input);
            return url.getHost().indexOf(".") != -1;
        } catch (Exception ignored) {}

        return false;
    }

    static String normaliseURLPathOrThrowError(String input) throws MalformedURLException {
        String trimmedInput = input.trim();
        String output = "";

        try {
            if (!trimmedInput.startsWith("http://") && !trimmedInput.startsWith("https://")) {
                throw new MalformedURLException("Error converting to proper URL");
            }

            URL urlObj = new URL(trimmedInput);
            output = urlObj.getPath();

            if (output.length() == 0) {
                return output;
            }

            if (output.charAt(output.length() - 1) == '/') {
                return output.substring(0, output.length() - 1);
            }

            return output;
        } catch (Exception ignored) {}

        // If the input contains a . it means they have given a domain name.
        // So we try assuming that they have given a domain name + path
        if (
                (isDomainGiven(trimmedInput) || trimmedInput.startsWith("localhost")) &&
                        !trimmedInput.startsWith("http://") &&
                        !trimmedInput.startsWith("https://")
        ) {
            trimmedInput = "http://" + trimmedInput;
            return normaliseURLPathOrThrowError(trimmedInput);
        }

        if (trimmedInput.charAt(0) != '/') {
            trimmedInput = "/" + trimmedInput;
        }

        // at this point, we should be able to convert it into a fake URL and recursively call this function.
        try {
            // test that we can convert this to prevent an infinite loop
            new URL("http://example.com" + trimmedInput);
            return normaliseURLPathOrThrowError("http://example.com" + trimmedInput);
        } catch (Exception e) {
            throw new MalformedURLException("Please provide a valid URL path");
        }
    }
}
