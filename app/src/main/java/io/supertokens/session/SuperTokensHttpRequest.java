package io.supertokens.session;

import android.content.Context;
import android.util.Log;
import io.supertokens.session.utils.AntiCSRF;
import io.supertokens.session.utils.IdRefreshToken;

import java.io.IOException;
import java.net.*;
import java.util.List;

@SuppressWarnings("unused")
public class SuperTokensHttpRequest {
    private static final Object refreshTokenLock = new Object();

    public static <T> T newRequest(Context context, URL url, SuperTokensHttpCallback<T> callback) throws IllegalAccessException, IOException {
        if ( !SuperTokens.isInitCalled ) {
            throw new IllegalAccessException("SuperTokens.init function needs to be called before using newRequest");
        }
        try {
            while (true) {
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                // Add antiCSRF token, if present in storage, to the request headers
                String preRequestIdRefreshToken = IdRefreshToken.getToken(context);
                String antiCSRFToken = AntiCSRF.getToken(context, preRequestIdRefreshToken);

                if ( antiCSRFToken != null ) {
                    connection.setRequestProperty("anti-csrf", antiCSRFToken);
                }

                // Get the default cookie manager that is used, if null set a new one
                CookieManager cookieManager = (CookieManager)CookieManager.getDefault();
                if ( cookieManager == null ) {
                    cookieManager = new CookieManager();
                    CookieManager.setDefault(cookieManager);
                }

                // This will execute all the steps the user wants to do with the connection and returns the output that the user has configured
                T output = callback.doBody(connection);
                int responseCode = connection.getResponseCode();
                if ( responseCode == SuperTokens.sessionExpiryStatusCode ) {
                    // Network call threw UnauthorisedAccess, try to call the refresh token endpoint and retry original call
                    boolean retry = SuperTokensHttpRequest.handleUnauthorised(context, preRequestIdRefreshToken);
                    if(!retry) {
                        return output;
                    }
                } else {
                    // Get the cookies from the response and store the idRefreshToken to storage
                    List<String> cookies = connection.getHeaderFields().get("Set-Cookie");
                    SuperTokensHttpRequest.saveIdRefreshTokenFromSetCookie(context, cookies, cookieManager);

                    // Store the anti-CSRF token from the response headers
                    SuperTokensHttpRequest.saveAntiCSRFFromConnection(context, connection);

                    return output;
                }
            }
        } finally {
            if ( IdRefreshToken.getToken(context) == null ) {
                AntiCSRF.removeToken(context);
            }
        }
    }

    private static boolean handleUnauthorised(Context context, String preRequestIdRefreshToken) throws IOException {
        if ( preRequestIdRefreshToken == null ) {
            String idRefreshToken = IdRefreshToken.getToken(context);
            return idRefreshToken != null;
        }

        synchronized (refreshTokenLock) {
            String postLockIdRefreshToken = IdRefreshToken.getToken(context);
            String postLockAntiCSRF = AntiCSRF.getToken(context, postLockIdRefreshToken);
            if ( postLockIdRefreshToken == null ) {
                return false;
            }

            if ( !postLockIdRefreshToken.equals(preRequestIdRefreshToken )) {
                return true;
            }

            URL refreshTokenUrl = new URL(SuperTokens.refreshTokenEndpoint);
            HttpURLConnection refreshTokenConnection = (HttpURLConnection) refreshTokenUrl.openConnection();
            refreshTokenConnection.setRequestMethod("POST");

            CookieManager cookieManager = (CookieManager)CookieManager.getDefault();
            if ( cookieManager == null ) {
                cookieManager = new CookieManager();
                CookieManager.setDefault(cookieManager);
            }

            // Ideally if there was an API error this would throw to the user directly, this condition is a safety
            Log.e(SuperTokens.TAG, refreshTokenConnection.getResponseCode() + "");
            if ( refreshTokenConnection.getResponseCode() != 200 ) {
                throw new IOException(refreshTokenConnection.getResponseMessage());
            }

            List<String> cookies = refreshTokenConnection.getHeaderFields().get("Set-Cookie");
            SuperTokensHttpRequest.saveIdRefreshTokenFromSetCookie(context, cookies, cookieManager);

            SuperTokensHttpRequest.saveAntiCSRFFromConnection(context, refreshTokenConnection);

            if (IdRefreshToken.getToken(context) == null) {
                return false;
            }
        }

        String idRefreshToken = IdRefreshToken.getToken(context);
        if ( idRefreshToken == null ) {
            return false;
        } else if (!idRefreshToken.equals(preRequestIdRefreshToken)) {
            return true;
        }

        return true;
    }

    private static void saveIdRefreshTokenFromSetCookie(Context context, List<String> setCookie, CookieManager cookieManager) {
        if ( setCookie != null ) {
            for(int i=0; i<setCookie.size(); i++) {
                HttpCookie currentCookie = HttpCookie.parse(setCookie.get(i)).get(0);
                cookieManager.getCookieStore().add(null, currentCookie);
                if (currentCookie.getName().equals(context.getString(R.string.supertokensIdRefreshCookieKey))) {
                    if ( currentCookie.hasExpired() ) {
                        IdRefreshToken.removeToken(context);
                    } else {
                        IdRefreshToken.setToken(context, currentCookie.getValue());
                    }
                }
            }
        }
    }

    private static void saveAntiCSRFFromConnection(Context context, HttpURLConnection connection) {
        String responseAntiCSRFToken = connection.getHeaderField("anti-csrf");
        if ( responseAntiCSRFToken != null ) {
            AntiCSRF.setToken(context, IdRefreshToken.getToken(context), responseAntiCSRFToken);
        }
    }

    public interface SuperTokensHttpCallback<T> {
        T doBody(HttpURLConnection con);
    }
}
