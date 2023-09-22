package com.example.example;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.example.TestUtils;
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder;

import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

import com.google.gson.JsonObject;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensHttpURLConnection;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SuppressWarnings({"CatchMayIgnoreException", "FieldCanBeLocal", "SingleStatementInBlock"})
@RunWith(MockitoJUnitRunner.class)
public class AccessTokenHttpURLConnectionTests {
    private final String testBaseURL = Constants.apiDomain;
    private final String refreshTokenEndpoint = testBaseURL + "/refresh";
    private final String loginAPIURL = testBaseURL + "/login";
    private final String login218APIURL = testBaseURL + "/login-2.18";
    private final String userInfoAPIURL = testBaseURL + "/";
    private final String logoutAPIURL = testBaseURL + "/logout";
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

    @Test
    public void testThatAppropriateAccessTokenPayloadIsSent() throws Exception {
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

        JSONObject payload = SuperTokens.getAccessTokenPayloadSecurely(context);

        if (TestUtils.checkIfV3AccessTokenIsSupported()) {
            String[] expectedKeys = new String[]{
                    "sub",
                    "exp",
                    "iat",
                    "sessionHandle",
                    "refreshTokenHash1",
                    "parentRefreshTokenHash1",
                    "antiCsrfToken",
                    "iss"
            };

            if (payload.has("tId")) {
                expectedKeys = new String[]{
                    "sub",
                    "exp",
                    "iat",
                    "sessionHandle",
                    "refreshTokenHash1",
                    "parentRefreshTokenHash1",
                    "antiCsrfToken",
                    "iss",
                    "tId",
                };
            }

            if (payload.has("rsub")) {
                expectedKeys = new String[]{
                        "sub",
                        "exp",
                        "iat",
                        "sessionHandle",
                        "refreshTokenHash1",
                        "parentRefreshTokenHash1",
                        "antiCsrfToken",
                        "iss",
                        "tId",
                        "rsub"
                };
            }

            assert payload.length() == expectedKeys.length;

            for (int i = 0; i < payload.names().length(); i++) {
                String key = payload.names().getString(i);
                assert Arrays.asList(expectedKeys).contains(key);
            }
        } else {
            assert payload.length() == 0;
        }
    }

    @Test
    public void testThatSessionCreatedWithCDI218CanBeRefreshed() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain).build();

        HttpURLConnection loginRequestConnection = SuperTokensHttpURLConnection.newRequest(new URL(login218APIURL), new SuperTokensHttpURLConnection.PreConnectCallback() {
            @Override
            public void doAction(HttpURLConnection con) throws IOException {
                con.setDoOutput(true);
                con.setRequestMethod("POST");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("Content-Type", "application/json");

                JsonObject bodyJson = new JsonObject();
                bodyJson.addProperty("userId", Constants.userId);

                JsonObject payload = new JsonObject();
                payload.addProperty("asdf", 1);

                bodyJson.add("payload", payload);

                OutputStream outputStream = con.getOutputStream();
                outputStream.write(bodyJson.toString().getBytes(StandardCharsets.UTF_8));
                outputStream.close();
            }
        });

        if (loginRequestConnection.getResponseCode() != 200) {
            throw new Exception("Login request failed");
        }

        loginRequestConnection.disconnect();

        JSONObject payload = SuperTokens.getAccessTokenPayloadSecurely(context);

        assert payload.length() == 1;
        assert payload.getInt("asdf") == 1;

        SuperTokens.attemptRefreshingSession(context);

        if (TestUtils.checkIfV3AccessTokenIsSupported()) {
            JSONObject v3Payload = SuperTokens.getAccessTokenPayloadSecurely(context);

            String[] expectedKeys = new String[]{
                    "sub",
                    "exp",
                    "iat",
                    "sessionHandle",
                    "refreshTokenHash1",
                    "parentRefreshTokenHash1",
                    "antiCsrfToken",
                    "asdf"
            };

            if (v3Payload.has("tId")) {
                expectedKeys = new String[]{
                        "sub",
                        "exp",
                        "iat",
                        "sessionHandle",
                        "refreshTokenHash1",
                        "parentRefreshTokenHash1",
                        "antiCsrfToken",
                        "asdf",
                        "tId",
                };
            }

            if (v3Payload.has("rsub")) {
                expectedKeys = new String[]{
                        "sub",
                        "exp",
                        "iat",
                        "sessionHandle",
                        "refreshTokenHash1",
                        "parentRefreshTokenHash1",
                        "antiCsrfToken",
                        "asdf",
                        "rsub",
                };
            }

            if (v3Payload.has("rsub") && v3Payload.has("tId")) {
                expectedKeys = new String[]{
                        "sub",
                        "exp",
                        "iat",
                        "sessionHandle",
                        "refreshTokenHash1",
                        "parentRefreshTokenHash1",
                        "antiCsrfToken",
                        "asdf",
                        "tId",
                        "rsub"
                };
            }

            assert v3Payload.length() == expectedKeys.length;

            for (int i = 0; i < v3Payload.names().length(); i++) {
                String key = v3Payload.names().getString(i);
                assert Arrays.asList(expectedKeys).contains(key);
            }

            assert v3Payload.getInt("asdf") == 1;
        } else {
            JSONObject v2Payload = SuperTokens.getAccessTokenPayloadSecurely(context);
            assert v2Payload.length() == 1;
            assert v2Payload.getInt("asdf") == 1;
        }
    }
}
