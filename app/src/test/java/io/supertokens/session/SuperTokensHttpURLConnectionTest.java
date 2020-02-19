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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Handler;

/* TODO:
 - while logged in, test that APIs that there is proper change in id refresh stored in storage
 - if not logged in, test that API that requires auth throws session expired.
 - Interception should not happen when domain is not the one that they gave
 - Proper change in anti-csrf token once access token resets
 */

@SuppressWarnings({"CatchMayIgnoreException", "FieldCanBeLocal", "SingleStatementInBlock"})
@RunWith(MockitoJUnitRunner.class)
public class SuperTokensHttpURLConnectionTest {
    private final String testBaseURL = "http://127.0.0.1:8080/";
    private final String refreshTokenEndpoint = testBaseURL + "refresh";
    private final String loginAPIURL = testBaseURL + "login";
    private final String userInfoAPIURL = testBaseURL + "userInfo";
    private final String logoutAPIURL = testBaseURL + "logout";
    private final String testHeaderAPIURL = testBaseURL + "header";
    private final String testCheckDeviceInfoAPIURL = testBaseURL + "checkDeviceInfo";
    private final String testPingAPIURL = testBaseURL + "testPing";
    private final String testErrorAPIURL = testBaseURL + "testError";
    private final String testCheckCustomRefresh = testBaseURL + "checkCustomHeader";

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

    // - session should not exist when user calls log out - use doesSessionExist***
    @Test
    public void httpUrlConnection_testThatSessionShouldNotExistWhenUserCallsLogOut() throws Exception {
        TestUtils.startST();
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, null);
        CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(context), null));

        //do a login request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
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

    // - device info tests***
    @Test
    public void httpUrlConnection_testThatDeviceInfoIsBeingSent() throws Exception {
        TestUtils.startST();
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, null);
        CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(context), null));


        HttpURLConnection checkDeviceInfo = SuperTokensHttpURLConnection.newRequest(new URL(testCheckDeviceInfoAPIURL), null);

        if (checkDeviceInfo.getResponseCode() != 200) {
            throw new Exception("checkDeviceInfo request failed");
        }

        JsonObject requestHeader = new JsonParser().parse(TestUtils.getBodyFromConnection(checkDeviceInfo)).getAsJsonObject();
        checkDeviceInfo.disconnect();

        if (!(requestHeader.get("supertokens-sdk-name").getAsString().equals(Utils.PACKAGE_PLATFORM) &&
                requestHeader.get("supertokens-sdk-version").getAsString().equals(BuildConfig.VERSION_NAME))) {
            throw new Exception("request header did not contain/ incorrect device info");
        }

    }

    // - session should not exist when user's session fully expires - use doesSessionExist***
    @Test
    public void httpUrlConnection_testThatSessionShouldNotExistWhenSessionFullyExpires() throws Exception {

        //accessTokenValidity set to 4 seconds and refreshTokenValidity set to 5 seconds
        TestUtils.startST(4, true, 0.08333);
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, null);
        CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(context), null));


        //do a login request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
            }
        });

        if (loginRequestConnection.getResponseCode() != 200) {
            throw new Exception("Login request failed");
        }

        loginRequestConnection.disconnect();

        //wait for 7 seconds for idRefreshToken and AccessToken to expire
        Thread.sleep(7000);

        //check that session does not exist
        if (SuperTokens.doesSessionExist(context)) {
            throw new Exception("Session exists after full expiry");
        }

        HttpURLConnection userInfoConnection = SuperTokensHttpURLConnection.newRequest(new URL(userInfoAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("GET");
            }
        });

        //check that after full expiry userInfo responds with 440
        if (userInfoConnection.getResponseCode() != 440) {
            throw new Exception("Session still exists after full expiry");
        }
        userInfoConnection.disconnect();

        //check that session does not exist
        if (SuperTokens.doesSessionExist(context)) {
            throw new Exception("Session exists after full expiry");
        }
    }

    // - tests APIs that don't require authentication work, before, during and after logout - using our library.***
    @Test
    public void httpUrlConnection_testThatAPISThatDontRequireAuthenticationWorkCorrectly() throws Exception {
        TestUtils.startST();
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, null);
        CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(context), null));


        //test testPing api before login

        HttpURLConnection testPingConnection = SuperTokensHttpURLConnection.newRequest(new URL(testPingAPIURL), null);

        if (!TestUtils.getBodyFromConnection(testPingConnection).contains("success")) {
            throw new Exception("api failed ");
        }

        testPingConnection.disconnect();

        //do login request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
            }
        });

        if (loginRequestConnection.getResponseCode() != 200) {
            throw new Exception("Login request failed");
        }

        loginRequestConnection.disconnect();

        //check testPing api while logged in
        testPingConnection = SuperTokensHttpURLConnection.newRequest(new URL(testPingAPIURL), null);

        if (!TestUtils.getBodyFromConnection(testPingConnection).contains("success")) {
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

        if (!TestUtils.getBodyFromConnection(testPingConnection).contains("success")) {
            throw new Exception("api failed ");
        }

        testPingConnection.disconnect();

    }

    // - test custom headers are being sent when logged in and when not.****
    @Test
    public void httpUrlConnection_testThatCustomHeadersAreProperlySent() throws Exception {
        TestUtils.startST();
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, null);
        CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(context), null));

        //login request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
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
        JsonObject customHeaderBody = new JsonParser().parse(TestUtils.getBodyFromConnection(customHeaderConnection)).getAsJsonObject();
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
        customHeaderBody = new JsonParser().parse(TestUtils.getBodyFromConnection(customHeaderConnection)).getAsJsonObject();
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
        TestUtils.startST();
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, null);
        CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(context), null));

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
        if (!(testErrorConnection.getResponseCode() == 500 && builder.toString().equals("custom message"))) {
            throw new Exception("error was not properly propagated");
        }
        testErrorConnection.disconnect();
    }

    // - testing doesSessionExist works fine when user is logged in***
    @Test
    public void httpUrlConnection_testThatDoesSessionWorkFineWhenUserIsLoggedIn() throws Exception {
        TestUtils.startST();
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, null);
        CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(context), null));

        //do a login Request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
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
        TestUtils.startST();
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, null);
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, null);
        CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(context), null));

        //login request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
            }
        });

        if (loginRequestConnection.getResponseCode() != 200) {
            throw new Exception("Login request failed");
        }

        loginRequestConnection.disconnect();

        //supertokensinit
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, null);

        //userInfo request
        HttpURLConnection userInfoRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(userInfoAPIURL), null);

        if (userInfoRequestConnection.getResponseCode() != 200) {
            throw new Exception("userInfo api failed");
        }

        if (TestUtils.getRefreshTokenCounter() != 0){
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
        TestUtils.startST();
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, null);
        CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(context), null));

        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
                con.setAllowUserInteraction(true);
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
        TestUtils.startST(3, true, 144000);
        HashMap<String, String> customRefreshParams = new HashMap<>();
        customRefreshParams.put("testKey", "testValue");
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, customRefreshParams);
        CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(context), null));


        //login request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
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

        if (!TestUtils.getBodyFromConnection(checkRefreshSetConnection).contains("true")) {
            throw new Exception("Custom RefreshAPi headers were not set");
        }
        checkRefreshSetConnection.disconnect();

        if (TestUtils.getRefreshTokenCounter() != 1){
            throw new Exception("Refresh API was called more/less than 1 time");
        }
    }

    // - Things should work if anti-csrf is disabled.****
    @Test
    public void httpUrlConnection_testThatThingsShouldWorkIfAntiCsrfIsDisabled() throws Exception {
        TestUtils.startST(3, false, 144000);
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, null);
        CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(context), null));

        //login request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
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

        if (TestUtils.getRefreshTokenCounter() != 1) {
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
        TestUtils.startST();
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, null);
        CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(context), null));

        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
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
        JsonObject userInfo = new JsonParser().parse(TestUtils.getBodyFromConnection(userInfoRequestConnection)).getAsJsonObject();

        if (userInfo.get("name") == null || userInfo.get("userId") == null){
            throw new Exception("user Info was not properly sent ");
        }
    }

    // - multiple API calls in parallel when access token is expired (100 of them) and only 1 refresh should be called***
    @Test
    public void httpUrlConnection_testThatMultipleAPICallsInParallelAndOnly1RefreshShouldBeCalled() throws Exception{
        TestUtils.startST(3, true, 144000);
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, null);
        CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(context), null));

        //login request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
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
        if (TestUtils.getRefreshTokenCounter() != 1) {
            throw new Exception("The number of times the RefreshAPI was called is not the expected value");
        }

    }

    // - Check that eveyrthing works properly - login, access token expiry, refresh called once, userInfo result is proper, logout, check session does not exist.*****
    @Test
    public void  httpUrlConnection_testThatEverythingWorksProperly() throws Exception{
        TestUtils.startST(3, true, 144000);
        SuperTokens.init(context, refreshTokenEndpoint, sessionExpiryCode, null);
        CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(context), null));

        //login request
        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setRequestMethod("POST");
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

        if (TestUtils.getRefreshTokenCounter() != 1) {
            throw new Exception("refreshApi was called more/less than 1 time");
        }

        // TODO: check that the name and userId is there properly..

        JsonObject userInfo = new JsonParser().parse(TestUtils.getBodyFromConnection(userInfoRequestConnection)).getAsJsonObject();

        if (userInfo.get("name") == null || userInfo.get("userId") == null){
            throw new Exception("user Info was not properly sent ");
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
}
