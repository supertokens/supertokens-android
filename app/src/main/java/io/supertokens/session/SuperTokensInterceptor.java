package io.supertokens.session;

import android.app.Application;
import android.util.Log;
import io.supertokens.session.utils.AntiCSRF;
import io.supertokens.session.utils.IdRefreshToken;
import okhttp3.FormBody;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.util.List;

@SuppressWarnings("unused")
public class SuperTokensInterceptor implements Interceptor {
    private static final Object refreshTokenLock = new Object();

    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {
        if ( !SuperTokens.isInitCalled ) {
            throw new IOException("SuperTokens.init function needs to be called before using interceptors");
        }

        Application applicationContext = SuperTokens.contextWeakReference.get();
        if ( applicationContext == null ) {
            throw new IOException("Application context is null");
        }

//        CookieManager manager = (CookieManager) CookieManager.getDefault();
//        if ( manager == null ) {
//            manager = new CookieManager((CookieStore)SuperTokens.persistentCookieStore, null);
//            CookieManager.setDefault(manager);
//        }

        try {
            while (true) {
                Request.Builder requestBuilder = chain.request().newBuilder();

                String preRequestIdRefreshToken = IdRefreshToken.getToken(applicationContext);
                String antiCSRFToken = AntiCSRF.getToken(applicationContext, preRequestIdRefreshToken);

                if ( antiCSRFToken != null ) {
                    requestBuilder.header(applicationContext.getString(R.string.supertokensAntiCSRFHeaderKey), antiCSRFToken);
                }

                Request request = requestBuilder.build();
                Response response =  chain.proceed(request);

                if ( response.code() == SuperTokens.sessionExpiryStatusCode ) {
                    response.close();
                    Boolean retry = SuperTokensInterceptor.handleUnauthorised(applicationContext, preRequestIdRefreshToken, chain);
                    if ( !retry ) {
                        return response;
                    }
                } else {
                    SuperTokensInterceptor.saveAntiCSRFFromResponse(applicationContext, response);
                    SuperTokensInterceptor.saveIdRefreshFromSetCookie(applicationContext, response);

                    return response;
                }
            }
        } finally {
            if ( IdRefreshToken.getToken(applicationContext) == null ) {
                AntiCSRF.removeToken(applicationContext);
            }
        }
    }

    private static Boolean handleUnauthorised(Application applicationContext, String preRequestIdRefreshToken, Chain chain) throws IOException {
        if ( preRequestIdRefreshToken == null ) {
            String idRefresh = IdRefreshToken.getToken(applicationContext);
            return idRefresh != null;
        }

        synchronized (refreshTokenLock) {
            String postLockIdRefreshToken = IdRefreshToken.getToken(applicationContext);
//            String postLockAntiCSRF = AntiCSRF.getToken(applicationContext, postLockIdRefreshToken);

            if ( postLockIdRefreshToken == null ) {
                return false;
            }

            if ( !postLockIdRefreshToken.equals(preRequestIdRefreshToken )) {
                return true;
            }

            Request.Builder refreshRequestBuilder = new Request.Builder();
            refreshRequestBuilder.url(SuperTokens.refreshTokenEndpoint);
            refreshRequestBuilder.method("POST", new FormBody.Builder().build());

            CookieManager cookieManager = (CookieManager)CookieManager.getDefault();
            if ( cookieManager == null ) {
                cookieManager = new CookieManager();
                CookieManager.setDefault(cookieManager);
            }

            Request refreshRequest = refreshRequestBuilder.build();
            Response refreshResponse = chain.proceed(refreshRequest);

            if ( refreshResponse.code() != 200 ) {
                refreshResponse.close();
                throw new IOException(refreshResponse.message());
            }

            SuperTokensInterceptor.saveIdRefreshFromSetCookie(applicationContext, refreshResponse);
            SuperTokensInterceptor.saveAntiCSRFFromResponse(applicationContext, refreshResponse);

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
        if ( setCookie.size() > 0 ) {
            for(int i = 0; i < setCookie.size(); i++) {
                HttpCookie currentCookie = HttpCookie.parse(setCookie.get(i)).get(0);
                if (currentCookie.getName().equals(applicationContext.getString(R.string.supertokensIdRefreshCookieKey))) {
                    if ( currentCookie.hasExpired() ) {
                        IdRefreshToken.removeToken(applicationContext);
                    } else {
                        IdRefreshToken.setToken(applicationContext, currentCookie.getValue());
                    }
                }
            }
        }
    }
}
