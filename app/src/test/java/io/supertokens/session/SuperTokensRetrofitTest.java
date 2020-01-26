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
import okhttp3.OkHttpClient;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.File;
import java.util.logging.Handler;

@SuppressWarnings({"CatchMayIgnoreException", "FieldCanBeLocal"})
@RunWith(MockitoJUnitRunner.class)
public class SuperTokensRetrofitTest {
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
    public void dummy() {

    }
//
//    @Before
//    public void beforeAll() {
//        SuperTokens.isInitCalled = false;
//        Mockito.mock(TextUtils.class);
//        Mockito.mock(Looper.class);
//        Mockito.mock(Handler.class);
//        mockSharedPrefs = new MockSharedPrefs();
//        Mockito.when(application.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(mockSharedPrefs);
//        Mockito.when(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(mockSharedPrefs);
//        Mockito.when(application.getString(R.string.supertokensIdRefreshKey)).thenReturn(testIdRefreskPrefsKey);
//        Mockito.when(application.getString(R.string.supertokensAntiCSRFTokenKey)).thenReturn(testAntiCSRFPrefsKey);
//        Mockito.when(application.getString(R.string.supertokensSetCookieHeaderKey)).thenReturn("Set-Cookie");
//        Mockito.when(application.getString(R.string.supertokensAntiCSRFHeaderKey)).thenReturn("anti-csrf");
//        Mockito.when(application.getString(R.string.supertokensIdRefreshCookieKey)).thenReturn("sIdRefreshToken");
//        Mockito.when(application.getString(R.string.supertokensNameHeaderKey)).thenReturn("supertokens-sdk-name");
//        Mockito.when(application.getString(R.string.supertokensVersionHeaderKey)).thenReturn("supertokens-sdk-version");
//        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();
//        clientBuilder.interceptors().add(new SuperTokensInterceptor());
//        clientBuilder.cookieJar(new PersistentCookieJar(new SetCookieCache(), new SharedPrefsCookiePersistor(context)));
//        okHttpClient = clientBuilder.build();
//        retrofitInstance = new Retrofit.Builder()
//                .baseUrl(testBaseURL)
//                .addConverterFactory(GsonConverterFactory.create())
//                .client(okHttpClient)
//                .build();
//        retrofitTestAPIService = retrofitInstance.create(RetrofitTestAPIService.class);
//    }
//
//    private void resetAccessTokenValidity(int lifetime) throws IOException {
//        Retrofit instance = new Retrofit.Builder()
//                .addConverterFactory(GsonConverterFactory.create())
//                .baseUrl(testBaseURL)
//                .build();
//        RetrofitTestAPIService service = instance.create(RetrofitTestAPIService.class);
//        Response<Void> response = service.reset(lifetime).execute();
//        if ( response.code() != 200 ) {
//            throw new IOException("Could not connect to reset API");
//        }
//    }
//
//    private int getRefreshTokenCounter() throws IOException {
//        Retrofit instance = new Retrofit.Builder()
//                .addConverterFactory(GsonConverterFactory.create())
//                .baseUrl(testBaseURL)
//                .build();
//        RetrofitTestAPIService service = instance.create(RetrofitTestAPIService.class);
//        Response<GetRefreshCounterResponse> response = service.refreshCounter().execute();
//        if ( response.code() != 200 ) {
//            throw new IOException("getRefreshCounter responded with an invalid format");
//        }
//
//        if ( response.body() == null ) {
//            throw new IOException("getRefreshCounter responded with an invalid format");
//        }
//
//        return response.body().counter;
//    }
//
//    @Test
//    public void retrofit_requestFailsIfInitNotCalled() {
//        boolean failed = true;
//        try {
//            resetAccessTokenValidity(10);
//            Response<Void> response = retrofitTestAPIService.login().execute();
//        } catch (IOException e) {
//            if ( e.getMessage().equals("SuperTokens.init function needs to be called before using interceptors") ) {
//                failed = false;
//            }
//        } catch (Exception e) {}
//
//        assertTrue(!failed);
//    }
//
//    @Test
//    public void retrofit_refreshFailsIfInitNotCalled() {
//        boolean failed = true;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokensInterceptor.attemptRefreshingSession(okHttpClient);
//        } catch (IllegalAccessException e) {
//            if ( e.getMessage().equals("SuperTokens.init function needs to be called before using attemptRefreshingSession") ) {
//                failed = false;
//            }
//        } catch (Exception e) {}
//
//        assertTrue(!failed);
//    }
//
//    @Test
//    public void retrofit_refreshIsCalledAfterAccessTokenExpiry() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(3);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//            Response<Void> loginResponse = retrofitTestAPIService.login().execute();
//            if ( loginResponse.code() != 200 ) {
//                throw new Exception("Error making login request");
//            }
//            Thread.sleep(5000);
//
//            Response<Void> userInfoResponse = retrofitTestAPIService.userInfo().execute();
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
//    @Test
//    public void retrofit_refreshIsCalledIfAntiCSRFIsCleared() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//            Response<Void> loginResponse = retrofitTestAPIService.login().execute();
//            if ( loginResponse.code() != 200 ) {
//                throw new Exception("Error making login request");
//            }
//
//            AntiCSRF.removeToken(application);
//
//            Response<Void> userInfoResponse = retrofitTestAPIService.userInfo().execute();
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
//    @Test
//    public void retrofit_refreshShouldBeCalledOnlyOnceForMultipleThreads() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//
//            Response<Void> loginResponse = retrofitTestAPIService.login().execute();
//            if (loginResponse.code() != 200) {
//                throw new Exception("Error making login request");
//            }
//
//            Thread.sleep(10000);
//
//            List<Runnable> runnables = new ArrayList<>();
//            final List<Boolean> runnableResults = new ArrayList<>();
//            int runnableCount = 100;
//            final Object lock = new Object();
//
//            for(int i = 0; i < runnableCount; i++) {
//                final int position = i;
//                runnables.add(new Runnable() {
//                    @Override
//                    public void run() {
//                        try {
//                            Response<Void> userInfoResponse = retrofitTestAPIService.userInfo().execute();
//                            if ( userInfoResponse.code() != 200 ) {
//                                throw new Exception("User info API failed even after calling refresh");
//                            }
//                            synchronized (lock) {
//                                runnableResults.add(true);
//                            }
//                        } catch (Exception e) {
//                            synchronized (lock) {
//                                runnableResults.add(false);
//                            }
//                        }
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
//        } catch(Exception e) {
//            failed = true;
//        }
//
//        assertTrue(!failed);
//    }
//
//    @Test
//    public void retrofit_userInfoSucceedsAfterLoginWithoutCallingRefresh() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(3);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//
//            Response<Void> loginResponse = retrofitTestAPIService.login().execute();
//            if ( loginResponse.code() != 200 ) {
//                throw new Exception("Error making login request");
//            }
//
//            Response<Void> userInfoResponse = retrofitTestAPIService.userInfo().execute();
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
//    public void retrofit_sessionPossiblyExistsIsFalseAfterServerClearsIdRefresh() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//
//            Response<Void> loginResponse = retrofitTestAPIService.login().execute();
//            if ( loginResponse.code() != 200 ) {
//                throw new Exception("Error making login request");
//            }
//
//            Response<Void> logoutResponse = retrofitTestAPIService.logout().execute();
//            if (logoutResponse.code() != 200) {
//                throw new Exception("Error making logout request");
//            }
//
//            if ( SuperTokens.doesSessionExist(application) ) {
//                throw new Exception("Session active even after logout");
//            }
//        } catch(Exception e) {
//            failed = true;
//        }
//
//        assertTrue(!failed);
//    }
//
//    @Test
//    public void retrofit_apisWithoutAuthSucceedAfterLogout() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//            Response<Void> loginResponse = retrofitTestAPIService.login().execute();
//            if ( loginResponse.code() != 200 ) {
//                throw new Exception("Error making login request");
//            }
//
//            Response<Void> logoutResponse = retrofitTestAPIService.logout().execute();
//            if (logoutResponse.code() != 200) {
//                throw new Exception("Error making logout request");
//            }
//
//            Response<GetRefreshCounterResponse> refreshCounterResponse = retrofitTestAPIService.refreshCounter().execute();
//            if (refreshCounterResponse.code() != 200) {
//                throw new Exception("Manual call to refresh counter returned status code: " + refreshCounterResponse.code());
//            }
//        } catch (Exception e) {
//            failed = true;
//        }
//
//        assertTrue(!failed);
//    }
//
//    @Test
//    public void retrofit_userInfoAfterLogoutReturnsSessionExpired() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//            Response<Void> loginResponse = retrofitTestAPIService.login().execute();
//            if ( loginResponse.code() != 200 ) {
//                throw new Exception("Error making login request");
//            }
//
//            Response<Void> logoutResponse = retrofitTestAPIService.logout().execute();
//            if (logoutResponse.code() != 200) {
//                throw new Exception("Error making logout request");
//            }
//
//            Response<Void> userInfoResponse = retrofitTestAPIService.userInfo().execute();
//            if (userInfoResponse.code() != sessionExpiryCode) {
//                throw new Exception("User info did not return session expiry");
//            }
//        } catch (Exception e) {
//            failed = true;
//        }
//
//        assertTrue(!failed);
//    }
//
//    @Test
//    public void retrofit_testThatCustomHeadersAreSent() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//
//            Response<HeaderTestResponse> response = retrofitTestAPIService.testHeader("st").execute();
//            if (response.code() != 200) {
//                throw new IOException("testHeader API failed");
//            }
//
//            HeaderTestResponse headerTestResponse = response.body();
//            if (headerTestResponse == null) {
//                throw new Exception("testHeaderAPI returned with invalid response");
//            }
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
