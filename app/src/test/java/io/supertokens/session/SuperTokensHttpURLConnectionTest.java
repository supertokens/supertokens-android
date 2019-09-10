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
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

@SuppressWarnings("CatchMayIgnoreException")
@RunWith(MockitoJUnitRunner.class)
public class SuperTokensHttpURLConnectionTest {
    private final String testBaseURL = "http://192.168.29.145:8080/api/";
    private final String refreshTokenEndpoint = testBaseURL + "refreshtoken";
    private final int sessionExpiryCode = 440;
    private final String testAPiURL = testBaseURL + "testing";
    private final String loginAPIURL = testBaseURL + "testLogin";
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
        boolean failed = true;
        try {
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
        Mockito.when(application.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(mockSharedPrefs);
        Mockito.when(application.getString(R.string.supertokensIdRefreshKey)).thenReturn(testIdRefreskPrefsKey);
        Mockito.when(application.getString(R.string.supertokensSetCookieHeaderKey)).thenReturn("Set-Cookie");
        Mockito.when(mockSharedPrefs.getString(testIdRefreskPrefsKey, null)).thenReturn("123");
        Mockito.when(mockSharedPrefs.edit()).thenReturn(mockSharedPrefsEditor);
        try {
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
        Mockito.when(application.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(mockSharedPrefs);
        Mockito.when(application.getString(R.string.supertokensIdRefreshKey)).thenReturn(testIdRefreskPrefsKey);
        Mockito.when(application.getString(R.string.supertokensSetCookieHeaderKey)).thenReturn("Set-Cookie");
        Mockito.when(mockSharedPrefs.getString(testIdRefreskPrefsKey, null)).thenReturn("123");
        Mockito.when(mockSharedPrefs.edit()).thenReturn(mockSharedPrefsEditor);
        try {
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

}
