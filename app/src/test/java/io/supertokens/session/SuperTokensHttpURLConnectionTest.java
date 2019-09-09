package io.supertokens.session;

import android.app.Application;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;

import static org.junit.Assert.assertTrue;

@SuppressWarnings("CatchMayIgnoreException")
@RunWith(MockitoJUnitRunner.class)
public class SuperTokensHttpURLConnectionTest {
    private final String testBaseURL = "http://192.168.29.145:8080/api/";
    private final String refreshTokenEndpoint = testBaseURL + "refreshtoken";
    private final int sessionExpiryCode = 440;
    private final String testAPiURL = testBaseURL + "testing";
    private final String loginAPIURL = testBaseURL + "login";
    private static SharedPreferences mockSharedPrefs;
    private static SharedPreferences.Editor mockSharedPrefsEditor;

    private static final String testIdRefreskPrefsKey = "st-test-id-refresh-prefs-key";

    @Mock
    Application application;

    @BeforeClass
    public static void beforeAll() {
        SuperTokens.isInitCalled = false;
        Mockito.mock(TextUtils.class);
        mockSharedPrefs = Mockito.mock(SharedPreferences.class);
        mockSharedPrefsEditor = Mockito.mock(SharedPreferences.Editor.class);
    }

    @Test
    public void httpURLConnection_requestFailsIfInitNotCalled() {
        boolean failed = false;
        try {
            SuperTokensHttpURLConnection.newRequest(new URL(testBaseURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
                @SuppressWarnings("RedundantThrows")
                @Override
                public Integer runOnConnection(HttpURLConnection con) throws IOException {
                    return 0;
                }
            });
            failed = true;
        } catch (Exception e) {}

        assertTrue(!failed);
    }

    @Test
    public void httpURLConnection_refreshFailsIfInitNotCalled() {
        boolean failed = false;
        try {
            SuperTokensHttpURLConnection.attemptRefreshingSession();
            failed = true;
        } catch (Exception e) {}

        assertTrue(!failed);
    }

    @Test
    public void httpURLConnection_requestFailsIfCookieManagerNotSet() {
        boolean failed = false;
        Mockito.when(application.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(mockSharedPrefs);
        Mockito.when(application.getString(R.string.supertokensIdRefreshKey)).thenReturn(testIdRefreskPrefsKey);
        Mockito.when(mockSharedPrefs.getString(testIdRefreskPrefsKey, null)).thenReturn("123");
        try {
            SuperTokens.init(application, refreshTokenEndpoint, sessionExpiryCode);
            SuperTokensHttpURLConnection.newRequest(new URL(testAPiURL), new SuperTokensHttpURLConnection.SuperTokensHttpURLConnectionCallback<Integer>() {
                @Override
                public Integer runOnConnection(HttpURLConnection con) throws IOException {
                    con.setRequestMethod("GET");
                    con.connect();
                    return con.getResponseCode();
                }
            });
            failed = true;
        } catch(Exception e) {}

        assertTrue(!failed);
    }

//    @Test
//    public void httpURLConnection_testAPIsWithoutParams() {
//        Mockito.when(application.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(mockSharedPrefs);
//        Mockito.when(application.getString(R.string.supertokensIdRefreshKey)).thenReturn(testIdRefreskPrefsKey);
//        Mockito.when(application.getString(R.string.supertokensSetCookieHeaderKey)).thenReturn("Set-Cookie");
//        Mockito.when(mockSharedPrefs.getString(testIdRefreskPrefsKey, null)).thenReturn("123");
//        Mockito.when(mockSharedPrefs.edit()).thenReturn(mockSharedPrefsEditor);
//        try {
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
//            int test = 0;
//        } catch(Exception e) {
//            Log.e("", e.getMessage());
//        }
//    }

}
