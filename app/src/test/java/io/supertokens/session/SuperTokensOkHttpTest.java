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

import android.content.Context;
import android.os.Looper;
import android.text.TextUtils;
import com.franmontiel.persistentcookiejar.PersistentCookieJar;
import com.franmontiel.persistentcookiejar.cache.SetCookieCache;
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor;
import io.supertokens.session.android.MockSharedPrefs;
import okhttp3.*;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.File;
import java.io.IOException;
import java.util.logging.Handler;

@SuppressWarnings({"CatchMayIgnoreException", "FieldCanBeLocal", "deprecation"})
@RunWith(MockitoJUnitRunner.class)
public class SuperTokensOkHttpTest {
    private final String testBaseURL = "http://127.0.0.1:8080/";
    private final String refreshTokenEndpoint = testBaseURL + "refresh";
    private final String loginAPIURL = testBaseURL + "login";
    private final String userInfoAPIURL = testBaseURL + "userInfo";
    private final String logoutAPIURL = testBaseURL + "logout";
    private final String testHeaderAPIURL = testBaseURL + "header";

    private final int sessionExpiryCode = 440;
    private static MockSharedPrefs mockSharedPrefs;
    private static OkHttpClient okHttpClient;

    @Mock
    Context context;

    @BeforeClass
    public static void beforeAll() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("./testHelpers/startServer", "../com-root");
        pb.directory(new File("../"));
        Process process = pb.start();
        Thread.sleep(1000);
    }

    @Before
    public void beforeEach() {
        SuperTokens.isInitCalled = false;
        Mockito.mock(TextUtils.class);
        Mockito.mock(Looper.class);
        Mockito.mock(Handler.class);
        mockSharedPrefs = new MockSharedPrefs();
        Mockito.when(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(mockSharedPrefs);
        Mockito.when(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(mockSharedPrefs);
        Mockito.when(context.getString(R.string.supertokensIdRefreshSharedPrefsKey)).thenReturn("supertokens-android-idrefreshtoken-key");
        Mockito.when(context.getString(R.string.supertokensAntiCSRFTokenKey)).thenReturn("supertokens-android-anticsrf-key");
        Mockito.when(context.getString(R.string.supertokensAntiCSRFHeaderKey)).thenReturn("anti-csrf");
        Mockito.when(context.getString(R.string.supertokensIdRefreshHeaderKey)).thenReturn("id-refresh-token");
        Mockito.when(context.getString(R.string.supertokensNameHeaderKey)).thenReturn("supertokens-sdk-name");
        Mockito.when(context.getString(R.string.supertokensVersionHeaderKey)).thenReturn("supertokens-sdk-version");
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();
        clientBuilder.interceptors().add(new SuperTokensInterceptor());
        clientBuilder.cookieJar(new PersistentCookieJar(new SetCookieCache(), new SharedPrefsCookiePersistor(context)));
        okHttpClient = clientBuilder.build();

        TestUtils.callBeforeEachAPI();
    }

    @AfterClass
    public static void after() {
        TestUtils.callAfterAPI();
        TestUtils.stopAPI();
    }


    @Test
    public void okHttp_requestFailsIfInitNotCalled() throws Exception {
        try {
            Request request = new Request.Builder()
                    .url(testBaseURL)
                    .build();
            Response response = okHttpClient.newCall(request).execute();
            response.close();
            throw new Exception ("test failed");
        } catch (IOException e) {
            if (e.getMessage().equals("SuperTokens.init function needs to be called before using interceptors")) {
                return;
            }
        }
        throw new Exception ("test failed");
    }

//    @Test
//    public void okHttp_refreshFailsIfInitNotCalled() throws Exception {
//        try {
//            SuperTokensInterceptor.attemptRefreshingSession(okHttpClient);
//            throw new Exception ("test failed");
//        } catch (IllegalAccessException e) {
//            if ( e.getMessage().equals("SuperTokens.init function needs to be called before using attemptRefreshingSession") ) {
//                return;
//            }
//        }
//        throw new Exception ("test failed");
//    }

    @Test
    public void okHttp_testApiWithoutParams() throws Exception {
        TestUtils.startST();
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, null);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .build();
        Response response = okHttpClient.newCall(request).execute();
        if ( response.code() != 200 ) {
            throw new Exception ("test failed");
        }
        response.close();
    }

    @Test
    public void okHttp_testApiWithParams() throws Exception {
        TestUtils.startST();
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, null);
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", body)
                .header("TestingHeaderKey", "testValue")
                .build();
        Response response = okHttpClient.newCall(request).execute();
        if ( response.code() != 200 ) {
            throw new Exception ("test failed");
        }
        response.close();
    }

    @Test
    public void okHttp_refreshIsCalledAfterAccessTokenExpiry() throws Exception {
        TestUtils.startST(3);
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, null);
        RequestBody loginReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
        Request request = new Request.Builder()
                .url(loginAPIURL)
                .method("POST", loginReqBody)
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
        if ( userInfoResponse.code() != 200 ) {
            throw new Exception("User info API failed even after calling refresh");
        }

        int refreshTokenCounter = TestUtils.getRefreshTokenCounter();
        if ( refreshTokenCounter != 1 ) {
            throw new Exception("Refresh token counter value is not the same as the expected value");
        }
    }

//    @Test
//    public void okHttp_refreshIsCalledIfAntiCSRFIsCleared() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//
//            RequestBody loginReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
//            Request request = new Request.Builder()
//                    .url(loginAPIURL)
//                    .method("POST", loginReqBody)
//                    .build();
//            Response loginResponse = okHttpClient.newCall(request).execute();
//            if (loginResponse.code() != 200) {
//                throw new Exception("Error making login request");
//            }
//            loginResponse.close();
//
//            AntiCSRF.removeToken(application);
//
//            Request userInfoRequest = new Request.Builder()
//                    .url(userInfoAPIURL)
//                    .build();
//
//            Response userInfoResponse = okHttpClient.newCall(userInfoRequest).execute();
//            if ( userInfoResponse.code() != 200 ) {
//                throw new Exception("User info API failed even after calling refresh");
//            }
//
//            int refreshTokenCounter = getRefreshTokenCounter();
//            if ( refreshTokenCounter != 1 ) {
//                throw new Exception("Refresh token counter value is not the same as the expected value");
//            }
//        } catch(Exception e) {
//            failed = true;
//        }
//
//        assertTrue(!failed);
//    }
//
//    @SuppressWarnings("ConditionalBreakInInfiniteLoop")
//    @Test
//    public void okHttp_refreshShouldBeCalledOnlyOnceForMultipleThreads() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//
//            RequestBody loginReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
//            Request request = new Request.Builder()
//                    .url(loginAPIURL)
//                    .method("POST", loginReqBody)
//                    .build();
//            Response loginResponse = okHttpClient.newCall(request).execute();
//            if (loginResponse.code() != 200) {
//                throw new Exception("Error making login request");
//            }
//            loginResponse.close();
//
//            Thread.sleep(10000);
//            List<Runnable> runnables = new ArrayList<>();
//            final List<Boolean> runnableResults = new ArrayList<>();
//            int runnableCount = 100;
//            final Object lock = new Object();
//
//            for(int i = 0; i < runnableCount; i++) {
//                runnables.add(new Runnable() {
//                    @Override
//                    public void run() {
//                    try {
//                        Request userInfoRequest = new Request.Builder()
//                                .url(userInfoAPIURL)
//                                .build();
//
//                        Response userInfoResponse = okHttpClient.newCall(userInfoRequest).execute();
//                        if ( userInfoResponse.code() != 200 ) {
//                            throw new Exception("User info API failed even after calling refresh");
//                        }
//                        synchronized (lock) {
//                            runnableResults.add(true);
//                        }
//                    } catch (Exception e) {
//                        synchronized (lock) {
//                            runnableResults.add(false);
//                        }
//                    }
//                    }
//                });
//            }
//
//            ExecutorService executorService = Executors.newFixedThreadPool(runnableCount);
//            for ( int i = 0; i < runnables.size(); i++ ) {
//                Runnable currentRunnable = runnables.get(i);
//                executorService.submit(currentRunnable);
//            }
//
//            while(true) {
//                Thread.sleep(1000);
//                if ( runnableResults.size() == runnableCount ) {
//                    break;
//                }
//            }
//
//            if ( runnableResults.contains(false) ) {
//                throw new Exception("One of the API calls failed");
//            }
//
//            int refreshCount = getRefreshTokenCounter();
//            if ( refreshCount != 1 ) {
//                throw new Exception("Refresh token counter value is not the same as the expected value");
//            }
//        } catch (Exception e) {
//            failed = true;
//        }
//
//        assertTrue(!failed);
//    }
//
//    @Test
//    public void okHttp_userInfoSucceedsAfterLoginWithoutCallingRefresh() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(3);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//            RequestBody loginReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
//            Request request = new Request.Builder()
//                    .url(loginAPIURL)
//                    .method("POST", loginReqBody)
//                    .build();
//            Response loginResponse = okHttpClient.newCall(request).execute();
//            if (loginResponse.code() != 200) {
//                throw new Exception("Error making login request");
//            }
//            loginResponse.close();
//
//            Request userInfoRequest = new Request.Builder()
//                    .url(userInfoAPIURL)
//                    .build();
//
//            Response userInfoResponse = okHttpClient.newCall(userInfoRequest).execute();
//            if ( userInfoResponse.code() != 200 ) {
//                throw new Exception("User info API failed even after calling refresh");
//            }
//
//            int refreshTokenCounter = getRefreshTokenCounter();
//            if ( refreshTokenCounter != 0 ) {
//                throw new Exception("Refresh token counter value is not the same as the expected value");
//            }
//        } catch(Exception e) {
//            failed = true;
//        }
//
//        assertTrue(!failed);
//    }
//
//    @Test
//    public void okHttp_sessionPossiblyExistsIsFalseAfterServerClearsIdRefresh() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//
//            RequestBody loginReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
//            Request loginRequest = new Request.Builder()
//                    .url(loginAPIURL)
//                    .method("POST", loginReqBody)
//                    .build();
//            Response loginResponse = okHttpClient.newCall(loginRequest).execute();
//            if (loginResponse.code() != 200) {
//                throw new Exception("Error making login request");
//            }
//            loginResponse.close();
//
//            RequestBody logoutReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
//            Request logoutRequest = new Request.Builder()
//                    .url(logoutAPIURL)
//                    .method("POST", logoutReqBody)
//                    .build();
//            Response logoutResponse = okHttpClient.newCall(logoutRequest).execute();
//            if (logoutResponse.code() != 200) {
//                throw new Exception("Error making logout request");
//            }
//            logoutResponse.close();
//
//            if (SuperTokens.doesSessionExist(application)) {
//                throw new Exception("Session active even after logout");
//            }
//        } catch (Exception e) {
//            failed = true;
//        }
//
//        assertTrue(!failed);
//    }
//
//    @Test
//    public void okHttp_apisWithoutAuthSucceedAfterLogout() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//
//            RequestBody loginReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
//            Request loginRequest = new Request.Builder()
//                    .url(loginAPIURL)
//                    .method("POST", loginReqBody)
//                    .build();
//            Response loginResponse = okHttpClient.newCall(loginRequest).execute();
//            if (loginResponse.code() != 200) {
//                throw new Exception("Error making login request");
//            }
//            loginResponse.close();
//
//            RequestBody logoutReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
//            Request logoutRequest = new Request.Builder()
//                    .url(logoutAPIURL)
//                    .method("POST", logoutReqBody)
//                    .build();
//            Response logoutResponse = okHttpClient.newCall(logoutRequest).execute();
//            if (logoutResponse.code() != 200) {
//                throw new Exception("Error making logout request");
//            }
//            logoutResponse.close();
//
//            Request refreshCounterRequest = new Request.Builder()
//                    .url(new URL(refreshCounterAPIURL))
//                    .build();
//            Response refreshCounterResponse = okHttpClient.newCall(refreshCounterRequest).execute();
//
//            if (refreshCounterResponse.code() != 200) {
//                throw new Exception("Manual call to refresh counter returned status code: " + refreshCounterResponse.code());
//            }
//
//            refreshCounterResponse.close();
//        } catch (Exception e) {
//            failed = true;
//        }
//
//        assertTrue(!failed);
//    }
//
//    @Test
//    public void okHttp_userInfoAfterLogoutReturnsSessionExpired() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//
//            RequestBody loginReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
//            Request loginRequest = new Request.Builder()
//                    .url(loginAPIURL)
//                    .method("POST", loginReqBody)
//                    .build();
//            Response loginResponse = okHttpClient.newCall(loginRequest).execute();
//            if (loginResponse.code() != 200) {
//                throw new Exception("Error making login request");
//            }
//            loginResponse.close();
//
//            RequestBody logoutReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
//            Request logoutRequest = new Request.Builder()
//                    .url(logoutAPIURL)
//                    .method("POST", logoutReqBody)
//                    .build();
//            Response logoutResponse = okHttpClient.newCall(logoutRequest).execute();
//            if (logoutResponse.code() != 200) {
//                throw new Exception("Error making logout request");
//            }
//            logoutResponse.close();
//
//            Request userInfoRequest = new Request.Builder()
//                    .url(userInfoAPIURL)
//                    .build();
//
//            Response userInfoResponse = okHttpClient.newCall(userInfoRequest).execute();
//            if ( userInfoResponse.code() != sessionExpiryCode ) {
//                throw new Exception("User info did not return session expiry");
//            }
//            userInfoResponse.close();
//        } catch (Exception e) {
//            failed = true;
//        }
//
//        assertTrue(!failed);
//    }
//
//    @Test
//    public void okHttp_testThatCustomHeadersAreSent() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//
//            Request testHeaderRequest = new Request.Builder()
//                    .url(testHeaderAPIURL)
//                    .header("st-custom-header", "st")
//                    .build();
//
//            Response testHeaderResponse = okHttpClient.newCall(testHeaderRequest).execute();
//            if (testHeaderResponse.code() != 200) {
//                throw new IOException("testHeader API failed");
//            }
//
//            ResponseBody body = testHeaderResponse.body();
//
//            if (body == null) {
//                throw new Exception("testHeaderAPI returned with invalid response");
//            }
//
//            String bodyString = body.string();
//            HeaderTestResponse headerTestResponse = new Gson().fromJson(bodyString, HeaderTestResponse.class);
//            testHeaderResponse.close();
//
//            if (!headerTestResponse.success) {
//                throw new Exception("testHeader API returned false");
//            }
//        } catch (Exception e) {
//            failed = true;
//        }
//
//        assertTrue(!failed);
//    }
}
