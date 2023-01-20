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
import com.franmontiel.persistentcookiejar.PersistentCookieJar;
import com.franmontiel.persistentcookiejar.cache.SetCookieCache;
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor;
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.jetbrains.annotations.NotNull;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
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
import com.supertokens.session.android.RetrofitTestAPIService;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

@SuppressWarnings({"CatchMayIgnoreException", "FieldCanBeLocal"})
@RunWith(MockitoJUnitRunner.class)
public class SuperTokensRetrofitTest {
    private final String testBaseURL = "http://127.0.0.1:8080/";
    private final String refreshTokenEndpoint = testBaseURL + "/refresh";
    private final String loginAPIURL = testBaseURL + "/login";
    private final String userInfoAPIURL = testBaseURL + "/";
    private final String logoutAPIURL = testBaseURL + "/logout";
    private final String testHeaderAPIURL = testBaseURL + "/header";

    private final int sessionExpiryCode = 401;
    private static OkHttpClient okHttpClient;
    private static Retrofit retrofitInstance;
    private static RetrofitTestAPIService retrofitTestAPIService;

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
        retrofitInstance = new Retrofit.Builder()
                .baseUrl(testBaseURL)
                .addConverterFactory(GsonConverterFactory.create())
                .client(okHttpClient)
                .build();
        retrofitTestAPIService = retrofitInstance.create(RetrofitTestAPIService.class);

        com.example.TestUtils.beforeEach();
    }

    @AfterClass
    public static void after() {
        com.example.TestUtils.afterAll();
    }

    // - testing doesSessionExist works fine when user is logged in***
    @Test
    public void retrofit_testDoesSessionExistWorksFineWhenUserIsLoggedIn() throws Exception{
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .tokenTransferMethod("cookie")
                .build();
        JsonObject body = new JsonObject();
        body.addProperty("userId", Constants.userId);
        Response <Void> loginResponse = retrofitTestAPIService.login(body).execute();

        if (loginResponse.code()!= 200){
            throw new Exception("login failed");
        }

        //check that session exists
        if (!SuperTokens.doesSessionExist(context)){
            throw new Exception("session does not exist");
        }
    }

    // - session should not exist when user calls log out - use doesSessionExist***
    @Test
    public void retrofit_testSessionShouldNotExitWhenUserCallsLogout() throws Exception{
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .tokenTransferMethod("cookie")
                .build();

        //do login request
        JsonObject body = new JsonObject();
        body.addProperty("userId", Constants.userId);
        Response <Void> loginResponse = retrofitTestAPIService.login(body).execute();

        if (loginResponse.code() != 200){
            throw new Exception("login failed");
        }

        //do logout request
        Response<Void> logoutResponse = retrofitTestAPIService.logout().execute();

        if (logoutResponse.code() != 200){
            throw new Exception("logout failed");
        }
        if (SuperTokens.doesSessionExist(context)){
            throw new Exception("Session exists after calling logout");
        }
    }

    // TODO NEMI: Re add this test when front token is implemented
    // - session should not exist when user's session fully expires - use doesSessionExist***
//    @Test
//    public void retrofit_testThatSessionShouldNotExistWhenSessionFullyExpires() throws Exception {
//
//        //accessTokenValidity set to 4 seconds and refreshTokenValidity set to 5 seconds
//        com.example.TestUtils.startST(4, true, 0.08333);
//        new SuperTokens.Builder(context, Constants.apiDomain).build();
//
//
//        //do a login request
//        Response<Void> loginResponse = retrofitTestAPIService.login().execute();
//        if (loginResponse.code() != 200){
//            throw new Exception("login failed");
//        }
//
//        //wait for 7 seconds for idRefreshToken and AccessToken to expire
//        Thread.sleep(7000);
//
//        //check that session does not exist
//        if (SuperTokens.doesSessionExist(context)) {
//            throw new Exception("Session exists after full expiry");
//        }
//
//        Response userInfoResponse = retrofitTestAPIService.userInfo().execute();
//
//        //check that after full expiry userInfo responds with 401
//        if (userInfoResponse.code() != 401) {
//            throw new Exception("Session still exists after full expiry");
//        }
//
//        //check that session does not exist
//        if (SuperTokens.doesSessionExist(context)) {
//            throw new Exception("Session exists after full expiry");
//        }
//    }
    // - tests APIs that don't require authentication work, before, during and after logout - using our library.***
    @Test
    public void retrofit_testThatAPISThatDontRequireAuthenticationWorkCorrectly() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .tokenTransferMethod("cookie")
                .build();


        //test testPing api before login
        Response<ResponseBody> testPingResponse = retrofitTestAPIService.testPing().execute();
        if (testPingResponse.body() == null){
            throw new Exception("testPing body is null");
        }

        if (!testPingResponse.body().string().contains("success")) {
            throw new Exception("api failed ");
        }


        //do login request
        JsonObject body = new JsonObject();
        body.addProperty("userId", Constants.userId);
        Response <Void> loginResponse = retrofitTestAPIService.login(body).execute();

        if (loginResponse.code() != 200) {
            throw new Exception("Login request failed");
        }

        //check testPing api while logged in
        testPingResponse = retrofitTestAPIService.testPing().execute();

        if (testPingResponse.body() == null){
            throw new Exception("testPing body is null");
        }

        if (!testPingResponse.body().string().contains("success")) {
            throw new Exception("api failed ");
        }

        // do logout request
        Response<Void> logoutResponse = retrofitTestAPIService.logout().execute();

        if (logoutResponse.code() != 200) {
            throw new Exception("Logout request failed");
        }


        //check testPing after logout
        testPingResponse = retrofitTestAPIService.testPing().execute();

        if (testPingResponse.body() == null){
            throw new Exception("testPing body is null");
        }

        if (!testPingResponse.body().string().contains("success")) {
            throw new Exception("api failed ");
        }
    }

    // - if any API throws error, it gets propogated to the user properly (with and without interception)***
    @Test
    public void retrofit_testThatAPIErrorsGetPropagatedToTheUserProperlyWithInterception() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .tokenTransferMethod("cookie")
                .build();

        Response<ResponseBody> testErrorResponse = retrofitTestAPIService.testError().execute();
        if (testErrorResponse.errorBody() == null){
            throw new Exception("testError body is null");
        }

        if (!(testErrorResponse.code() == 500 && testErrorResponse.errorBody().string().contains("test error message"))) {
            throw new Exception("error was not properly propagated");
        }
    }

    // - if any API throws error, it gets propogated to the user properly (with and without interception)***
    @Test
    public void retrofit_testThatAPIErrorsGetPropagatedToTheUserProperlyWithoutInterception() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .tokenTransferMethod("cookie")
                .build();

        OkHttpClient client = okHttpClient.newBuilder().build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(testBaseURL)
                .addConverterFactory(GsonConverterFactory.create())
                .client(client)
                .build();
        RetrofitTestAPIService retrofitTestAPIService1 = retrofit.create(RetrofitTestAPIService.class);

        Response<ResponseBody> testErrorResponse = retrofitTestAPIService1.testError().execute();

        if (testErrorResponse.errorBody() == null){
            throw new Exception("testError body is null");
        }
        if (!(testErrorResponse.code() == 500 && testErrorResponse.errorBody().string().contains("test error message"))) {
            throw new Exception("error was not properly propagated");
        }
    }
    // - Calling SuperTokens.init more than once works!****
    @Test
    public void retrofit_testThatCallingSuperTokensInitMoreThanOnceWorks() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .tokenTransferMethod("cookie")
                .build();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .tokenTransferMethod("cookie")
                .build();

        //login request
        JsonObject body = new JsonObject();
        body.addProperty("userId", Constants.userId);
        Response <Void> loginResponse = retrofitTestAPIService.login(body).execute();

        if (loginResponse.code() != 200) {
            throw new Exception("Login request failed");
        }

        //supertokensinit
        new SuperTokens.Builder(context, Constants.apiDomain)
                .tokenTransferMethod("cookie")
                .build();

        Response<ResponseBody> userInfoResponse = retrofitTestAPIService.userInfo().execute();
        if (userInfoResponse.code() != 200) {
            throw new Exception("User info API failed even after calling refresh");
        }

        if (com.example.TestUtils.getRefreshTokenCounter() != 0 ){
            throw new Exception("Refresh API was called ");
        }

        // do logout request
        Response<Void> logoutResponse = retrofitTestAPIService.logout().execute();
        if (logoutResponse.code() != 200){
            throw new Exception("logout failed");
        }

        //check that session does not exist
        if (SuperTokens.doesSessionExist(context)) {
            throw new Exception("Session exists when it should not");
        }
    }

    // - Things should work if anti-csrf is disabled.****
    @Test
    public void retrofit_testThatThingsShouldWorkIfAntiCsrfIsDisabled() throws Exception {
        com.example.TestUtils.startST(3, false, 144000);
        new SuperTokens.Builder(context, Constants.apiDomain)
                .tokenTransferMethod("cookie")
                .build();

        JsonObject body = new JsonObject();
        body.addProperty("userId", Constants.userId);
        Response <Void> loginResponse = retrofitTestAPIService.login(body).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }

        Thread.sleep(5000);

        Response<ResponseBody> userInfoResponse = retrofitTestAPIService.userInfo().execute();
        if (userInfoResponse.code() != 200) {
            throw new Exception("User info API failed even after calling refresh");
        }

        //check that the refresh API was only called once
        if (com.example.TestUtils.getRefreshTokenCounter() != 1) {
            throw new Exception("refresh API was called more/less than 1 time");
        }

        //check that logout is working correctly
        Response<Void> logoutResponse = retrofitTestAPIService.logout().execute();
        if (logoutResponse.code() != 200){
            throw new Exception("logout failed");
        }


        if (SuperTokens.doesSessionExist(context)){
            throw new Exception("Session exists when it should not");
        }

    }

    // - multiple API calls in parallel when access token is expired (100 of them) and only 1 refresh should be called***
    @Test
    public void okHttp_testThatMultipleAPICallsInParallelAndOnly1RefreshShouldBeCalled() throws Exception {
        com.example.TestUtils.startST(3, true, 144000);
        new SuperTokens.Builder(context, Constants.apiDomain)
                .tokenTransferMethod("cookie")
                .build();

        JsonObject body = new JsonObject();
        body.addProperty("userId", Constants.userId);
        Response <Void> loginResponse = retrofitTestAPIService.login(body).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }

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
                        Response<ResponseBody> userInfoResponse = retrofitTestAPIService.userInfo().execute();
                        if (userInfoResponse.code() != 200) {
                            throw new Exception("User info api failed even after refresh API call");
                        }
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

    // - Custom refresh API headers are sent****
    @Test
    public void okHttp_testThatCustomRefreshAPIHeadersAreSent() throws Exception {
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
                .tokenTransferMethod("cookie")
                .build();

        JsonObject body = new JsonObject();
        body.addProperty("userId", Constants.userId);
        Response<Void> loginResponse = retrofitTestAPIService.login(body).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }


        Thread.sleep(5000);

        Response<ResponseBody> userInfoResponse = retrofitTestAPIService.userInfo().execute();
        if (userInfoResponse.code() != 200) {
            throw new Exception("User info API failed even after calling refresh");
        }

        //getCustomRefreshAPIHeaders
        Response<ResponseBody> response = retrofitTestAPIService.checkCustomHeaders().execute();

        if (response.body() == null){
            throw new Exception("testError body is null");
        }

        if (!new Gson().fromJson(Objects.requireNonNull(response.body()).string(), JsonObject.class).get("value").getAsString().equals("custom-value")){
            throw new Exception("Custom parameters were not set");
        }

        if (com.example.TestUtils.getRefreshTokenCounter() != 1){
            throw new Exception("Refresh API was called more/less than 1 time");
        }
    }

    @Test
    public void okHttp_testThatMultipleInterceptorsAreThereAndTheyShouldAllWork() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .tokenTransferMethod("cookie")
                .build();
        OkHttpClient client = okHttpClient.newBuilder().addInterceptor(new customInterceptors()).build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(testBaseURL)
                .addConverterFactory(GsonConverterFactory.create())
                .client(client)
                .build();
        RetrofitTestAPIService retrofitTestAPIService1 = retrofit.create(RetrofitTestAPIService.class);

        Response<ResponseBody> multipleInterceptorResponse = retrofitTestAPIService1.multipleInterceptors().execute();
        if (multipleInterceptorResponse.code() != 200) {
            throw new Exception("Error when doing multipleInterceptors ");
        }
        if (!multipleInterceptorResponse.body().string().equals("success")) {
            throw new Exception("Request Interception did not take place");
        }
    }
    //- Check that eveyrthing works properly - login, access token expiry, refresh called once, userInfo result is proper, logout, check session does not exist.*****
    @Test
    public void retrofit_testThatEverythingShouldWork() throws Exception {
        com.example.TestUtils.startST(3, true, 144000);
        new SuperTokens.Builder(context, Constants.apiDomain)
                .tokenTransferMethod("cookie")
                .build();

        JsonObject body = new JsonObject();
        body.addProperty("userId", Constants.userId);
        Response <Void> loginResponse = retrofitTestAPIService.login(body).execute();
        if (loginResponse.code() != 200) {
            throw new Exception("Error making login request");
        }

        Thread.sleep(5000);

        Response<ResponseBody> userInfoResponse = retrofitTestAPIService.userInfo().execute();
        if (userInfoResponse.code() != 200) {
            throw new Exception("User info API failed even after calling refresh");
        }

        //check that the refresh API was only called once
        if (com.example.TestUtils.getRefreshTokenCounter() != 1) {
            throw new Exception("refresh API was called more/less than 1 time");
        }

        //check that logout is working correctly
        Response<Void> logoutResponse = retrofitTestAPIService.logout().execute();
        if (logoutResponse.code() != 200){
            throw new Exception("logout failed");
        }

        if (SuperTokens.doesSessionExist(context)){
            throw new Exception("Session exists when it should not");
        }

    }

    class customInterceptors implements Interceptor {
        @NotNull
        @Override
        public okhttp3.Response intercept(@NotNull Chain chain) throws IOException {
            Request.Builder requestBuilder = chain.request().newBuilder();
            requestBuilder.header("interceptorHeader", "value1");


            Request request = requestBuilder.build();

            return chain.proceed(request);
        }
    }
}
