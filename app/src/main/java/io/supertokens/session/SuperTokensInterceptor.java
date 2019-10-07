package io.supertokens.session;

import android.app.Application;

import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@SuppressWarnings("unused")
public class SuperTokensInterceptor implements Interceptor {
    private static final Object refreshTokenLock = new Object();
    private static final ReentrantReadWriteLock refreshAPILock = new ReentrantReadWriteLock();

    private static Response makeRequest(Chain chain, Request request) throws IOException {
        return chain.proceed(request);
    }

    private static Response makeRequest(OkHttpClient client, Request request) throws IOException {
        return client.newCall(request).execute();
    }

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

        if ( requestUrl.equals(SuperTokens.refreshTokenEndpoint) ) {
            // We don't want to intercept calls to the refresh token endpoint. Return the response of the request.
            return chain.proceed(chain.request());
        }

        try {
            while (true) {
                Request.Builder requestBuilder = chain.request().newBuilder();
                String preRequestIdRefreshToken;
                Response response;
                refreshAPILock.readLock().lock();
                try {
                    preRequestIdRefreshToken = IdRefreshToken.getToken(applicationContext);
                    String antiCSRFToken = AntiCSRF.getToken(applicationContext, preRequestIdRefreshToken);

                    if ( antiCSRFToken != null ) {
                        requestBuilder.header(applicationContext.getString(R.string.supertokensAntiCSRFHeaderKey), antiCSRFToken);
                    }

                    Request request = requestBuilder.build();
                    response =  makeRequest(chain, request);
                    List<String> setCookie = response.headers(applicationContext.getString(R.string.supertokensSetCookieHeaderKey));
                    SuperTokensResponseCookieHandler.saveIdRefreshFromSetCookieOkhttp(applicationContext, setCookie);
                } finally {
                    refreshAPILock.readLock().unlock();
                }

                if ( response.code() == SuperTokens.sessionExpiryStatusCode ) {
                    response.close();
                    Boolean retry = handleUnauthorised(applicationContext, preRequestIdRefreshToken, chain, null);
                    if ( !retry ) {
                        return response;
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

    private static Boolean handleUnauthorised(Application applicationContext, String preRequestIdRefreshToken, Chain chain, @Nullable OkHttpClient client) throws IOException {
        if ( preRequestIdRefreshToken == null ) {
            String idRefresh = IdRefreshToken.getToken(applicationContext);
            return idRefresh != null;
        }

        SuperTokensUtils.Unauthorised unauthorisedResponse;
        if ( client != null ) {
            unauthorisedResponse = onUnauthorisedResponse(SuperTokens.refreshTokenEndpoint, preRequestIdRefreshToken, applicationContext, chain, client);
        } else {
            unauthorisedResponse = onUnauthorisedResponse(SuperTokens.refreshTokenEndpoint, preRequestIdRefreshToken, applicationContext, chain, null);
        }

        if ( unauthorisedResponse.status == SuperTokensUtils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED ) {
            return false;
        } else if (unauthorisedResponse.status == SuperTokensUtils.Unauthorised.UnauthorisedStatus.API_ERROR) {
            throw unauthorisedResponse.error;
        }

        return true;
    }

    private static SuperTokensUtils.Unauthorised onUnauthorisedResponse(String refreshTokenUrl, String preRequestIdRefreshToken, Application applicationContext, Chain chain, @Nullable OkHttpClient client) {
        // this is intentionally not put in a loop because the loop in other projects is because locking has a timeout
        Response refreshResponse = null;
        try {
            refreshAPILock.writeLock().lock();
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
            if ( client != null ) {
                refreshResponse = makeRequest(client, refreshRequest);
            } else {
                refreshResponse = makeRequest(chain, refreshRequest);
            }

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
            refreshAPILock.writeLock().unlock();
            if ( refreshResponse != null ) {
                refreshResponse.close();
            }
        }
    }

    public static boolean attemptRefreshingSession(OkHttpClient client) throws IOException, IllegalAccessException {
        if ( !SuperTokens.isInitCalled ) {
            throw new IllegalAccessException("SuperTokens.init function needs to be called before using attemptRefreshingSession");
        }

        Application applicationContext = SuperTokens.contextWeakReference.get();
        if ( applicationContext == null ) {
            throw new IllegalAccessException("Application context is null");
        }

        try {
            String preRequestIdRefreshToken = IdRefreshToken.getToken(applicationContext);
            return handleUnauthorised(applicationContext, preRequestIdRefreshToken, null , client);
        } finally {
            String idRefreshToken = IdRefreshToken.getToken(applicationContext);
            if ( idRefreshToken == null ) {
                AntiCSRF.removeToken(applicationContext);
            }
        }
    }
}
