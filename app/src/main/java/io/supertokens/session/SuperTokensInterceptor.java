/*
 * Copyright (c) 2020, VRAI Labs and/or its affiliates. All rights reserved.
 *
 * This software is licensed under the Apache License, Version 2.0 (the
 * "License") as published by the Apache Software Foundation.
 *
 * You may not use this file except in compliance with the License. You may
 * obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.supertokens.session;

import android.content.Context;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import okhttp3.FormBody;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

@SuppressWarnings("unused")
public class SuperTokensInterceptor implements Interceptor {
    private static final Object refreshTokenLock = new Object();
    private static final ReentrantReadWriteLock refreshAPILock = new ReentrantReadWriteLock();

    private static Response makeRequest(Chain chain, Request request) throws IOException {
        return chain.proceed(request);
    }

    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {
        if (!SuperTokens.isInitCalled) {
            throw new IOException("SuperTokens.init function needs to be called before using interceptors");
        }

        Context applicationContext = SuperTokens.contextWeakReference.get();
        if (applicationContext == null) {
            throw new IOException("Context is null");
        }

        String requestUrl = chain.request().url().url().toString();
        if (!SuperTokens.getApiDomain(requestUrl).equals(SuperTokens.apiDomain)) {
            // The the api domain does not match we do not want to intercept. Return the response of the request.
            return chain.proceed(chain.request());
        }

        if (requestUrl.equals(SuperTokens.refreshTokenEndpoint)) {
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

                    if (antiCSRFToken != null) {
                        requestBuilder.header(applicationContext.getString(R.string.supertokensAntiCSRFHeaderKey), antiCSRFToken);
                    }

                    // Add package information to headers
                    requestBuilder.header(applicationContext.getString(R.string.supertokensNameHeaderKey), Utils.PACKAGE_PLATFORM);
                    requestBuilder.header(applicationContext.getString(R.string.supertokensVersionHeaderKey), BuildConfig.VERSION_NAME);

                    Request request = requestBuilder.build();
                    response = makeRequest(chain, request);
                    String idRefreshToken = response.header(applicationContext.getString(R.string.supertokensIdRefreshHeaderKey));
                    if (idRefreshToken != null) {
                        IdRefreshToken.setToken(applicationContext, idRefreshToken);
                    }
                } finally {
                    refreshAPILock.readLock().unlock();
                }

                if (response.code() == SuperTokens.sessionExpiryStatusCode) {
                    // Cloning the response object, if retry is false then we return this
                    Response clonedResponse = new Response.Builder()
                            .body(response.peekBody(Long.MAX_VALUE))
                            .cacheResponse(response.cacheResponse())
                            .code(response.code())
                            .handshake(response.handshake())
                            .headers(response.headers())
                            .message(response.message())
                            .networkResponse(response.networkResponse())
                            .priorResponse(response.priorResponse())
                            .protocol(response.protocol())
                            .receivedResponseAtMillis(response.receivedResponseAtMillis())
                            .request(response.request())
                            .sentRequestAtMillis(response.sentRequestAtMillis())
                            .build();
                    response.close();
                    Boolean retry = handleUnauthorised(applicationContext, preRequestIdRefreshToken, chain);
                    if (!retry) {
                        return clonedResponse;
                    }
                } else {
                    String antiCSRF = response.header(applicationContext.getString(R.string.supertokensAntiCSRFHeaderKey));
                    if (antiCSRF != null) {
                        AntiCSRF.setToken(applicationContext, IdRefreshToken.getToken(applicationContext), antiCSRF);
                    }
                    return response;
                }
            }
        } finally {
            if (IdRefreshToken.getToken(applicationContext) == null) {
                AntiCSRF.removeToken(applicationContext);
            }
        }
    }

    private static Boolean handleUnauthorised(Context applicationContext, String preRequestIdRefreshToken, Chain chain) throws IOException {
        if (preRequestIdRefreshToken == null) {
            String idRefresh = IdRefreshToken.getToken(applicationContext);
            return idRefresh != null;
        }

        Utils.Unauthorised unauthorisedResponse = onUnauthorisedResponse(SuperTokens.refreshTokenEndpoint, preRequestIdRefreshToken, applicationContext, chain);

        if (unauthorisedResponse.status == Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED) {
            return false;
        } else if (unauthorisedResponse.status == Utils.Unauthorised.UnauthorisedStatus.API_ERROR) {
            throw unauthorisedResponse.error;
        }

        return true;
    }

    private static Utils.Unauthorised onUnauthorisedResponse(String refreshTokenUrl, String preRequestIdRefreshToken, Context applicationContext, Chain chain) {
        // this is intentionally not put in a loop because the loop in other projects is because locking has a timeout
        Response refreshResponse = null;
        try {
            refreshAPILock.writeLock().lock();
            String postLockIdRefreshToken = IdRefreshToken.getToken(applicationContext);
            if (postLockIdRefreshToken == null) {
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
            }

            if (!postLockIdRefreshToken.equals(preRequestIdRefreshToken)) {
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.RETRY);
            }

            Request.Builder refreshRequestBuilder = new Request.Builder();
            refreshRequestBuilder.url(refreshTokenUrl);
            refreshRequestBuilder.method("POST", new FormBody.Builder().build());

            String antiCSRFToken = AntiCSRF.getToken(applicationContext, preRequestIdRefreshToken);

            if (antiCSRFToken != null) {
                refreshRequestBuilder.header(applicationContext.getString(R.string.supertokensAntiCSRFHeaderKey), antiCSRFToken);
            }

            // Add package information to headers
            refreshRequestBuilder.header(applicationContext.getString(R.string.supertokensNameHeaderKey), Utils.PACKAGE_PLATFORM);
            refreshRequestBuilder.header(applicationContext.getString(R.string.supertokensVersionHeaderKey), BuildConfig.VERSION_NAME);
            for (Map.Entry<String, String> entry : SuperTokens.refreshAPICustomHeaders.entrySet()) {
                refreshRequestBuilder.header(entry.getKey(), entry.getValue());
            }

            Request refreshRequest = refreshRequestBuilder.build();
            refreshResponse = makeRequest(chain, refreshRequest);

            boolean removeIdRefreshToken = true;
            String idRefreshToken = refreshResponse.header(applicationContext.getString(R.string.supertokensIdRefreshHeaderKey));
            if (idRefreshToken != null) {
                IdRefreshToken.setToken(applicationContext, idRefreshToken);
                removeIdRefreshToken = false;
            }

            final int code = refreshResponse.code();
            if (code == SuperTokens.sessionExpiryStatusCode && removeIdRefreshToken) {
                IdRefreshToken.setToken(applicationContext, "remove");
            }

            if (code < 200 || code >= 300) {
                String responseMessage = refreshResponse.message();
                throw new IOException(responseMessage);
            }

            String idRefreshAfterResponse = IdRefreshToken.getToken(applicationContext);
            if (idRefreshAfterResponse == null) {
                // removed by server
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
            }

            String antiCSRF = refreshResponse.header(applicationContext.getString(R.string.supertokensAntiCSRFHeaderKey));
            if (antiCSRF != null) {
                AntiCSRF.setToken(applicationContext, IdRefreshToken.getToken(applicationContext), antiCSRF);
            }

            return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.RETRY);

        } catch (Exception e) {
            e.printStackTrace();
            IOException ioe = new IOException(e);
            if (e instanceof IOException) {
                ioe = (IOException) e;
            }
            String idRefreshToken = IdRefreshToken.getToken(applicationContext);
            if (idRefreshToken == null) {
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
            }

            return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.API_ERROR, ioe);

        } finally {
            refreshAPILock.writeLock().unlock();
            if (refreshResponse != null) {
                refreshResponse.close();
            }
        }
    }

//    public static boolean attemptRefreshingSession(OkHttpClient client) throws IOException, IllegalAccessException {
//        if ( !SuperTokens.isInitCalled ) {
//            throw new IllegalAccessException("SuperTokens.init function needs to be called before using attemptRefreshingSession");
//        }
//
//        Context applicationContext = SuperTokens.contextWeakReference.get();
//        if ( applicationContext == null ) {
//            throw new IllegalAccessException("Context is null");
//        }
//
//        try {
//            String preRequestIdRefreshToken = IdRefreshToken.getToken(applicationContext);
//            return handleUnauthorised(applicationContext, preRequestIdRefreshToken, null , client);
//        } finally {
//            String idRefreshToken = IdRefreshToken.getToken(applicationContext);
//            if ( idRefreshToken == null ) {
//                AntiCSRF.removeToken(applicationContext);
//            }
//        }
//    }
}
