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

package com.example.example;

public class Constants {
    public static String apiDomain = isolatedBackend();
    public static String userId = "testing-supertokens-android";

    private static String isolatedBackend() {
        String value = System.getenv("ST_TEST_BACKEND_URL");
        if (value == null || System.getenv("ST_TEST_RUN_ID") == null) {
            throw new IllegalStateException("Use the isolated ordinary fixture runner");
        }
        java.net.URI uri = java.net.URI.create(value);
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() < 1024 || uri.getPort() == 8080 || uri.getPort() == 9000) {
            throw new IllegalStateException("Expected isolated loopback backend URL");
        }
        return value;
    }
}
