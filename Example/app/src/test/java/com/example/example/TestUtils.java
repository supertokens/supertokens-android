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

package com.example;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;

import io.supertokens.session.SuperTokens;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TestUtils {

    private static final String testBaseURL = "http://127.0.0.1:8080/";
    private static final String beforeEachAPIURL = testBaseURL + "beforeeach";
    private static final String afterAPIURL = testBaseURL + "after";
    private static final String startSTAPIURL = testBaseURL + "startst";
    private static final String stopAPIURL = testBaseURL + "stop";
    private static final String refreshCounterAPIURL = testBaseURL + "refreshCounter";

    public static final String VERSION_NAME = "1.0.1";

    public static void setInitToFalse() throws NoSuchFieldException, IllegalAccessException, ClassNotFoundException {
            Class<?> forName = Class.forName("io.supertokens.session.SuperTokens");
            Field[] declaredFields = forName.getDeclaredFields();
            for (Field field : declaredFields) {
                if (field.getName().equals("isInitCalled")) {
                    field.setAccessible(true);
                    field.set(null, false);
                }
            }
    }

    public static void callBeforeEachAPI() {
        try {
            RequestBody reqbody = RequestBody.create(null, new byte[0]);
            OkHttpClient client = new OkHttpClient.Builder().build();
            Request request = new Request.Builder()
                    .url(new URL(beforeEachAPIURL))
                    .method("POST",reqbody)
                    .build();
            client.newCall(request).execute();
        } catch (Exception e) {
        }
    }

    public static void callAfterAPI() {
        try {
            RequestBody reqbody = RequestBody.create(null, new byte[0]);
            OkHttpClient client = new OkHttpClient.Builder().build();
            Request request = new Request.Builder()
                    .url(new URL(afterAPIURL))
                    .method("POST",reqbody)
                    .build();
            client.newCall(request).execute();
        } catch (Exception e) {

        }
    }

    public static void stopAPI() {
        try {
            OkHttpClient client = new OkHttpClient.Builder().build();
            Request request = new Request.Builder()
                    .url(new URL(stopAPIURL))
                    .build();
            client.newCall(request).execute();
        } catch (Exception e) {

        }
    }

    public static void startST() {
        startST(10, true,144000);
    }

    public static void startST(long validity, boolean AntiCsrf, double refreshTokenValidity) {
        try {
            final MediaType JSON
                    = MediaType.parse("application/json; charset=utf-8");
            RequestBody body = RequestBody.create(JSON, "{\"accessTokenValidity\": " + validity + ",\"setAntiCsrf\": " + AntiCsrf +",\"refreshTokenValidity\": "+refreshTokenValidity+"}");
            OkHttpClient client = new OkHttpClient.Builder().build();
            Request request = new Request.Builder()
                    .url(new URL(startSTAPIURL))
                    .post(body)
                    .build();
            client.newCall(request).execute();
        } catch (Exception e) {
        }
    }

    public static int getRefreshTokenCounter() throws IOException {
        OkHttpClient refreshTokenCounterClient = new OkHttpClient.Builder().build();
        Request request = new Request.Builder()
                .url(new URL(refreshCounterAPIURL))
                .build();
        Response response = refreshTokenCounterClient.newCall(request).execute();
        if ( response.code() != 200 ) {
            throw new IOException("Could not connect to getRefreshCounter API");
        }

        if ( response.body() == null ) {
            throw new IOException("getRefreshCounter responded with an invalid format");
        }

        String body = response.body().string();
        GetRefreshCounterResponse counterResponse = new Gson().fromJson(body, GetRefreshCounterResponse.class);
        response.close();
        return counterResponse.counter;
    }

    public static String getBodyFromConnection(HttpURLConnection connection) throws IOException{
        StringBuilder builder = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            String line = reader.readLine();
            while (line != null) {
                builder.append(line).append("\n");
                line = reader.readLine();
            }
        }
        return builder.toString();
    }

    public static class GetRefreshCounterResponse {
        int counter;
    }

    public static class HeaderTestResponse {
        boolean success;
    }

}
