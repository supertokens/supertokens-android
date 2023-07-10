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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.text.TextUtils;

import com.example.example.Constants;
import com.example.example.R;
import com.franmontiel.persistentcookiejar.PersistentCookieJar;
import com.franmontiel.persistentcookiejar.cache.SetCookieCache;
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor;
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jetbrains.annotations.NotNull;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Handler;

import com.supertokens.session.CustomHeaderProvider;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensInterceptor;
import com.supertokens.session.SuperTokensPersistentCookieStore;
import com.supertokens.session.Utils;

import net.bytebuddy.implementation.bind.annotation.Super;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

@SuppressWarnings({"CatchMayIgnoreException", "FieldCanBeLocal", "deprecation"})
@RunWith(MockitoJUnitRunner.class)
public class SuperTokensOkHttpHeaderTests {
    private final String testBaseURL = Constants.apiDomain;
    private final String refreshTokenEndpoint = testBaseURL + "/refresh";
    private final String loginAPIURL = testBaseURL + "/login";
    private final String baseCustomAuthUrl = testBaseURL + "/base-custom-auth";
    private final String userInfoAPIURL = testBaseURL + "/";
    private final String logoutAPIURL = testBaseURL + "/logout";
    private final String logoutAltAPIURL = testBaseURL + "/logout-alt";
    private final String testHeaderAPIURL = testBaseURL + "/header";
    private final String testMultipleInterceptorsAPIURL = testBaseURL + "/multipleInterceptors";
    private final String testCheckDeviceInfoAPIURL = testBaseURL + "/checkDeviceInfo";
    private final String testErrorAPIURL = testBaseURL + "/testError";
    private final String testPingAPIURL = testBaseURL + "/ping";

    private final int sessionExpiryCode = 401;
    private static OkHttpClient okHttpClient;

    @Mock
    Context context;

    SharedPreferences mockedPrefs;

    @BeforeClass
    public static void beforeAll() throws Exception {
        com.example.TestUtils.beforeAll();
    }

    @Before
    public void beforeEach() throws NoSuchFieldException, IllegalAccessException, ClassNotFoundException {
        com.example.TestUtils.setInitToFalse();
        Mockito.mock(TextUtils.class);
        Mockito.mock(Looper.class);
        Mockito.mock(Handler.class);
        mockedPrefs = new SPMockBuilder().createSharedPreferences();
        Mockito.when(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenAnswer(invocation -> {
            return mockedPrefs;
        });
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();
        clientBuilder.interceptors().add(new SuperTokensInterceptor());
        clientBuilder.cookieJar(new PersistentCookieJar(new SetCookieCache(), new SharedPrefsCookiePersistor(context)));
        okHttpClient = clientBuilder.build();

        com.example.TestUtils.beforeEach();
    }

    @AfterClass
    public static void after() {
        com.example.TestUtils.afterAll();
    }


    @Test
    public void okHttpHeaders_requestFailsIfInitNotCalled() throws Exception {
        try {
            Request request = new Request.Builder()
                    .url(testBaseURL)
                    .build();
            Response response = okHttpClient.newCall(request).execute();
            response.close();
            throw new Exception("test failed");
        } catch (IOException e) {
            if (e.getMessage().equals("SuperTokens.init function needs to be called before using interceptors")) {
                return;
            }
        }
        throw new Exception("test failed");
    }

    @Test
    public void okHttpHeaders_testApiWithoutParams() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();
        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        Response response = okHttpClient.newCall(request).execute();
        if (response.code() != 200) {
            throw new Exception("test failed");
        }
        response.close();
    }

    @Test
    public void okHttpHeaders_testApiWithParams() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();
        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        Response response = okHttpClient.newCall(request).execute();
        if (response.code() != 200) {
            throw new Exception("test failed");
        }
        response.close();
    }

    @Test
    public void okHttpHeaders_refreshIsCalledAfterAccessTokenExpiry() throws Exception {
        com.example.TestUtils.startST(3, true, 144000);
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();
        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        Response loginResponse = okHttpClient.newCall(request).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }
        loginResponse.close();
        Thread.sleep(5000);

        Request userInfoRequest = new Request.Builder()
                .url(userInfoAPIURL)
                .build();

        Response userInfoResponse = okHttpClient.newCall(userInfoRequest).execute();
        if (userInfoResponse.code() != 200) {
            throw new Exception("User info API failed even after calling refresh");
        }

        userInfoResponse.close();

        int refreshTokenCounter = com.example.TestUtils.getRefreshTokenCounter();
        if (refreshTokenCounter != 1) {
            throw new Exception("Refresh token counter value is not the same as the expected value");
        }
    }

    // - session should not exist when user calls log out - use doesSessionExist***
    @Test
    public void okHttpHeaders_sessionShouldExistWhenUserCallsLogOut() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        Response loginResponse = okHttpClient.newCall(request).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }
        loginResponse.close();

        // do a request for logout
        RequestBody logoutReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
        request = new Request.Builder()
                .url(logoutAPIURL)
                .method("POST", logoutReqBody)
                .build();
        Response logoutResponse = okHttpClient.newCall(request).execute();

        if (logoutResponse.code() != 200) {
            throw new Exception("Error making logout request");
        }

        //check that session does not exist
        if (SuperTokens.doesSessionExist(context)) {
            throw new Exception("Session active even after logout");
        }
        logoutResponse.close();
    }

    // - testing doesSessionExist works fine when user is logged in***
    @Test
    public void okHttpHeaders_testDoesSessionExistWorkFineWhenUserIsLoggedIn() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        Response loginResponse = okHttpClient.newCall(request).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }
        loginResponse.close();

        if (!SuperTokens.doesSessionExist(context)) {
            throw new Exception("Session does not exist when logged in");
        }
    }

    // - Calling SuperTokens.init more than once works!****
    @Test
    public void okHttpHeaders_testThatCallingSuperTokensInitMoreThanOnceWorks() throws Exception {
        com.example.TestUtils.startST(10, true, 144000);
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        Response loginResponse = okHttpClient.newCall(request).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error when making login Request");
        }

        loginResponse.close();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        Request userInfoRequest = new Request.Builder()
                .url(userInfoAPIURL)
                .build();

        Response userInfoResponse = okHttpClient.newCall(userInfoRequest).execute();
        if (userInfoResponse.code() != 200) {
            throw new Exception("User info API failed even after calling refresh");
        }

        if (com.example.TestUtils.getRefreshTokenCounter() != 0) {
            throw new Exception("Refresh API was called");
        }

        // do a request for logout
        RequestBody logoutReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
        request = new Request.Builder()
                .url(logoutAPIURL)
                .method("POST", logoutReqBody)
                .build();
        Response logoutResponse = okHttpClient.newCall(request).execute();

        if (logoutResponse.code() != 200) {
            throw new Exception("Error making logout request");
        }
        logoutResponse.close();

        // check that session does not exist
        if (SuperTokens.doesSessionExist(context)) {
            throw new Exception("Session should not exist when logged out");
        }

    }

    // - if multiple interceptors are there, they should all work***
    @Test
    public void okHttpHeaders_testThatMultipleInterceptorsAreThereAndTheyShouldAllWork() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();
        OkHttpClient client = okHttpClient.newBuilder().addInterceptor(new customInterceptors()).build();

        RequestBody logoutReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
        Request request = new Request.Builder()
                .url(testMultipleInterceptorsAPIURL)
                .method("POST", logoutReqBody)
                .build();
        Response multipleInterceptorResponse = client.newCall(request).execute();

        if (multipleInterceptorResponse.code() != 200) {
            throw new Exception("Error when doing multipleInterceptors ");
        }

        if (!Objects.equals(Objects.requireNonNull(multipleInterceptorResponse.body()).string(), "success")) {
            throw new Exception("Request Interception did not take place");
        }
        multipleInterceptorResponse.close();
    }

    // - if any API throws error, it gets propogated to the user properly (with and without interception)***
    @Test
    public void okHttpHeaders_testThatAPIErrorsGetPropagatedToTheUserInterception() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        Request request = new Request.Builder()
                .url(testErrorAPIURL)
                .build();
        Response response = okHttpClient.newCall(request).execute();

        //check that the status code of the response is 500
        if (response.code() != 500) {
            throw new Exception("testError api did not return with proper status code");
        }

        //check that the custom error message is received
        if (!Objects.requireNonNull(response.body()).string().equals("test error message")) {
            throw new Exception("testError api did not have the custom error message");
        }
        response.close();


    }

    // - if any API throws error, it gets propogated to the user properly (with and without interception)***

    @Test
    public void okHttpHeaders_testThatAPIErrorsGetPropagatedToTheUserWithoutInterception() throws Exception {
        com.example.TestUtils.startST();

        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();
        OkHttpClient client = new OkHttpClient().newBuilder().build();
        Request request = new Request.Builder()
                .url(testErrorAPIURL)
                .build();
        Response response = client.newCall(request).execute();

        if (response.code() != 500) {
            throw new Exception("testError API did not return with proper status code");
        }

        if (!Objects.requireNonNull(response.body()).string().equals("test error message")) {
            throw new Exception("testError API did not return custom message ");
        }
        response.close();

    }

    // - User passed config should be sent as well****
    @Test
    public void okHttpHeaders_testThatUserPassedConfigShouldBeSentAsWell() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .tag("CustomTag")
                .build();
        Response userConfigResponse = okHttpClient.newCall(request).execute();

        if (userConfigResponse.code() != 200) {
            throw new Exception("login api failed");
        }

        //check that user config tag was set in the request
        if (!Objects.requireNonNull(userConfigResponse.request().tag()).toString().equals("CustomTag")) {
            throw new Exception("user config tag was not set");
        }
        userConfigResponse.close();

    }

    // - Things should work if anti-csrf is disabled.****
    @Test
    public void okHttpHeaders_testThatThingsShouldWorkIfAntiCsrfIsDisabled() throws Exception {
        com.example.TestUtils.startST(3, false, 144000);
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        Response loginResponse = okHttpClient.newCall(request).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }
        loginResponse.close();

        Thread.sleep(5000);

        Request userInfoRequest = new Request.Builder()
                .url(userInfoAPIURL)
                .build();

        Response userInfoResponse = okHttpClient.newCall(userInfoRequest).execute();
        if (userInfoResponse.code() != 200) {
            throw new Exception("User info API failed even after calling refresh");
        }
        userInfoResponse.close();

        //check that the refresh API was only called once
        if (com.example.TestUtils.getRefreshTokenCounter() != 1) {
            throw new Exception("refresh API was called more/less than 1 time");
        }

        //check that logout is working correctly
        RequestBody logoutReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
        request = new Request.Builder()
                .url(logoutAPIURL)
                .method("POST", logoutReqBody)
                .build();
        Response logoutResponse = okHttpClient.newCall(request).execute();

        if (logoutResponse.code() != 200) {
            throw new Exception("Error making logout request");
        }
        logoutResponse.close();

        if (SuperTokens.doesSessionExist(context)) {
            throw new Exception("Session exists when it should not");
        }
    }

    // - multiple API calls in parallel when access token is expired (100 of them) and only 1 refresh should be called***
    @Test
    public void okHttpHeaders_testThatMultipleAPICallsInParallelAndOnly1RefreshShouldBeCalled() throws Exception {
        com.example.TestUtils.startST(3, true, 144000);
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        Response loginResponse = okHttpClient.newCall(request).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }
        loginResponse.close();

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
                        Request userInfoRequest = new Request.Builder()
                                .url(userInfoAPIURL)
                                .build();

                        Response userInfoResponse = okHttpClient.newCall(userInfoRequest).execute();
                        if (userInfoResponse.code() != 200) {
                            throw new Exception("User info api failed even after refresh API call");
                        }
                        userInfoResponse.close();
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


    // TODO NEMI: Re add this test when front token is implemented
    // - session should not exist when user's session fully expires - use doesSessionExist***
//    @Test
//    public void okHttpHeaders_testThatSessionShouldNotExistWhenSessionFullyExpires() throws Exception {
//        com.example.TestUtils.startST(4, true, 0.08333);
//        new SuperTokens.Builder(context, Constants.apiDomain).build();
//
//        JsonObject bodyJson = new JsonObject();
//        bodyJson.addProperty("userId", Constants.userId);
//        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
//        Request request = new Request.Builder()
//                .url(loginAPIURL)
//                .method("POST", body)
//                .addHeader("Accept", "application/json")
//                .addHeader("Content-Type", "application/json")
//                .build();
//        Response userConfigResponse = okHttpClient.newCall(request).execute();
//
//        if (userConfigResponse.code() != 200) {
//            throw new Exception("login api failed");
//        }
//
//        userConfigResponse.close();
//
//        //wait for 7 seconds for idRefreshToken and AccessToken to expire
//        Thread.sleep(7000);
//
//        //check that session does not exist
//        if (SuperTokens.doesSessionExist(context)) {
//            throw new Exception("Session still exists");
//        }
//
//        Request userInfoRequest = new Request.Builder()
//                .url(userInfoAPIURL)
//                .build();
//
//        Response userInfoResponse = okHttpClient.newCall(userInfoRequest).execute();
//        if (!(userInfoResponse.code() == 401 && Objects.requireNonNull(userInfoResponse.body()).string().equals("Session expired"))) {
//            throw new Exception("Session did not expire");
//        }
//
//        //check that session does not exist
//        if (SuperTokens.doesSessionExist(context)) {
//            throw new Exception("Session still exists");
//        }
//        userInfoResponse.close();
//    }

    // - Custom refresh API headers are sent****
    @Test
    public void okHttpHeaders_testThatCustomRefreshAPIHeadersAreSent() throws Exception {
        com.example.TestUtils.startST(3, true, 144000);
        new SuperTokens.Builder(context, Constants.apiDomain).customHeaderProvider(new CustomHeaderProvider() {
                    @Override
                    public Map<String, String> getRequestHeaders(RequestType requestType) {
                        if (requestType == RequestType.REFRESH) {
                            Map<String, String> headers = new HashMap<>();
                            headers.put("custom-header", "custom-value");

                            return headers;
                        }

                        return null;
                    }
                })
                .build();

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        Response loginResponse = okHttpClient.newCall(request).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }
        loginResponse.close();

        Thread.sleep(5000);

        Request userInfoRequest = new Request.Builder()
                .url(userInfoAPIURL)
                .build();

        okHttpClient.newCall(userInfoRequest).execute();
        //getCustomRefreshAPIHeaders

        request = new Request.Builder()
                .url(testBaseURL + "/refreshHeader")
                .build();
        Response response = okHttpClient.newCall(request).execute();

        if (!(new Gson().fromJson(Objects.requireNonNull(response.body()).string(), JsonObject.class).get("value").getAsString().equals("custom-value"))) {
            throw new Exception("Custom parameters were not set");
        }

        if (com.example.TestUtils.getRefreshTokenCounter() != 1) {
            throw new Exception("Refresh API was called more/less than 1 time");
        }
    }

    // - tests APIs that don't require authentication work, before, during and after logout - using our library.***
    @Test
    public void okHttpHeaders_testThatAPIsThatDontNeedAuthenticationWorkProperly() throws Exception {
        com.example.TestUtils.startST(5, true, 144000);
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        //api request which does not require authentication
        Request request = new Request.Builder()
                .url(testPingAPIURL)
                .build();
        Response response = okHttpClient.newCall(request).execute();

        //check if api request is a success before login
        if (!Objects.requireNonNull(response.body()).string().equals("success")) {
            throw new Exception("testPingAPI failed");
        }

        //login request
        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request loginRequest = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        Response loginResponse = okHttpClient.newCall(loginRequest).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }
        loginResponse.close();

        //check if it works correctly while logged in
        response = okHttpClient.newCall(request).execute();
        if (!Objects.requireNonNull(response.body()).string().equals("success")) {
            throw new Exception("testPingAPI failed");
        }

        //do logout
        RequestBody logoutReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
        Request logoutRequest = new Request.Builder()
                .url(logoutAPIURL)
                .method("POST", logoutReqBody)
                .build();
        Response logoutResponse = okHttpClient.newCall(logoutRequest).execute();

        if (logoutResponse.code() != 200) {
            throw new Exception("Error making logout request");
        }
        logoutResponse.close();

        //check if it works correctly after log out
        response = okHttpClient.newCall(request).execute();
        if (!Objects.equals(Objects.requireNonNull(response.body()).string(), "success")) {
            throw new Exception("testPingAPI failed");
        }

    }

    // - Check that eveyrthing works properly - login, access token expiry, refresh called once, userInfo result is proper, logout, check session does not exist.*****
    @Test
    public void okHttpHeaders_testThatEverythingWorksProperly() throws Exception {
        com.example.TestUtils.startST(3, true, 144000);
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        Response loginResponse = okHttpClient.newCall(request).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }
        loginResponse.close();

        Thread.sleep(5000);

        Request userInfoRequest = new Request.Builder()
                .url(userInfoAPIURL)
                .build();

        Response userInfoResponse = okHttpClient.newCall(userInfoRequest).execute();
        if (userInfoResponse.code() != 200) {
            throw new Exception("User info API failed even after calling refresh");
        }

        //check that the refresh API was only called once
        if (com.example.TestUtils.getRefreshTokenCounter() != 1) {
            throw new Exception("refresh API was called more/less than 1 time");
        }

        userInfoResponse.close();

        //check that logout is working correctly
        RequestBody logoutReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
        request = new Request.Builder()
                .url(logoutAPIURL)
                .method("POST", logoutReqBody)
                .build();
        Response logoutResponse = okHttpClient.newCall(request).execute();

        if (logoutResponse.code() != 200) {
            throw new Exception("Error making logout request");
        }
        logoutResponse.close();

        if (SuperTokens.doesSessionExist(context)) {
            throw new Exception("Session exists when it should not");
        }
    }

    @Test
    public void okHttpHeaders_testThatGetAccessTokenWorksCorrectly() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        String accessToken = SuperTokens.getAccessToken(context);

        assert accessToken == null;

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        Response loginResponse = okHttpClient.newCall(request).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }
        loginResponse.close();

        accessToken = SuperTokens.getAccessToken(context);
        assert accessToken != null;

        RequestBody logoutReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
        request = new Request.Builder()
                .url(logoutAPIURL)
                .method("POST", logoutReqBody)
                .build();
        Response logoutResponse = okHttpClient.newCall(request).execute();

        if (logoutResponse.code() != 200) {
            throw new Exception("Error making logout request");
        }
        logoutResponse.close();

        accessToken = SuperTokens.getAccessToken(context);
        assert accessToken == null;
    }

    @Test
    public void okhttpHeaders_testThatDifferentCasingForAuthorizationHeaderWorksFine() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        Response loginResponse = okHttpClient.newCall(request).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }
        loginResponse.close();

        String accessToken = SuperTokens.getAccessToken(context);

        Request userInfoRequest = new Request.Builder()
                .url(userInfoAPIURL)
                .header("Authorization", "Bearer " + accessToken)
                .build();

        Response userInfoResponse = okHttpClient.newCall(userInfoRequest).execute();
        if (userInfoResponse.code() != 200) {
            throw new Exception("User info API failed");
        }

        Request userInfoRequestSmall = new Request.Builder()
                .url(userInfoAPIURL)
                .header("authorization", "Bearer " + accessToken)
                .build();

        Response userInfoResponseSmall = okHttpClient.newCall(userInfoRequest).execute();
        if (userInfoResponseSmall.code() != 200) {
            throw new Exception("User info API failed with lowercase");
        }
    }

    @Test
    public void okhttpHeaders_testThatManuallyAddingAnExpiredAccessTokenWorksNormally() throws Exception {
        com.example.TestUtils.startST(3);
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        Response loginResponse = okHttpClient.newCall(request).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }
        loginResponse.close();

        String accessToken = SuperTokens.getAccessToken(context);
        Thread.sleep(5000);

        Request userInfoRequest = new Request.Builder()
                .url(userInfoAPIURL)
                .header("Authorization", "Bearer " + accessToken)
                .build();

        Response userInfoResponse = okHttpClient.newCall(userInfoRequest).execute();
        if (userInfoResponse.code() != 200) {
            throw new Exception("User info API failed");
        }

        int refreshTokenCounter = com.example.TestUtils.getRefreshTokenCounter();
        if (refreshTokenCounter != 1) {
            throw new Exception("Refresh token counter value is not the same as the expected value");
        }
    }

    @Test
    public void okhttpHeaders_testThatGetAccessTokenCallsRefreshCorrectly() throws Exception {
        com.example.TestUtils.startST(3);
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        Response loginResponse = okHttpClient.newCall(request).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }
        loginResponse.close();

        String accessToken = SuperTokens.getAccessToken(context);

        assert accessToken != null;

        Thread.sleep(5000);

        String newAccessToken = SuperTokens.getAccessToken(context);
        assert newAccessToken != null;
        assert  newAccessToken != accessToken;

        int refreshTokenCounter = com.example.TestUtils.getRefreshTokenCounter();
        if (refreshTokenCounter != 1) {
            throw new Exception("Refresh token counter value is not the same as the expected value");
        }
    }

    @Test
    public void okhttpHeaders_testThatOldAccessTokenAfterSignOutWorksFine() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        Response loginResponse = okHttpClient.newCall(request).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }
        loginResponse.close();

        String accessToken = SuperTokens.getAccessToken(context);

        assert accessToken != null;

        RequestBody logoutReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
        request = new Request.Builder()
                .url(logoutAPIURL)
                .method("POST", logoutReqBody)
                .build();
        Response logoutResponse = okHttpClient.newCall(request).execute();

        if (logoutResponse.code() != 200) {
            throw new Exception("Error making logout request");
        }
        logoutResponse.close();

        Request userInfoRequest = new Request.Builder()
                .url(userInfoAPIURL)
                .header("Authorization", "Bearer " + accessToken)
                .build();

        Response userInfoResponse = okHttpClient.newCall(userInfoRequest).execute();
        if (userInfoResponse.code() != 200) {
            throw new Exception("User info API failed");
        }
    }

    @Test
    public void okhttpHeaders_testThatFrontTokenRemoveRemovesAccessAndRefreshAsWell() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        Response loginResponse = okHttpClient.newCall(request).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }
        loginResponse.close();

        String accessToken = Utils.getTokenForHeaderAuth(Utils.TokenType.ACCESS, context);
        String refreshToken = Utils.getTokenForHeaderAuth(Utils.TokenType.REFRESH, context);
        assert accessToken != null;
        assert refreshToken != null;

        RequestBody logoutReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
        request = new Request.Builder()
                .url(logoutAltAPIURL)
                .method("POST", logoutReqBody)
                .build();
        Response logoutResponse = okHttpClient.newCall(request).execute();

        if (logoutResponse.code() != 200) {
            throw new Exception("Error making logout request");
        }
        logoutResponse.close();

        String accessTokenAfter = Utils.getTokenForHeaderAuth(Utils.TokenType.ACCESS, context);
        String refreshTokenAfter = Utils.getTokenForHeaderAuth(Utils.TokenType.REFRESH, context);
        assert accessTokenAfter == null;
        assert refreshTokenAfter == null;
    }

    @Test
    public void okhttpHeaders_testThatAuthHeaderIsNotIgnoredEvenIfItMatchesTheStoredAccessToken() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("userId", Constants.userId);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson.toString());
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        Response loginResponse = okHttpClient.newCall(request).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }
        loginResponse.close();

        Thread.sleep(5000);
        Utils.setToken(Utils.TokenType.ACCESS, "myOwnHeHe", context);

        Request request2 = new Request.Builder()
                .url(baseCustomAuthUrl)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer myOwnHeHe")
                .build();
        Response response2 = okHttpClient.newCall(request).execute();
        if (response2.code() != 200) {
            throw new Exception("Error making api request");
        }
        response2.close();
    }

    //custom interceptors
    class customInterceptors implements Interceptor {
        @NotNull
        @Override
        public Response intercept(@NotNull Chain chain) throws IOException {
            Request.Builder requestBuilder = chain.request().newBuilder();
            requestBuilder.header("interceptorHeader", "value1");
            Request request = requestBuilder.build();

            return chain.proceed(request);
        }
    }
}
