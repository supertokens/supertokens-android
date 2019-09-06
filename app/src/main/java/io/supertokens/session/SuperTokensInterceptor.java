package io.supertokens.session;

import android.app.Application;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

import okhttp3.FormBody;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

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

        String requestUrl = chain.request().url().url().toString();
        if ( !SuperTokens.getApiDomain(requestUrl).equals(SuperTokens.apiDomain) ) {
            // The the api domain does not match we do not want to intercept. Return the response of the request.
            return chain.proceed(chain.request());
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
                List<String> setCookie = response.headers(applicationContext.getString(R.string.supertokensSetCookieHeaderKey));
                SuperTokensResponseCookieHandler.saveIdRefreshFromSetCookieOkhttp(applicationContext, setCookie);

                if ( response.code() == SuperTokens.sessionExpiryStatusCode ) {
                    Boolean retry = this.handleUnauthorised(applicationContext, preRequestIdRefreshToken, chain);
                    if ( !retry ) {
                        return response;
                    } else {
                        // closing existing response object because it will loop and create another
                        response.close();
                    }
                } else {
                    String antiCSRF = response.header(applicationContext.getString(R.string.supertokensAntiCSRFHeaderKey));
                    if ( antiCSRF != null ) {
                        AntiCSRF.setToken(applicationContext, IdRefreshToken.getToken(applicationContext), antiCSRF);
                    }
                    return response;
                }
            }
        } finally {
            if ( IdRefreshToken.getToken(applicationContext) == null ) {
                AntiCSRF.removeToken(applicationContext);
            }
        }
    }

    private Boolean handleUnauthorised(Application applicationContext, String preRequestIdRefreshToken, Chain chain) throws IOException {
        if ( preRequestIdRefreshToken == null ) {
            String idRefresh = IdRefreshToken.getToken(applicationContext);
            return idRefresh != null;
        }

        SuperTokensUtils.Unauthorised unauthorisedResponse = onUnauthorisedResponse(SuperTokens.refreshTokenEndpoint, preRequestIdRefreshToken, applicationContext, chain);

        if ( unauthorisedResponse.status == SuperTokensUtils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED ) {
            return false;
        } else if (unauthorisedResponse.status == SuperTokensUtils.Unauthorised.UnauthorisedStatus.API_ERROR) {
            throw unauthorisedResponse.error;
        }

        return true;
    }

    private SuperTokensUtils.Unauthorised onUnauthorisedResponse(String refreshTokenUrl, String preRequestIdRefreshToken, Application applicationContext, Chain chain) {
        // this is intentionally not put in a loop because the loop in other projects is because locking has a timeout
        synchronized (SuperTokensInterceptor.refreshTokenLock) {
            Response refreshResponse = null;
            try {
                String postLockIdRefreshToken = IdRefreshToken.getToken(applicationContext);
                if ( postLockIdRefreshToken == null ) {
                    return new SuperTokensUtils.Unauthorised(SuperTokensUtils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
                }

                if ( !postLockIdRefreshToken.equals(preRequestIdRefreshToken) ) {
                    return new SuperTokensUtils.Unauthorised(SuperTokensUtils.Unauthorised.UnauthorisedStatus.RETRY);
                }

                Request.Builder refreshRequestBuilder = new Request.Builder();
                refreshRequestBuilder.url(refreshTokenUrl);
                refreshRequestBuilder.method("POST", new FormBody.Builder().build());

                Request refreshRequest = refreshRequestBuilder.build();
                refreshResponse = chain.proceed(refreshRequest);

                List<String> setCookie = refreshResponse.headers(applicationContext.getString(R.string.supertokensSetCookieHeaderKey));
                SuperTokensResponseCookieHandler.saveIdRefreshFromSetCookieOkhttp(applicationContext, setCookie);

                if ( refreshResponse.code() != 200 ) {
                    String responseMessage = refreshResponse.message();
                    throw new IOException(responseMessage);
                }

                String idRefreshAfterResponse = IdRefreshToken.getToken(applicationContext);
                if ( idRefreshAfterResponse == null ) {
                    // removed by server
                    return new SuperTokensUtils.Unauthorised(SuperTokensUtils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
                }

                String antiCSRF = refreshResponse.header(applicationContext.getString(R.string.supertokensAntiCSRFHeaderKey));
                if ( antiCSRF != null ) {
                    AntiCSRF.setToken(applicationContext, IdRefreshToken.getToken(applicationContext), antiCSRF);
                }

                return new SuperTokensUtils.Unauthorised(SuperTokensUtils.Unauthorised.UnauthorisedStatus.RETRY);

            } catch (Exception e) {
                IOException ioe = new IOException(e);
                if (e instanceof IOException) {
                    ioe = (IOException) e;
                }
                String idRefreshToken = IdRefreshToken.getToken(applicationContext);
                if ( idRefreshToken == null ) {
                    return new SuperTokensUtils.Unauthorised(SuperTokensUtils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
                }

                return new SuperTokensUtils.Unauthorised(SuperTokensUtils.Unauthorised.UnauthorisedStatus.API_ERROR, ioe);

            } finally {
                if ( refreshResponse != null ) {
                    refreshResponse.close();
                }
            }
        }
    }
}
