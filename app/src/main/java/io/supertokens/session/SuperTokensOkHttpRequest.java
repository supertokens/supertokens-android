package io.supertokens.session;

import android.app.Application;
import io.supertokens.session.utils.AntiCSRF;
import io.supertokens.session.utils.IdRefreshToken;
import okhttp3.*;

import java.io.IOException;
import java.util.List;

@SuppressWarnings("unused")
class SuperTokensOkHttpRequest {
    private static final Object refreshTokenLock = new Object();

    public static Response newCall(Request originalRequest, OkHttpClient client) throws IllegalAccessException, IOException {
        if ( !SuperTokens.isInitCalled ) {
            throw new IllegalAccessException("SuperTokens.init function needs to be called before using interceptors");
        }

        Application applicationContext = SuperTokens.contextWeakReference.get();
        if ( applicationContext == null ) {
            throw new IllegalAccessException("Application context is null");
        }

        try {
            while (true) {
                // Creating a new builder to modify headers while keeping everything else the same
                Request.Builder newBuilder = originalRequest.newBuilder();

                // Adding anticsrf token to request headers if present
                String preRequestIdRefreshToken = IdRefreshToken.getToken(applicationContext);
                String antiCSRFToken = AntiCSRF.getToken(applicationContext, preRequestIdRefreshToken);

                if ( antiCSRFToken != null ) {
                    newBuilder.header(applicationContext.getString(R.string.supertokensAntiCSRFHeaderKey), antiCSRFToken);
                }

                Request stRequest = newBuilder.build();
                Response stResponse =  client.newCall(stRequest).execute();

                // if response code is that of session expired try calling refresh token endpoint and retry
                if ( stResponse.code() == SuperTokens.sessionExpiryStatusCode ) {
                    stResponse.close();
                    Boolean retry = SuperTokensOkHttpRequest.handleUnauthorised(applicationContext, preRequestIdRefreshToken, client);
                    if ( !retry ) {
                        return stResponse;
                    }
                } else {
                    // If the response is success, save the response idRefresh and antiCSRF tokens
                    SuperTokensOkHttpRequest.saveAntiCSRFFromResponse(applicationContext, stResponse);
                    SuperTokensOkHttpRequest.saveIdRefreshFromSetCookie(applicationContext, stResponse);

                    return stResponse;
                }
            }
        } finally {
            // If idRefresh is absent, clear antiCSRF
            if ( IdRefreshToken.getToken(applicationContext) == null ) {
                AntiCSRF.removeToken(applicationContext);
            }
        }
    }

    private static Boolean handleUnauthorised(Application applicationContext, String preRequestIdRefreshToken, OkHttpClient client) throws IOException {
        if ( preRequestIdRefreshToken == null ) {
            String idRefresh = IdRefreshToken.getToken(applicationContext);
            return idRefresh != null;
        }

        synchronized (refreshTokenLock) {
            String postLockIdRefreshToken = IdRefreshToken.getToken(applicationContext);

            if ( postLockIdRefreshToken == null ) {
                return false;
            }

            if ( !postLockIdRefreshToken.equals(preRequestIdRefreshToken )) {
                return true;
            }

            Request.Builder refreshRequestBuilder = new Request.Builder();
            refreshRequestBuilder.url(SuperTokens.refreshTokenEndpoint);
            refreshRequestBuilder.method("POST", new FormBody.Builder().build());

            Request refreshRequest = refreshRequestBuilder.build();
            Response refreshResponse = client.newCall(refreshRequest).execute();

            if ( refreshResponse.code() != 200 ) {
                refreshResponse.close();
                throw new IOException(refreshResponse.message());
            }

            SuperTokensOkHttpRequest.saveIdRefreshFromSetCookie(applicationContext, refreshResponse);
            SuperTokensOkHttpRequest.saveAntiCSRFFromResponse(applicationContext, refreshResponse);

            if ( IdRefreshToken.getToken(applicationContext) == null ) {
                refreshResponse.close();
                return false;
            }
        }

        String idRefreshToken = IdRefreshToken.getToken(applicationContext);
        if ( idRefreshToken == null ) {
            return false;
        } else if (!idRefreshToken.equals(preRequestIdRefreshToken)) {
            return true;
        }

        return true;
    }

    private static void saveAntiCSRFFromResponse(Application applicationContext, Response response) {
        String antiCSRF = response.header(applicationContext.getString(R.string.supertokensAntiCSRFHeaderKey));
        if ( antiCSRF != null ) {
            AntiCSRF.setToken(applicationContext, IdRefreshToken.getToken(applicationContext), antiCSRF);
        }
    }

    private static void saveIdRefreshFromSetCookie(Application applicationContext, Response response) {
        List<String> setCookie = response.headers(applicationContext.getString(R.string.supertokensSetCookieHeaderKey));
        IdRefreshToken.saveIdRefreshFromSetCookieOkhttp(applicationContext, setCookie);
    }
}
