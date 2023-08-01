package com.example.example;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.text.TextUtils;

import com.example.TestUtils;
import com.franmontiel.persistentcookiejar.PersistentCookieJar;
import com.franmontiel.persistentcookiejar.cache.SetCookieCache;
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor;
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder;
import com.google.gson.JsonObject;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensInterceptor;

import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import com.supertokens.session.android.RetrofitTestAPIService;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;

import okhttp3.OkHttpClient;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

@SuppressWarnings({"CatchMayIgnoreException", "FieldCanBeLocal", "SingleStatementInBlock"})
@RunWith(MockitoJUnitRunner.class)
public class AccessTokenRetrofitTests {
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

    @Test
    public void testThatAppropriateAccessTokenPayloadIsSent() throws Exception {
        com.example.TestUtils.startST();
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        //login request
        JsonObject body = new JsonObject();
        body.addProperty("userId", Constants.userId);
        Response<Void> loginResponse = retrofitTestAPIService.login(body).execute();

        if (loginResponse.code() != 200) {
            throw new Exception("Login request failed");
        }

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
                        "asdf",
                        "tId"
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
        new SuperTokens.Builder(context, Constants.apiDomain)
                .build();

        //login request
        JsonObject body = new JsonObject();
        body.addProperty("userId", Constants.userId);
        JsonObject payload = new JsonObject();
        payload.addProperty("asdf", 1);
        body.add("payload", payload);
        Response<Void> loginResponse = retrofitTestAPIService.login218(body).execute();

        if (loginResponse.code() != 200) {
            throw new Exception("Login request failed");
        }

        JSONObject payloadFromSt = SuperTokens.getAccessTokenPayloadSecurely(context);

        assert payloadFromSt.length() == 1;
        assert payloadFromSt.getInt("asdf") == 1;

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
                        "tId"
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
