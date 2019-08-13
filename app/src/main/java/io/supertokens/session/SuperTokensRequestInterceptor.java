package io.supertokens.session;

import android.app.Application;
import android.util.Log;
import io.supertokens.session.utils.AntiCSRF;
import io.supertokens.session.utils.IdRefreshToken;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.util.List;

@SuppressWarnings("unused")
public class SuperTokensRequestInterceptor implements Interceptor {
    private static final Object refreshTokenLock = new Object();

    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {
        if ( !SuperTokens.isInitCalled ) {
            throw new IOException("SuperTokens.init function needs to be called before using interceptors");
        }

        Application applicationContext = SuperTokens.contextWeakReference.get();
        if ( applicationContext == null ) {
            Log.e(SuperTokens.TAG, "Application context is null");
            return chain.proceed(chain.request());
        }

        CookieManager manager = (CookieManager) CookieManager.getDefault();
        if ( manager == null ) {
            manager = new CookieManager();
            CookieManager.setDefault(manager);
        }

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
                    Boolean retry = SuperTokensRequestInterceptor.handleUnauthorised(applicationContext, preRequestIdRefreshToken, chain);
                    if ( !retry ) {
                        return response;
                    }
                } else {
                    SuperTokensRequestInterceptor.saveAntiCSRFFromResponse(applicationContext, response);
                    SuperTokensRequestInterceptor.saveIdRefreshFromSetCookie(applicationContext, manager, response);

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
            refreshRequestBuilder.method("POST", null);

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

            SuperTokensRequestInterceptor.saveIdRefreshFromSetCookie(applicationContext, cookieManager, refreshResponse);
            SuperTokensRequestInterceptor.saveAntiCSRFFromResponse(applicationContext, refreshResponse);

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

    private static void saveIdRefreshFromSetCookie(Application applicationContext, CookieManager manager, Response response) {
        List<String> setCookie = response.headers(applicationContext.getString(R.string.supertokensSetCookieHeaderKey));
        if ( setCookie.size() > 0 ) {
            for(int i = 0; i < setCookie.size(); i++) {
                HttpCookie currentCookie = HttpCookie.parse(setCookie.get(i)).get(0);
                manager.getCookieStore().add(null, currentCookie);
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
