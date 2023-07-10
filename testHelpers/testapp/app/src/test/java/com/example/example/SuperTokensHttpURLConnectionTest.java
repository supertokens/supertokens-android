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

/* TODO:
 - while logged in, test that APIs that there is proper change in id refresh stored in storage
 - if not logged in, test that API that requires auth throws session expired.
 - Interception should not happen when domain is not the one that they gave
 - Proper change in anti-csrf token once access token resets
 */


import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.franmontiel.persistentcookiejar.cache.SetCookieCache;
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor;
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.supertokens.session.CustomHeaderProvider;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensHttpURLConnection;
import com.supertokens.session.SuperTokensInterceptor;
import com.supertokens.session.SuperTokensPersistentCookieStore;

import net.bytebuddy.implementation.bind.annotation.Super;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import com.example.example.Constants;
import com.supertokens.session.Utils;

/**
 * With HttpURLConnection we only test for header based auth because in the test framework
 * we cannot read cookies that are httponly on the frontend which results in the SDK not
 * working correctly. Till we can find a solution for this, cookie based auth will need to be manually tested
 */

@SuppressWarnings({"CatchMayIgnoreException", "FieldCanBeLocal", "SingleStatementInBlock"})
@RunWith(MockitoJUnitRunner.class)
public class SuperTokensHttpURLConnectionTest {
    private final String testBaseURL = Constants.apiDomain;
    private final String refreshTokenEndpoint = testBaseURL + "/refresh";
    private final String loginAPIURL = testBaseURL + "/login";
    private final String baseCustomAuthUrl = testBaseURL + "/base-custom-auth";
    private final String userInfoAPIURL = testBaseURL + "/";
    private final String logoutAPIURL = testBaseURL + "/logout";
    private final String logoutAltAPIURL = testBaseURL + "/logout-alt";
    private final String testHeaderAPIURL = testBaseURL + "/testHeader";
    private final String testCheckDeviceInfoAPIURL = testBaseURL + "/checkDeviceInfo";
    private final String testPingAPIURL = testBaseURL + "/ping";
    private final String testErrorAPIURL = testBaseURL + "/testError";
    private final String testCheckCustomRefresh = testBaseURL + "/refreshHeader";

    private final int sessionExpiryCode = 401;

    @Mock
    Context context;

    SharedPreferences mockedPrefs;

    @BeforeClass
    public static void beforeAll() throws IOException, InterruptedException, ClassNotFoundException, NoSuchMethodException {
        com.example.TestUtils.beforeAll();
    }

    @Before
    public void beforeEach() throws NoSuchFieldException, IllegalAccessException, ClassNotFoundException {
        MockitoAnnotations.initMocks(this);
        Mockito.mock(TextUtils.class);
        Mockito.mock(Looper.class);
        Mockito.mock(Handler.class);
        mockedPrefs = new SPMockBuilder().createSharedPreferences();
        Mockito.when(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenAnswer(invocation -> {
            return mockedPrefs;
        });

        com.example.TestUtils.beforeEach();
    }

    @AfterClass
    public static void after() {
        com.example.TestUtils.afterAll();
    }

    // - session should not exist when user calls log out - use doesSessionExist***
    @Test
    public void httpUrlConnection_testThatSessionShouldNotExistWhenUserCallsLogOut() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain).build();

        //do a login request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setDoOutput(true);
                con.setRequestMethod("POST");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("Content-Type", "application/json");

                JsonObject bodyJson = new JsonObject();
                bodyJson.addProperty("userId", Constants.userId);

                OutputStream outputStream = con.getOutputStream();
                outputStream.write(bodyJson.toString().getBytes(StandardCharsets.UTF_8));
                outputStream.close();
            }
        });

        if (loginRequestConnection.getResponseCode() != 200) {
            throw new Exception("Login request failed");
        }

        loginRequestConnection.disconnect();

        // do a logout request
        HttpURLConnection logoutRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(logoutAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
            }
        });

        if (logoutRequestConnection.getResponseCode() != 200) {
            throw new Exception("Logout request failed");
        }

        logoutRequestConnection.disconnect();

        // check that session does not exist
        if (SuperTokens.doesSessionExist(context)) {
            throw new Exception("Session exists after calling logout");
        }
    }

    //     - tests APIs that don't require authentication work, before, during and after logout - using our library.***
    @Test
    public void httpUrlConnection_testThatAPISThatDontRequireAuthenticationWorkCorrectly() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain).build();


        //test testPing api before login

        HttpURLConnection testPingConnection = SuperTokensHttpURLConnection.newRequest(new URL(testPingAPIURL), null);

        if (!com.example.TestUtils.getBodyFromConnection(testPingConnection).contains("success")) {
            throw new Exception("api failed ");
        }

        testPingConnection.disconnect();

        //do login request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setDoOutput(true);
                con.setRequestMethod("POST");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("Content-Type", "application/json");

                JsonObject bodyJson = new JsonObject();
                bodyJson.addProperty("userId", Constants.userId);

                OutputStream outputStream = con.getOutputStream();
                outputStream.write(bodyJson.toString().getBytes(StandardCharsets.UTF_8));
                outputStream.close();
            }
        });

        if (loginRequestConnection.getResponseCode() != 200) {
            throw new Exception("Login request failed");
        }

        loginRequestConnection.disconnect();

        //check testPing api while logged in
        testPingConnection = SuperTokensHttpURLConnection.newRequest(new URL(testPingAPIURL), null);

        if (!com.example.TestUtils.getBodyFromConnection(testPingConnection).contains("success")) {
            throw new Exception("api failed ");
        }

        testPingConnection.disconnect();

        // do logout request
        HttpURLConnection logoutRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(logoutAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
            }
        });

        if (logoutRequestConnection.getResponseCode() != 200) {
            throw new Exception("Logout request failed");
        }

        logoutRequestConnection.disconnect();

        //check testPing after logout
        testPingConnection = SuperTokensHttpURLConnection.newRequest(new URL(testPingAPIURL), null);

        if (!com.example.TestUtils.getBodyFromConnection(testPingConnection).contains("success")) {
            throw new Exception("api failed ");
        }

        testPingConnection.disconnect();

    }

    // - test custom headers are being sent when logged in and when not.****
    @Test
    public void httpUrlConnection_testThatCustomHeadersAreProperlySent() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain).build();

        //login request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setDoOutput(true);
                con.setRequestMethod("POST");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("Content-Type", "application/json");

                JsonObject bodyJson = new JsonObject();
                bodyJson.addProperty("userId", Constants.userId);

                OutputStream outputStream = con.getOutputStream();
                outputStream.write(bodyJson.toString().getBytes(StandardCharsets.UTF_8));
                outputStream.close();
            }
        });

        if (loginRequestConnection.getResponseCode() != 200) {
            throw new Exception("Login request failed");
        }

        loginRequestConnection.disconnect();

        //send request with custom headers
        HttpURLConnection customHeaderConnection = SuperTokensHttpURLConnection.newRequest(new URL(testHeaderAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("GET");
                con.setRequestProperty("st-custom-header", "st");
            }
        });

        // verify custom headers response
        JsonObject customHeaderBody = new JsonParser().parse(com.example.TestUtils.getBodyFromConnection(customHeaderConnection)).getAsJsonObject();
        customHeaderConnection.disconnect();

        if (customHeaderConnection.getResponseCode() != 200) {
            throw new Exception("customHeader API failed");

        }

        if (!customHeaderBody.get("success").getAsBoolean()) {
            throw new Exception("Custom headers were not added");
        }

        // do logout request
        HttpURLConnection logoutRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(logoutAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
            }
        });

        if (logoutRequestConnection.getResponseCode() != 200) {
            throw new Exception("Logout request failed");
        }

        logoutRequestConnection.disconnect();


        //send request with custom headers after logout
        customHeaderConnection = SuperTokensHttpURLConnection.newRequest(new URL(testHeaderAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("GET");
                con.setRequestProperty("st-custom-header", "st");
            }
        });

        //request to verify custom headers after logout
        customHeaderBody = new JsonParser().parse(com.example.TestUtils.getBodyFromConnection(customHeaderConnection)).getAsJsonObject();
        customHeaderConnection.disconnect();

        if (customHeaderConnection.getResponseCode() != 200) {
            throw new Exception("customHeader API failed");

        }

        if (!customHeaderBody.get("success").getAsBoolean()) {
            throw new Exception("Custom headers were not added");
        }
    }

    // - if any API throws error, it gets propogated to the user properly (with and without interception)***
    @Test
    public void httpUrlConnection_testThatAPIErrorsGetPropagatedToTheUserProperly() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain).build();

        HttpURLConnection testErrorConnection = SuperTokensHttpURLConnection.newRequest(new URL(testErrorAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("GET");
            }
        });
        StringBuilder builder = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(testErrorConnection.getErrorStream()))) {
            String line = reader.readLine();
            builder.append(line);
        }
        if (!(testErrorConnection.getResponseCode() == 500 && builder.toString().equals("test error message"))) {
            throw new Exception("error was not properly propagated");
        }
        testErrorConnection.disconnect();
    }

    // - testing doesSessionExist works fine when user is logged in***
    @Test
    public void httpUrlConnection_testThatDoesSessionWorkFineWhenUserIsLoggedIn() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain).build();

        //do a login Request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setDoOutput(true);
                con.setRequestMethod("POST");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("Content-Type", "application/json");

                JsonObject bodyJson = new JsonObject();
                bodyJson.addProperty("userId", Constants.userId);

                OutputStream outputStream = con.getOutputStream();
                outputStream.write(bodyJson.toString().getBytes(StandardCharsets.UTF_8));
                outputStream.close();
            }
        });

        if (loginRequestConnection.getResponseCode() != 200) {
            throw new Exception("Login request failed");
        }

        if (!SuperTokens.doesSessionExist(context)) {
            throw new Exception("Session does not exist when it should");
        }
        loginRequestConnection.disconnect();


    }

    // - Calling SuperTokens.init more than once works!****
    @Test
    public void httpUrlConnection_testThatCllingSuperTokensInitMoreThanOnceWorks() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain).build();
        new SuperTokens.Builder(context, Constants.apiDomain).build();

        //login request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setDoOutput(true);
                con.setRequestMethod("POST");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("Content-Type", "application/json");

                JsonObject bodyJson = new JsonObject();
                bodyJson.addProperty("userId", Constants.userId);

                OutputStream outputStream = con.getOutputStream();
                outputStream.write(bodyJson.toString().getBytes(StandardCharsets.UTF_8));
                outputStream.close();
            }
        });

        if (loginRequestConnection.getResponseCode() != 200) {
            throw new Exception("Login request failed");
        }

        loginRequestConnection.disconnect();

        //supertokensinit
        new SuperTokens.Builder(context, Constants.apiDomain).build();

        //userInfo request
        HttpURLConnection userInfoRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(userInfoAPIURL), null);

        if (userInfoRequestConnection.getResponseCode() != 200) {
            throw new Exception("userInfo api failed");
        }

        if (com.example.TestUtils.getRefreshTokenCounter() != 0){
            throw new Exception("Refresh API was called");
        }

        // do logout request
        HttpURLConnection logoutRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(logoutAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
            }
        });

        if (logoutRequestConnection.getResponseCode() != 200) {
            throw new Exception("Logout request failed");
        }

        logoutRequestConnection.disconnect();

        //check that session does not exist
        if (SuperTokens.doesSessionExist(context)) {
            throw new Exception("Session exists when it should not");
        }

    }

    // - User passed config should be sent as well****

    @Test
    public void httpUrlConnection_testThatUserPassedConfigShouldBeSent() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain).build();

        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setDoOutput(true);
                con.setAllowUserInteraction(true);
                con.setRequestMethod("POST");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("Content-Type", "application/json");

                JsonObject bodyJson = new JsonObject();
                bodyJson.addProperty("userId", Constants.userId);

                OutputStream outputStream = con.getOutputStream();
                outputStream.write(bodyJson.toString().getBytes(StandardCharsets.UTF_8));
                outputStream.close();
            }
        });

        if (!loginRequestConnection.getAllowUserInteraction()) {
            throw new Exception("user config was not set");
        }
        loginRequestConnection.disconnect();
    }

    // - Custom refresh API headers are sent****
    @Test
    public void httpUrlConnection_testThatCustomRefreshHeadersAreSent() throws Exception {
        com.example.TestUtils.startST(3, true, 144000);
        new SuperTokens.Builder(context, Constants.apiDomain)
                .customHeaderProvider(new CustomHeaderProvider() {
                    @Override
                    public Map<String, String> getRequestHeaders(CustomHeaderProvider.RequestType requestType) {
                        if (requestType == RequestType.REFRESH) {
                            Map<String, String> headers = new HashMap<>();
                            headers.put("custom-header", "custom-header");

                            return headers;
                        }

                        return null;
                    }
                })
                .build();


        //login request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setDoOutput(true);
                con.setRequestMethod("POST");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("Content-Type", "application/json");

                JsonObject bodyJson = new JsonObject();
                bodyJson.addProperty("userId", Constants.userId);

                OutputStream outputStream = con.getOutputStream();
                outputStream.write(bodyJson.toString().getBytes(StandardCharsets.UTF_8));
                outputStream.close();
            }
        });

        if (loginRequestConnection.getResponseCode() != 200) {
            throw new Exception("Login request failed");
        }

        loginRequestConnection.disconnect();

        //wait for accessToken validity to expire
        Thread.sleep(5000);

        //userInfo request
        HttpURLConnection userInfoRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(userInfoAPIURL), null);

        if (userInfoRequestConnection.getResponseCode() != 200) {
            throw new Exception("userInfo api failed");
        }

        userInfoRequestConnection.disconnect();

        HttpURLConnection checkRefreshSetConnection = SuperTokensHttpURLConnection.newRequest(new URL(testCheckCustomRefresh), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("GET");
            }
        });

        if (!com.example.TestUtils.getBodyFromConnection(checkRefreshSetConnection).contains("custom-header")) {
            throw new Exception("Custom RefreshAPi headers were not set");
        }
        checkRefreshSetConnection.disconnect();

        if (com.example.TestUtils.getRefreshTokenCounter() != 1){
            throw new Exception("Refresh API was called more/less than 1 time");
        }
    }

    // - Things should work if anti-csrf is disabled.****
    @Test
    public void httpUrlConnection_testThatThingsShouldWorkIfAntiCsrfIsDisabled() throws Exception {
        com.example.TestUtils.startST(3, false, 144000);
        new SuperTokens.Builder(context, Constants.apiDomain).build();

        //login request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setDoOutput(true);
                con.setRequestMethod("POST");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("Content-Type", "application/json");

                JsonObject bodyJson = new JsonObject();
                bodyJson.addProperty("userId", Constants.userId);

                OutputStream outputStream = con.getOutputStream();
                outputStream.write(bodyJson.toString().getBytes(StandardCharsets.UTF_8));
                outputStream.close();
            }
        });

        if (loginRequestConnection.getResponseCode() != 200) {
            throw new Exception("Login request failed");
        }

        loginRequestConnection.disconnect();

        //wait for access token expiry
        Thread.sleep(5000);

        HttpURLConnection userInfoRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(userInfoAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("GET");
            }
        });

        if (userInfoRequestConnection.getResponseCode() != 200) {
            throw new Exception("userInfo api failed");
        }

        userInfoRequestConnection.disconnect();
        //check refresh was only called once

        if (com.example.TestUtils.getRefreshTokenCounter() != 1) {
            throw new Exception("refreshApi was called more/less than 1 time");
        }


        // do logout request
        HttpURLConnection logoutRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(logoutAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
            }
        });

        if (logoutRequestConnection.getResponseCode() != 200) {
            throw new Exception("Logout request failed");
        }

        logoutRequestConnection.disconnect();
        // check that session does not exist
        if (SuperTokens.doesSessionExist(context)) {
            throw new Exception("Session exists when it  should not");
        }

    }

    // - Calling connection.connect in preRequestCallback in HttpURLConnection should not be a problem****
    @Test
    public void httpUrlConnection_testThatCallingConnectionConnectInPreRequestCallBackIsNotAProblem() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain).build();

        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setDoOutput(true);
                con.setRequestMethod("POST");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("Content-Type", "application/json");

                JsonObject bodyJson = new JsonObject();
                bodyJson.addProperty("userId", Constants.userId);

                OutputStream outputStream = con.getOutputStream();
                outputStream.write(bodyJson.toString().getBytes(StandardCharsets.UTF_8));
                outputStream.close();
                con.connect();
            }
        });

        if (loginRequestConnection.getResponseCode() != 200){
            throw new Exception("login failed when doing con.connect() in preRequestCallBack");
        }
        loginRequestConnection.disconnect();

        //userInfo request
        HttpURLConnection userInfoRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(userInfoAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("GET");
                con.connect();
            }
        });

        if (userInfoRequestConnection.getResponseCode() != 200) {
            throw new Exception("userInfo api failed");
        }
    }

    // - multiple API calls in parallel when access token is expired (100 of them) and only 1 refresh should be called***
    @Test
    public void httpUrlConnection_testThatMultipleAPICallsInParallelAndOnly1RefreshShouldBeCalled() throws Exception{
        com.example.TestUtils.startST(3, true, 144000);
        new SuperTokens.Builder(context, Constants.apiDomain).build();

        //login request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setDoOutput(true);
                con.setRequestMethod("POST");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("Content-Type", "application/json");

                JsonObject bodyJson = new JsonObject();
                bodyJson.addProperty("userId", Constants.userId);

                OutputStream outputStream = con.getOutputStream();
                outputStream.write(bodyJson.toString().getBytes(StandardCharsets.UTF_8));
                outputStream.close();
            }
        });

        if (loginRequestConnection.getResponseCode() != 200) {
            throw new Exception("Login request failed");
        }

        loginRequestConnection.disconnect();

        // wait for access token to expire
        Thread.sleep(5000);

        List<Runnable> runnables = new ArrayList<>();
        final List<Boolean> runnableSuccess = new ArrayList<>();
        int runnableCount = 100;
        final Object lock = new Object();

        for (int i = 0; i < runnableCount; i++) {
            runnables.add(new Runnable() {
                @Override
                public void run() {
                    try {
                        HttpURLConnection userInfoConnection = SuperTokensHttpURLConnection.newRequest(new URL(userInfoAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
                            @Override
                            public void doAction(HttpURLConnection con) throws IOException {
                                con.setRequestMethod("GET");
                            }
                        });

                        if (userInfoConnection.getResponseCode() != 200) {
                            throw new Exception("User info api failed even after refresh API call");
                        }
                        userInfoConnection.disconnect();
                        synchronized (lock) {
                            runnableSuccess.add(true);
                        }
                    } catch (Exception e) {
                        synchronized (lock) {
                            runnableSuccess.add(false);
                        }
                    }
                }
            });
        }
        ExecutorService executorService = Executors.newFixedThreadPool(runnableCount);
        for (int i = 0; i < runnables.size(); i++) {
            Runnable currentRunnable = runnables.get(i);
            executorService.submit(currentRunnable);
        }

        while (true) {
            Thread.sleep(1000);
            if (runnableSuccess.size() == runnableCount) {
                break;
            }
        }
        if (runnableSuccess.contains(false)) {
            throw new Exception("one of the userInfoAPIs failed");
        }
        if (com.example.TestUtils.getRefreshTokenCounter() != 1) {
            throw new Exception("The number of times the RefreshAPI was called is not the expected value");
        }

    }

    // - Check that eveyrthing works properly - login, access token expiry, refresh called once, userInfo result is proper, logout, check session does not exist.*****
    @Test
    public void  httpUrlConnection_testThatEverythingWorksProperly() throws Exception{
        com.example.TestUtils.startST(3, true, 144000);
        new SuperTokens.Builder(context, Constants.apiDomain).build();

        //login request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setDoOutput(true);
                con.setRequestMethod("POST");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("Content-Type", "application/json");

                JsonObject bodyJson = new JsonObject();
                bodyJson.addProperty("userId", Constants.userId);

                OutputStream outputStream = con.getOutputStream();
                outputStream.write(bodyJson.toString().getBytes(StandardCharsets.UTF_8));
                outputStream.close();
            }
        });

        if (loginRequestConnection.getResponseCode() != 200) {
            throw new Exception("Login request failed");
        }

        loginRequestConnection.disconnect();

        //wait for access token expiry
        Thread.sleep(5000);

        HttpURLConnection userInfoRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(userInfoAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("GET");
            }
        });

        if (userInfoRequestConnection.getResponseCode() != 200) {
            throw new Exception("userInfo api failed");
        }
        //check refresh was only called once

        if (com.example.TestUtils.getRefreshTokenCounter() != 1) {
            throw new Exception("refreshApi was called more/less than 1 time");
        }

        userInfoRequestConnection.disconnect();

        // do logout request
        HttpURLConnection logoutRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(logoutAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
            }
        });

        if (logoutRequestConnection.getResponseCode() != 200) {
            throw new Exception("Logout request failed");
        }

        logoutRequestConnection.disconnect();

        // check that session does not exist
        if (SuperTokens.doesSessionExist(context)) {
            throw new Exception("Session exists when it  should not");
        }
    }

    @Test
    public void  httpUrlConnection_testThatFrontTokenRemoveRemovesAccessAndRefreshAsWell() throws Exception{
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain).build();

        //login request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setDoOutput(true);
                con.setRequestMethod("POST");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("Content-Type", "application/json");

                JsonObject bodyJson = new JsonObject();
                bodyJson.addProperty("userId", Constants.userId);

                OutputStream outputStream = con.getOutputStream();
                outputStream.write(bodyJson.toString().getBytes(StandardCharsets.UTF_8));
                outputStream.close();
            }
        });

        if (loginRequestConnection.getResponseCode() != 200) {
            throw new Exception("Login request failed");
        }

        loginRequestConnection.disconnect();

        String accessToken = Utils.getTokenForHeaderAuth(Utils.TokenType.ACCESS, context);
        String refreshToken = Utils.getTokenForHeaderAuth(Utils.TokenType.REFRESH, context);
        assert accessToken != null;
        assert refreshToken != null;

        // do logout request
        HttpURLConnection logoutRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(logoutAltAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
            }
        });

        if (logoutRequestConnection.getResponseCode() != 200) {
            throw new Exception("Logout request failed");
        }

        logoutRequestConnection.disconnect();

        if (com.example.TestUtils.getRefreshTokenCounter() != 0) {
            throw new Exception("refreshApi was called when it shouldnt have");
        }

        String accessTokenAfter = Utils.getTokenForHeaderAuth(Utils.TokenType.ACCESS, context);
        String refreshTokenAfter = Utils.getTokenForHeaderAuth(Utils.TokenType.REFRESH, context);
        assert accessTokenAfter == null;
        assert refreshTokenAfter == null;
    }

    @Test
    public void httpUrlConnection_testThatAuthHeaderIsNotIgnoredEvenIfItMatchesTheStoredAccessToken() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain).build();

        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setDoOutput(true);
                con.setRequestMethod("POST");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("Content-Type", "application/json");

                JsonObject bodyJson = new JsonObject();
                bodyJson.addProperty("userId", Constants.userId);

                OutputStream outputStream = con.getOutputStream();
                outputStream.write(bodyJson.toString().getBytes(StandardCharsets.UTF_8));
                outputStream.close();
            }
        });

        if (loginRequestConnection.getResponseCode() != 200) {
            throw new Exception("Login request failed");
        }

        loginRequestConnection.disconnect();

        Thread.sleep(5000);
        Utils.setToken(Utils.TokenType.ACCESS, "myOwnHeHe", context);

        HttpURLConnection connection = SuperTokensHttpURLConnection.newRequest(new URL(baseCustomAuthUrl), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setDoOutput(true);
                con.setRequestMethod("GET");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("Content-Type", "application/json");
                con.setRequestProperty("Authorization", "Bearer myOwnHeHe");
            }
        });

        if (connection.getResponseCode() != 200) {
            throw new Exception("Api request failed");
        }
    }
}
