package io.supertokens.session;

import android.app.Application;
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

/* TODO:
 - device info tests
 - multiple API calls in parallel when access token is expired (100 of them) and only 1 refresh should be called
 - session should not exist when user calls log out - use sessionPossiblyExists & check storage is empty
 - session should not exist when user's session fully expires - use sessionPossiblyExists & check storage is empty
 - while logged in, test that APIs that there is proper change in id refresh stored in storage
 - tests APIs that don't require authentication work after logout.
 - test custom headers are being sent when logged in and when not.
 - if not logged in, test that API that requires auth throws session expired.
 - if any API throws error, it gets propogated to the user properly (with and without interception)
 - if multiple interceptors are there, they should all work
 - testing attemptRefreshingSession works fine
 - testing sessionPossiblyExists works fine when user is logged in
 - Test everything without and without interception
 - Interception should not happen when domain is not the one that they gave
 - Calling SuperTokens.init more than once works!
 - Proper change in anti-csrf token once access token resets
 - User passed config should be sent as well
 - Custom refresh API headers are sent
 - Things should work if anti-csrf is disabled.
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

    private final int sessionExpiryCode = 440;
    private static MockSharedPrefs mockSharedPrefs;
    private static OkHttpClient okHttpClient;

    @Mock
    Application application;
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
        Mockito.when(application.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(mockSharedPrefs);
        Mockito.when(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(mockSharedPrefs);
        Mockito.when(application.getString(R.string.supertokensIdRefreshSharedPrefsKey)).thenReturn("supertokens-android-idrefreshtoken-key");
        Mockito.when(application.getString(R.string.supertokensAntiCSRFTokenKey)).thenReturn("supertokens-android-anticsrf-key");
        Mockito.when(application.getString(R.string.supertokensAntiCSRFHeaderKey)).thenReturn("anti-csrf");
        Mockito.when(application.getString(R.string.supertokensIdRefreshHeaderKey)).thenReturn("id-refresh-token");
        Mockito.when(application.getString(R.string.supertokensNameHeaderKey)).thenReturn("supertokens-sdk-name");
        Mockito.when(application.getString(R.string.supertokensVersionHeaderKey)).thenReturn("supertokens-sdk-version");
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
//    private final String testBaseURL = "http://127.0.0.1:8080/api/";
//    private final String refreshTokenEndpoint = testBaseURL + "refreshtoken";
//    private final String testAPiURL = testBaseURL + "testing";
//    private final String loginAPIURL = testBaseURL + "login";
//    private final String logoutURL = testBaseURL + "logout";
//    private final String resetAPIURL = testBaseURL + "testReset";
//    private final String refreshCounterAPIURL = testBaseURL + "testRefreshCounter";
//    private final String userInfoAPIURL = testBaseURL + "userInfo";
//    private final String testHeaderAPIURL = testBaseURL + "testHeader";
//
//    private final int sessionExpiryCode = 440;
//    private static MockSharedPrefs mockSharedPrefs;
//
//    private static final String testIdRefreskPrefsKey = "st-test-id-refresh-prefs-key";
//    private static final String testAntiCSRFPrefsKey = "st-test-anti-csrf-prefs-key";
//
//    class UserInfoResponse {
//        int counter;
//    }
//
//    class HeaderTestResponse {
//        boolean success;
//    }
//
//    @Mock
//    Application application;
//    @Mock
//    Context context;
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
//    }
//
//    private void resetAccessTokenValidity(int lifetime) throws IOException {
//        URL resetURL = new URL(resetAPIURL);
//        HttpURLConnection con = (HttpURLConnection)resetURL.openConnection();
//        con.setRequestMethod("POST");
//        con.setRequestProperty("Content-Type", "application/json; utf-8");
//        con.setRequestProperty("Accept", "application/json");
//        con.setRequestProperty("atValidity", "" + lifetime);
//        con.connect();
//        if ( con.getResponseCode() != 200 ) {
//            throw new IOException("Could not connect to reset API");
//        }
//    }
//
//    private int getRefreshTokenCounter() throws IOException {
//        URL refreshCounterURL = new URL(refreshCounterAPIURL);
//        HttpURLConnection con = (HttpURLConnection)refreshCounterURL.openConnection();
//        if ( con.getResponseCode() != 200 ) {
//            throw new IOException("Could not connect to getRefreshCounter API");
//        }
//
//        BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream()));
//        StringBuilder builder = new StringBuilder();
//        String line = reader.readLine();
//        while (line != null) {
//            builder.append(line).append("\n");
//            line = reader.readLine();
//        }
//
//        reader.close();
//        UserInfoResponse response = new Gson().fromJson(builder.toString(), UserInfoResponse.class);
//        return response.counter;
//    }
//
//    @Test
//    public void httpURLConnection_requestFailsIfInitNotCalled() {
//        boolean failed = true;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokensHttpURLConnection.newRequest(new URL(testBaseURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                @SuppressWarnings("RedundantThrows")
//                @Override
//                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                    return 0;
//                }
//            });
//        } catch (IllegalAccessException e) {
//            if ( e.getMessage().equals("SuperTokens.init function needs to be called before using newRequest") ) {
//                failed = false;
//            }
//        } catch (Exception e) {}
//
//        assertTrue(!failed);
//    }
//
//    @Test
//    public void httpURLConnection_refreshFailsIfInitNotCalled() {
//        boolean failed = true;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokensHttpURLConnection.attemptRefreshingSession();
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
//    public void httpURLConnection_requestFailsIfCookieManagerNotSet() {
//        boolean failed = true;
//        CookieManager.setDefault(null);
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//            SuperTokensHttpURLConnection.newRequest(new URL(testAPiURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                @Override
//                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                    con.setRequestMethod("GET");
//                    con.connect();
//                    return con.getResponseCode();
//                }
//            });
//        } catch(IllegalAccessException e) {
//            if ( e.getMessage().equals("Please initialise a CookieManager.\n" +
//                                "For example: new CookieManager(new SuperTokensPersistentCookieStore(context), null).\n" +
//                                "SuperTokens provides a persistent cookie store called SuperTokensPersistentCookieStore.\n" +
//                                "For more information visit our documentation.") ) {
//                failed = false;
//            }
//        } catch(Exception e) {}
//
//        assertTrue(!failed);
//    }
//
//    @Test
//    public void httpURLConnection_testAPIsWithoutParams() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//            CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(application), null));
//            int getRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                @Override
//                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                    con.setRequestMethod("POST");
//                    con.connect();
//                    return con.getResponseCode();
//                }
//            });
//            if ( getRequestCode != 200 ) {
//                failed = true;
//            }
//        } catch(Exception e) {
//            failed = true;
//        }
//
//        assertTrue(!failed);
//    }
//
//    @Test
//    public void httpURLConnection_testAPIsWithParams() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//            CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(application), null));
//            int getRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                @Override
//                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                    con.setRequestMethod("POST");
//                    con.setRequestProperty("TestingHeaderKey", "testValue");
//                    con.connect();
//                    return con.getResponseCode();
//                }
//            });
//            if ( getRequestCode != 200 ) {
//                failed = true;
//            }
//        } catch(Exception e) {
//            failed = true;
//        }
//
//        assertTrue(!failed);
//    }
//
//    @Test
//    public void httpURLConnection_refreshIsCalledAfterAccessTokenExpiry() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(3);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//            CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(application), null));
//            int loginRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                @Override
//                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                    con.setRequestMethod("POST");
//                    con.connect();
//                    return con.getResponseCode();
//                }
//            });
//            if ( loginRequestCode != 200 ) {
//                throw new Exception("Error making login request");
//            }
//            Thread.sleep(5000);
//
//            int userInfoResponseCode = SuperTokensHttpURLConnection.newRequest(new URL(userInfoAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                @Override
//                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                    con.setRequestMethod("GET");
//                    con.connect();
//                    return con.getResponseCode();
//                }
//            });
//
//            if ( userInfoResponseCode != 200 ) {
//                throw new Exception("User info API failed even after calling refresh");
//            }
//
//            int refreshTokenCounter = getRefreshTokenCounter();
//            if ( refreshTokenCounter != 1 ) {
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
//    public void httpURLConnection_refreshIsCalledIfAntiCSRFIsCleared() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//            CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(application), null));
//            int loginRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                @Override
//                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                    con.setRequestMethod("POST");
//                    con.connect();
//                    return con.getResponseCode();
//                }
//            });
//            if ( loginRequestCode != 200 ) {
//                throw new Exception("Error making login request");
//            }
//            AntiCSRF.removeToken(application);
//
//            int userInfoResponseCode = SuperTokensHttpURLConnection.newRequest(new URL(userInfoAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                @Override
//                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                    con.setRequestMethod("GET");
//                    con.connect();
//                    return con.getResponseCode();
//                }
//            });
//
//            if ( userInfoResponseCode != 200 ) {
//                throw new Exception("User info API failed even after calling refresh");
//            }
//
//            int refreshTokenCounter = getRefreshTokenCounter();
//            if ( refreshTokenCounter != 1 ) {
//                throw new Exception("Refresh token counter value is not the same as the expected value");
//            }
//        } catch (Exception e) {
//            failed = true;
//        }
//
//        assertTrue(!failed);
//    }
//
//    @SuppressWarnings("ConditionalBreakInInfiniteLoop")
//    @Test
//    public void httpURLConnection_refreshShouldBeCalledOnlyOnceForMultipleThreads() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//            CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(application), null));
//            int loginRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                @Override
//                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                    con.setRequestMethod("POST");
//                    con.connect();
//                    return con.getResponseCode();
//                }
//            });
//            if ( loginRequestCode != 200 ) {
//                throw new Exception("Error making login request");
//            }
//
//            Thread.sleep(10000);
//            List<Runnable> runnables = new ArrayList<>();
//            final List<Boolean> runnableResults = new ArrayList<>();
//            int runnableCount = 100;
//            final Object lock = new Object();
//            for(int i = 0; i < runnableCount; i++) {
//                runnables.add(new Runnable() {
//                    @Override
//                    public void run() {
//                        try {
//                            int userInfoResponseCode = SuperTokensHttpURLConnection.newRequest(new URL(userInfoAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                                @Override
//                                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                                    con.setRequestMethod("GET");
//                                    con.connect();
//                                    return con.getResponseCode();
//                                }
//                            });
//                            if ( userInfoResponseCode != 200 ) {
//                                throw new Exception("Error connecting to userInfo");
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
//    public void httpURLConnection_userInfoSucceedsAfterLoginWithoutCallingRefresh() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//            CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(application), null));
//            int loginRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                @Override
//                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                    con.setRequestMethod("POST");
//                    con.connect();
//                    return con.getResponseCode();
//                }
//            });
//            if ( loginRequestCode != 200 ) {
//                throw new Exception("Error making login request");
//            }
//
//            int userInfoResponseCode = SuperTokensHttpURLConnection.newRequest(new URL(userInfoAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                @Override
//                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                    con.setRequestMethod("GET");
//                    con.connect();
//                    return con.getResponseCode();
//                }
//            });
//
//            if ( userInfoResponseCode != 200 ) {
//                throw new Exception("User info API failed even after calling refresh");
//            }
//
//            int refreshTokenCounter = getRefreshTokenCounter();
//            if ( refreshTokenCounter != 0 ) {
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
//    public void httpURLConnection_sessionPossiblyExistsIsFalseAfterServerClearsIdRefresh() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//            CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(application), null));
//            int loginRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                @Override
//                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                    con.setRequestMethod("POST");
//                    con.connect();
//                    return con.getResponseCode();
//                }
//            });
//            if ( loginRequestCode != 200 ) {
//                throw new Exception("Error making login request");
//            }
//
//            int logoutRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(logoutURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                @Override
//                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                    con.setRequestMethod("POST");
//                    con.connect();
//                    return con.getResponseCode();
//                }
//            });
//
//            if (logoutRequestCode != 200) {
//                throw new Exception("Error making logout request");
//            }
//
//            if ( SuperTokens.sessionPossiblyExists(application) ) {
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
//    public void httpURLConnection_apisWithoutAuthSucceedAfterLogout() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//            CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(application), null));
//            int loginRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                @Override
//                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                    con.setRequestMethod("POST");
//                    con.connect();
//                    return con.getResponseCode();
//                }
//            });
//            if ( loginRequestCode != 200 ) {
//                throw new Exception("Error making login request");
//            }
//
//            int logoutRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(logoutURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                @Override
//                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                    con.setRequestMethod("POST");
//                    con.connect();
//                    return con.getResponseCode();
//                }
//            });
//
//            if (logoutRequestCode != 200) {
//                throw new Exception("Error making logout request");
//            }
//
//            SuperTokensHttpURLConnection.newRequest(new URL(refreshCounterAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Void>() {
//
//                @Override
//                public Void runOnConnection(HttpURLConnection con) throws IOException {
//                    int responseCode = con.getResponseCode();
//                    if (responseCode != 200) {
//                        throw new IOException("Manual call to refresh counter returned status code: " + responseCode);
//                    }
//
//                    return null;
//                }
//            });
//        } catch(Exception e) {
//            failed = true;
//        }
//
//        assertTrue(!failed);
//    }
//
//    @Test
//    public void httpURLConnection_userInfoAfterLogoutReturnsSessionExpired() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//            CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(application), null));
//            int loginRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(loginAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                @Override
//                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                    con.setRequestMethod("POST");
//                    con.connect();
//                    return con.getResponseCode();
//                }
//            });
//            if ( loginRequestCode != 200 ) {
//                throw new Exception("Error making login request");
//            }
//
//            int logoutRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(logoutURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                @Override
//                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                    con.setRequestMethod("POST");
//                    con.connect();
//                    return con.getResponseCode();
//                }
//            });
//
//            if (logoutRequestCode != 200) {
//                throw new Exception("Error making logout request");
//            }
//
//            int userInfoRequestCode = SuperTokensHttpURLConnection.newRequest(new URL(userInfoAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
//                @Override
//                public Integer runOnConnection(HttpURLConnection con) throws IOException {
//                    return con.getResponseCode();
//                }
//            });
//
//            if (userInfoRequestCode != sessionExpiryCode) {
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
//    public void httpURLConnection_testThatCustomHeadersAreSent() {
//        boolean failed = false;
//        try {
//            resetAccessTokenValidity(10);
//            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
//            CookieManager.setDefault(new CookieManager(new SuperTokensPersistentCookieStore(application), null));
//            HeaderTestResponse headerTestResponse = SuperTokensHttpURLConnection.newRequest(new URL(testHeaderAPIURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<HeaderTestResponse>() {
//                @Override
//                public HeaderTestResponse runOnConnection(HttpURLConnection con) throws IOException {
//                    con.setRequestProperty("st-custom-header", "st");
//                    con.connect();
//
//                    if(con.getResponseCode() != 200) {
//                        throw new IOException("testHeader API failed");
//                    }
//
//                    BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream()));
//                    StringBuilder builder = new StringBuilder();
//                    String line = reader.readLine();
//                    while (line != null) {
//                        builder.append(line).append("\n");
//                        line = reader.readLine();
//                    }
//
//                    reader.close();
//
//                    HeaderTestResponse response = new Gson().fromJson(builder.toString(), HeaderTestResponse.class);
//                    return response;
//                }
//            });
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
