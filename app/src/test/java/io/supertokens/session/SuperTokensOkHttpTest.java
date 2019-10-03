package io.supertokens.session;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.text.TextUtils;
import com.franmontiel.persistentcookiejar.PersistentCookieJar;
import com.franmontiel.persistentcookiejar.cache.SetCookieCache;
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor;
import com.google.gson.Gson;
import io.supertokens.session.android.MockSharedPrefs;
import okhttp3.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Handler;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@SuppressWarnings({"CatchMayIgnoreException", "FieldCanBeLocal"})
@RunWith(MockitoJUnitRunner.class)
public class SuperTokensOkHttpTest {
    private final String testBaseURL = "http://127.0.0.1:8080/api/";
    private final String refreshTokenEndpoint = testBaseURL + "refreshtoken";
    private final String testAPiURL = testBaseURL + "testing";
    private final String loginAPIURL = testBaseURL + "login";
    private final String logoutAPIURL = testBaseURL + "logout";
    private final String resetAPIURL = testBaseURL + "testReset";
    private final String refreshCounterAPIURL = testBaseURL + "testRefreshCounter";
    private final String userInfoAPIURL = testBaseURL + "userInfo";

    private final int sessionExpiryCode = 440;
    private static MockSharedPrefs mockSharedPrefs;
    private static OkHttpClient okHttpClient;

    private static final String testIdRefreskPrefsKey = "st-test-id-refresh-prefs-key";
    private static final String testAntiCSRFPrefsKey = "st-test-anti-csrf-prefs-key";

    @Mock
    Application application;
    @Mock
    Context context;

    class GetRefreshCounterResponse {
        int counter;
    }

    @Before
    public void beforeAll() {
        SuperTokens.isInitCalled = false;
        Mockito.mock(TextUtils.class);
        Mockito.mock(Looper.class);
        Mockito.mock(Handler.class);
        mockSharedPrefs = new MockSharedPrefs();
        Mockito.when(application.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(mockSharedPrefs);
        Mockito.when(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(mockSharedPrefs);
        Mockito.when(application.getString(R.string.supertokensIdRefreshKey)).thenReturn(testIdRefreskPrefsKey);
        Mockito.when(application.getString(R.string.supertokensAntiCSRFTokenKey)).thenReturn(testAntiCSRFPrefsKey);
        Mockito.when(application.getString(R.string.supertokensSetCookieHeaderKey)).thenReturn("Set-Cookie");
        Mockito.when(application.getString(R.string.supertokensAntiCSRFHeaderKey)).thenReturn("anti-csrf");
        Mockito.when(application.getString(R.string.supertokensIdRefreshCookieKey)).thenReturn("sIdRefreshToken");
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();
        clientBuilder.interceptors().add(new SuperTokensInterceptor());
        clientBuilder.cookieJar(new PersistentCookieJar(new SetCookieCache(), new SharedPrefsCookiePersistor(context)));
        okHttpClient = clientBuilder.build();
    }

    private void resetAccessTokenValidity(int lifetime) throws IOException {
        OkHttpClient resetApiClient = new OkHttpClient.Builder().build();
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
        Request request = new Request.Builder()
                .url(new URL(resetAPIURL))
                .method("POST", body)
                .header("Content-Type", "application/json; utf-8")
                .header("Accept", "application/json")
                .header("atValidity", "" + lifetime)
                .build();
        Response response = resetApiClient.newCall(request).execute();
        if ( response.code() != 200 ) {
            throw new IOException("Could not connect to reset API");
        }
    }

    private int getRefreshTokenCounter() throws IOException {
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

    @Test
    public void okHttp_requestFailsIfInitNotCalled() {
        boolean failed = true;
        try {
            resetAccessTokenValidity(10);
            Request request = new Request.Builder()
                    .url(testBaseURL)
                    .build();
            Response response = okHttpClient.newCall(request).execute();
            response.close();
        } catch (IOException e) {
            if ( e.getMessage().equals("SuperTokens.init function needs to be called before using interceptors") ) {
                failed = false;
            }
        } catch (Exception e) {}

        assertTrue(!failed);
    }

    @Test
    public void okHttp_refreshFailsIfInitNotCalled() {
        boolean failed = true;
        try {
            resetAccessTokenValidity(10);
            SuperTokensInterceptor.attemptRefreshingSession(okHttpClient);
        } catch (IllegalAccessException e) {
            if ( e.getMessage().equals("SuperTokens.init function needs to be called before using attemptRefreshingSession") ) {
                failed = false;
            }
        } catch (Exception e) {}

        assertTrue(!failed);
    }

    @Test
    public void okHttp_testApiWithoutParams() {
        boolean failed = false;
        try {
            resetAccessTokenValidity(10);
            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
            RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
            Request request = new Request.Builder()
                    .url(loginAPIURL)
                    .method("POST", body)
                    .build();
            Response response = okHttpClient.newCall(request).execute();
            if ( response.code() != 200 ) {
                failed = true;
            }
            response.close();
        } catch(Exception e) {
            failed = true;
        }

        assertTrue(!failed);
    }

    @Test
    public void okHttp_testApiWithParams() {
        boolean failed = false;
        try {
            resetAccessTokenValidity(10);
            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
            RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
            Request request = new Request.Builder()
                    .url(loginAPIURL)
                    .method("POST", body)
                    .header("TestingHeaderKey", "testValue")
                    .build();
            Response response = okHttpClient.newCall(request).execute();
            if ( response.code() != 200 ) {
                failed = true;
            }
            response.close();
        } catch(Exception e) {
            failed = true;
        }

        assertTrue(!failed);
    }

    @Test
    public void okHttp_refreshIsCalledAfterAccessTokenExpiry() {
        boolean failed = false;
        try {
            resetAccessTokenValidity(3);
            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
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

            int refreshTokenCounter = getRefreshTokenCounter();
            if ( refreshTokenCounter != 1 ) {
                throw new Exception("Refresh token counter value is not the same as the expected value");
            }
        } catch(Exception e) {
            failed = true;
        }

        assertTrue(!failed);
    }

    @Test
    public void okHttp_refreshIsCalledIfAntiCSRFIsCleared() {
        boolean failed = false;
        try {
            resetAccessTokenValidity(10);
            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);

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

            AntiCSRF.removeToken(application);

            Request userInfoRequest = new Request.Builder()
                    .url(userInfoAPIURL)
                    .build();

            Response userInfoResponse = okHttpClient.newCall(userInfoRequest).execute();
            if ( userInfoResponse.code() != 200 ) {
                throw new Exception("User info API failed even after calling refresh");
            }

            int refreshTokenCounter = getRefreshTokenCounter();
            if ( refreshTokenCounter != 1 ) {
                throw new Exception("Refresh token counter value is not the same as the expected value");
            }
        } catch(Exception e) {
            failed = true;
        }

        assertTrue(!failed);
    }

    @Test
    public void okHttp_refreshShouldBeCalledOnlyOnceForMultipleThreads() {
        boolean failed = false;
        try {
            resetAccessTokenValidity(10);
            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);

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

            Thread.sleep(10000);
            List<Runnable> runnables = new ArrayList<>();
            final List<Boolean> runnableResults = new ArrayList<>();
            int runnableCount = 100;
            final Object lock = new Object();

            for(int i = 0; i < runnableCount; i++) {
                final int position = i;
                runnables.add(new Runnable() {
                    @Override
                    public void run() {
                    try {
                        Request userInfoRequest = new Request.Builder()
                                .url(userInfoAPIURL)
                                .build();

                        Response userInfoResponse = okHttpClient.newCall(userInfoRequest).execute();
                        if ( userInfoResponse.code() != 200 ) {
                            throw new Exception("User info API failed even after calling refresh");
                        }
                        synchronized (lock) {
                            runnableResults.add(true);
                        }
                    } catch (Exception e) {
                        synchronized (lock) {
                            runnableResults.add(false);
                        }
                    }
                    }
                });
            }

            ExecutorService executorService = Executors.newFixedThreadPool(runnableCount);
            for ( int i = 0; i < runnables.size(); i++ ) {
                Runnable currentRunnable = runnables.get(i);
                executorService.submit(currentRunnable);
            }

            while(true) {
                Thread.sleep(1000);
                if ( runnableResults.size() == runnableCount ) {
                    break;
                }
            }

            if ( runnableResults.contains(false) ) {
                throw new Exception("One of the API calls failed");
            }

            int refreshCount = getRefreshTokenCounter();
            if ( refreshCount != 1 ) {
                throw new Exception("Refresh token counter value is not the same as the expected value");
            }
        } catch (Exception e) {
            failed = true;
        }

        assertTrue(!failed);
    }

    @Test
    public void okHttp_userInfoSucceedsAfterLoginWithoutCallingRefresh() {
        boolean failed = false;
        try {
            resetAccessTokenValidity(3);
            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
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

            Request userInfoRequest = new Request.Builder()
                    .url(userInfoAPIURL)
                    .build();

            Response userInfoResponse = okHttpClient.newCall(userInfoRequest).execute();
            if ( userInfoResponse.code() != 200 ) {
                throw new Exception("User info API failed even after calling refresh");
            }

            int refreshTokenCounter = getRefreshTokenCounter();
            if ( refreshTokenCounter != 0 ) {
                throw new Exception("Refresh token counter value is not the same as the expected value");
            }
        } catch(Exception e) {
            failed = true;
        }

        assertTrue(!failed);
    }

    @Test
    public void okHttp_sessionPossiblyExistsIsFalseAfterServerClearsIdRefresh() {
        boolean failed = false;
        try {
            resetAccessTokenValidity(10);
            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);

            RequestBody loginReqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{}");
            Request loginRequest = new Request.Builder()
                    .url(loginAPIURL)
                    .method("POST", loginReqBody)
                    .build();
            Response loginResponse = okHttpClient.newCall(loginRequest).execute();
            if (loginResponse.code() != 200) {
                throw new Exception("Error making login request");
            }
            loginResponse.close();

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

            if (SuperTokens.sessionPossiblyExists(application)) {
                throw new Exception("Session active even after logout");
            }
        } catch (Exception e) {
            failed = true;
        }

        assertTrue(!failed);
    }
}
