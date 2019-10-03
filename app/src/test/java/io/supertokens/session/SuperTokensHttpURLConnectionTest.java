package io.supertokens.session;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.text.TextUtils;
import com.google.gson.Gson;
import io.supertokens.session.android.MockSharedPrefs;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Handler;

import static org.junit.Assert.assertTrue;

@SuppressWarnings({"CatchMayIgnoreException", "FieldCanBeLocal", "StatementWithEmptyBody", "SingleStatementInBlock"})
@RunWith(MockitoJUnitRunner.class)
public class SuperTokensHttpURLConnectionTest {
    private final String testBaseURL = "http://127.0.0.1:8080/api/";
    private final String refreshTokenEndpoint = testBaseURL + "refreshtoken";
    private final String testAPiURL = testBaseURL + "testing";
    private final String loginAPIURL = testBaseURL + "login";
    private final String logoutURL = testBaseURL + "logout";
    private final String resetAPIURL = testBaseURL + "testReset";
    private final String refreshCounterAPIURL = testBaseURL + "testRefreshCounter";
    private final String userInfoAPIURL = testBaseURL + "userInfo";

    private final int sessionExpiryCode = 440;
    private static MockSharedPrefs mockSharedPrefs;

    private static final String testIdRefreskPrefsKey = "st-test-id-refresh-prefs-key";
    private static final String testAntiCSRFPrefsKey = "st-test-anti-csrf-prefs-key";

    class UserInfoResponse {
        int counter;
    }

    @Mock
    Application application;
    @Mock
    Context context;

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
    }

    private void resetAccessTokenValidity(int lifetime) throws IOException {
        URL resetURL = new URL(resetAPIURL);
        HttpURLConnection con = (HttpURLConnection)resetURL.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json; utf-8");
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("atValidity", "" + lifetime);
        con.connect();
        if ( con.getResponseCode() != 200 ) {
            throw new IOException("Could not connect to reset API");
        }
    }

    private int getRefreshTokenCounter() throws IOException {
        URL refreshCounterURL = new URL(refreshCounterAPIURL);
        HttpURLConnection con = (HttpURLConnection)refreshCounterURL.openConnection();
        if ( con.getResponseCode() != 200 ) {
            throw new IOException("Could not connect to getRefreshCounter API");
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream()));
        StringBuilder builder = new StringBuilder();
        String line = reader.readLine();
        while (line != null) {
            builder.append(line).append("\n");
            line = reader.readLine();
        }

        reader.close();
        UserInfoResponse response = new Gson().fromJson(builder.toString(), UserInfoResponse.class);
        return response.counter;
    }

    @Test
    public void httpURLConnection_requestFailsIfInitNotCalled() {
        boolean failed = true;
        try {
            resetAccessTokenValidity(10);
            SuperTokensHttpURLConnection.newRequest(new URL(testBaseURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
                @SuppressWarnings("RedundantThrows")
                @Override
                public Integer runOnConnection(HttpURLConnection con) throws IOException {
                    return 0;
                }
            });
        } catch (IllegalAccessException e) {
            if ( e.getMessage().equals("SuperTokens.init function needs to be called before using newRequest") ) {
                failed = false;
            }
        } catch (Exception e) {}

        assertTrue(!failed);
    }

    @Test
    public void httpURLConnection_refreshFailsIfInitNotCalled() {
        boolean failed = true;
        try {
            resetAccessTokenValidity(10);
            SuperTokensHttpURLConnection.attemptRefreshingSession();
        } catch (IllegalAccessException e) {
            if ( e.getMessage().equals("SuperTokens.init function needs to be called before using attemptRefreshingSession") ) {
                failed = false;
            }
        } catch (Exception e) {}

        assertTrue(!failed);
    }

    @Test
    public void httpURLConnection_requestFailsIfCookieManagerNotSet() {
        boolean failed = true;
        CookieManager.setDefault(null);
        try {
            resetAccessTokenValidity(10);
            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
            SuperTokensHttpURLConnection.newRequest(new URL(testAPiURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
                @Override
                public Integer runOnConnection(HttpURLConnection con) throws IOException {
                    con.setRequestMethod("GET");
                    con.connect();
                    return con.getResponseCode();
                }
            });
        } catch(IllegalAccessException e) {
            if ( e.getMessage().equals("Please initialise a CookieManager.\n" +
                                "For example: new CookieManager(new SuperTokensPersistentCookieStore(context), null).\n" +
                                "SuperTokens provides a persistent cookie store called SuperTokensPersistentCookieStore.\n" +
                                "For more information visit our documentation.") ) {
                failed = false;
            }
        } catch(Exception e) {}

        assertTrue(!failed);
    }

    @Test
    public void httpURLConnection_testAPIsWithoutParams() {
        boolean failed = false;
        try {
            resetAccessTokenValidity(10);
            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
            CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(application), null));
            int getRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
                @Override
                public Integer runOnConnection(HttpURLConnection con) throws IOException {
                    con.setRequestMethod("POST");
                    con.connect();
                    return con.getResponseCode();
                }
            });
            if ( getRequestCode != 200 ) {
                failed = true;
            }
        } catch(Exception e) {
            failed = true;
        }

        assertTrue(!failed);
    }

    @Test
    public void httpURLConnection_testAPIsWithParams() {
        boolean failed = false;
        try {
            resetAccessTokenValidity(10);
            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
            CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(application), null));
            int getRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
                @Override
                public Integer runOnConnection(HttpURLConnection con) throws IOException {
                    con.setRequestMethod("POST");
                    con.setRequestProperty("TestingHeaderKey", "testValue");
                    con.connect();
                    return con.getResponseCode();
                }
            });
            if ( getRequestCode != 200 ) {
                failed = true;
            }
        } catch(Exception e) {
            failed = true;
        }

        assertTrue(!failed);
    }

    @Test
    public void httpURLConnection_refreshIsCalledAfterAccessTokenExpiry() {
        boolean failed = false;
        try {
            resetAccessTokenValidity(3);
            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
            CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(application), null));
            int loginRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
                @Override
                public Integer runOnConnection(HttpURLConnection con) throws IOException {
                    con.setRequestMethod("POST");
                    con.connect();
                    return con.getResponseCode();
                }
            });
            if ( loginRequestCode != 200 ) {
                throw new Exception("Error making login request");
            }
            Thread.sleep(5000);

            int userInfoResponseCode = SuperTokensHttpURLConnection.newRequest(new URL(userInfoAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
                @Override
                public Integer runOnConnection(HttpURLConnection con) throws IOException {
                    con.setRequestMethod("GET");
                    con.connect();
                    return con.getResponseCode();
                }
            });

            if ( userInfoResponseCode != 200 ) {
                throw new Exception("User info API failed even after calling refresh");
            }

            int refreshTokenCounter = getRefreshTokenCounter();
            if ( refreshTokenCounter != 1 ) {
                throw new Exception("Refresh token counter value is not the same as the expected value");
            }
        } catch (Exception e) {
            failed = true;
        }

        assertTrue(!failed);
    }

    @Test
    public void httpURLConnection_refreshIsCalledIfAntiCSRFIsCleared() {
        boolean failed = false;
        try {
            resetAccessTokenValidity(10);
            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
            CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(application), null));
            int loginRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
                @Override
                public Integer runOnConnection(HttpURLConnection con) throws IOException {
                    con.setRequestMethod("POST");
                    con.connect();
                    return con.getResponseCode();
                }
            });
            if ( loginRequestCode != 200 ) {
                throw new Exception("Error making login request");
            }
            AntiCSRF.removeToken(application);

            int userInfoResponseCode = SuperTokensHttpURLConnection.newRequest(new URL(userInfoAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
                @Override
                public Integer runOnConnection(HttpURLConnection con) throws IOException {
                    con.setRequestMethod("GET");
                    con.connect();
                    return con.getResponseCode();
                }
            });

            if ( userInfoResponseCode != 200 ) {
                throw new Exception("User info API failed even after calling refresh");
            }

            int refreshTokenCounter = getRefreshTokenCounter();
            if ( refreshTokenCounter != 1 ) {
                throw new Exception("Refresh token counter value is not the same as the expected value");
            }
        } catch (Exception e) {
            failed = true;
        }

        assertTrue(!failed);
    }

    @Test
    public void httpURLConnection_refreshShouldBeCalledOnlyOnceForMultipleThreads() {
        boolean failed = false;
        try {
            resetAccessTokenValidity(10);
            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
            CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(application), null));
            int loginRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
                @Override
                public Integer runOnConnection(HttpURLConnection con) throws IOException {
                    con.setRequestMethod("POST");
                    con.connect();
                    return con.getResponseCode();
                }
            });
            if ( loginRequestCode != 200 ) {
                throw new Exception("Error making login request");
            }

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
                            int userInfoResponseCode = SuperTokensHttpURLConnection.newRequest(new URL(userInfoAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
                                @Override
                                public Integer runOnConnection(HttpURLConnection con) throws IOException {
                                    con.setRequestMethod("GET");
                                    con.connect();
                                    int responseCode = con.getResponseCode();
                                    return con.getResponseCode();
                                }
                            });
                            if ( userInfoResponseCode != 200 ) {
                                throw new Exception("Error connecting to userInfo");
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
    public void httpURLConnection_userInfoSucceedsAfterLoginWithoutCallingRefresh() {
        boolean failed = false;
        try {
            resetAccessTokenValidity(10);
            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
            CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(application), null));
            int loginRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
                @Override
                public Integer runOnConnection(HttpURLConnection con) throws IOException {
                    con.setRequestMethod("POST");
                    con.connect();
                    return con.getResponseCode();
                }
            });
            if ( loginRequestCode != 200 ) {
                throw new Exception("Error making login request");
            }

            int userInfoResponseCode = SuperTokensHttpURLConnection.newRequest(new URL(userInfoAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
                @Override
                public Integer runOnConnection(HttpURLConnection con) throws IOException {
                    con.setRequestMethod("GET");
                    con.connect();
                    return con.getResponseCode();
                }
            });

            if ( userInfoResponseCode != 200 ) {
                throw new Exception("User info API failed even after calling refresh");
            }

            int refreshTokenCounter = getRefreshTokenCounter();
            if ( refreshTokenCounter != 0 ) {
                throw new Exception("Refresh token counter value is not the same as the expected value");
            }
        } catch (Exception e) {
            failed = true;
        }

        assertTrue(!failed);
    }

    @Test
    public void httpURLConnection_sessionPossiblyExistsIsFalseAfterServerClearsIdRefresh() {
        boolean failed = false;
        try {
            resetAccessTokenValidity(10);
            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
            CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(application), null));
            int loginRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
                @Override
                public Integer runOnConnection(HttpURLConnection con) throws IOException {
                    con.setRequestMethod("POST");
                    con.connect();
                    return con.getResponseCode();
                }
            });
            if ( loginRequestCode != 200 ) {
                throw new Exception("Error making login request");
            }

            int logoutRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(logoutURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
                @Override
                public Integer runOnConnection(HttpURLConnection con) throws IOException {
                    con.setRequestMethod("POST");
                    con.connect();
                    return con.getResponseCode();
                }
            });

            if (logoutRequestCode != 200) {
                throw new Exception("Error making logout request");
            }

            if ( SuperTokens.sessionPossiblyExists(application) ) {
                throw new Exception("Session active even after logout");
            }
        } catch(Exception e) {
            failed = true;
        }

        assertTrue(!failed);
    }

}
