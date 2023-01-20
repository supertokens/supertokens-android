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

package com.supertokens.session;

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

    private Request removeAuthHeaderIfMatchesLocalToken(Request request, Request.Builder builder, Context context) {
        String originalHeader = request.header("Authorization");

        if (originalHeader == null) {
            originalHeader = request.header("authorization");
        }

        if (originalHeader != null) {
            String accessToken = Utils.getTokenForHeaderAuth(Utils.TokenType.ACCESS, context);

            if (accessToken != null && originalHeader.equals("Bearer " + accessToken)) {
                builder.removeHeader("Authorization");
                builder.removeHeader("authorization");
            }
        }

        return builder.build();
    }

    private Request setAuthorizationHeaderIfRequired(Request.Builder builder, Context context) {
        Map<String, String> headersToSet = Utils.getAuthorizationHeaderIfRequired(context);
        for (Map.Entry<String, String> entry: headersToSet.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }

        return builder.build();
    }

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

        boolean doNotDoInterception = !Utils.shouldDoInterceptionBasedOnUrl(requestUrl, SuperTokens.config.apiDomain, SuperTokens.config.sessionTokenBackendDomain);

        if (doNotDoInterception) {
            return chain.proceed(chain.request());
        }

        if (requestUrl.equals(SuperTokens.refreshTokenUrl)) {
            /**
             * We don't want to intercept calls to the refresh token endpoint. Return the response of the request.
             *
             * Note: This check is required in the case of okhttp because requests made internally get passed back to the
             * interception chain
             */
            return chain.proceed(chain.request());
        }

        try {
            while (true) {
                Request.Builder requestBuilder = chain.request().newBuilder();
                Utils.LocalSessionState preRequestLocalSessionState;
                Response response;
                refreshAPILock.readLock().lock();
                try {
                    preRequestLocalSessionState = Utils.getLocalSessionState(applicationContext);
                    String antiCSRFToken = AntiCSRF.getToken(applicationContext, preRequestLocalSessionState.lastAccessTokenUpdate);

                    if (antiCSRFToken != null) {
                        requestBuilder.header(Constants.CSRF_HEADER_KEY, antiCSRFToken);
                    }

                    requestBuilder.header("st-auth-mode", SuperTokens.config.tokenTransferMethod);

                    Request request = requestBuilder.build();

                    if (request.header("rid") == null) {
                        request = request.newBuilder().header("rid", "anti-csrf").build();
                    }

                    request = removeAuthHeaderIfMatchesLocalToken(request, request.newBuilder(), applicationContext);
                    request = setAuthorizationHeaderIfRequired(request.newBuilder(), applicationContext);

                    response = makeRequest(chain, request);
                    Utils.saveTokenFromHeaders(response, applicationContext);
                    Utils.fireSessionUpdateEventsIfNecessary(
                            preRequestLocalSessionState.status == Utils.LocalSessionStateStatus.EXISTS,
                            response.code(),
                            response.header(Constants.FRONT_TOKEN_HEADER_KEY)
                    );
                } finally {
                    refreshAPILock.readLock().unlock();
                }

                if (response.code() == SuperTokens.config.sessionExpiredStatusCode) {
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

                    Utils.Unauthorised unauthorisedResponse = onUnauthorisedResponse(preRequestLocalSessionState, applicationContext, chain);
                    if (unauthorisedResponse.status != Utils.Unauthorised.UnauthorisedStatus.RETRY) {
                        if (unauthorisedResponse.error != null) {
                            throw unauthorisedResponse.error;
                        }

                        return clonedResponse;
                    }
                } else {
                    return response;
                }
            }
        } finally {
            if (Utils.getLocalSessionState(applicationContext).status == Utils.LocalSessionStateStatus.NOT_EXISTS) {
                AntiCSRF.removeToken(applicationContext);
                FrontToken.removeToken(applicationContext);
            }
        }
    }

    private static Utils.Unauthorised onUnauthorisedResponse(Utils.LocalSessionState preRequestLocalSessionState, Context applicationContext, Chain chain) {
        // this is intentionally not put in a loop because the loop in other projects is because locking has a timeout
        Response refreshResponse = null;
        try {
            refreshAPILock.writeLock().lock();
            Utils.LocalSessionState postLockLocalSessionState = Utils.getLocalSessionState(applicationContext);
            if (postLockLocalSessionState.status == Utils.LocalSessionStateStatus.NOT_EXISTS) {
                SuperTokens.config.eventHandler.handleEvent(EventHandler.EventType.UNAUTHORISED);
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
            }

            if ( postLockLocalSessionState.status != preRequestLocalSessionState.status ||
                    (postLockLocalSessionState.status == Utils.LocalSessionStateStatus.EXISTS &&
                            preRequestLocalSessionState.status == Utils.LocalSessionStateStatus.EXISTS &&
                            postLockLocalSessionState.lastAccessTokenUpdate != preRequestLocalSessionState.lastAccessTokenUpdate) ) {
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.RETRY);
            }

            Request.Builder refreshRequestBuilder = new Request.Builder();
            refreshRequestBuilder.url(SuperTokens.refreshTokenUrl);
            refreshRequestBuilder.method("POST", new FormBody.Builder().build());

            if (preRequestLocalSessionState.status == Utils.LocalSessionStateStatus.EXISTS) {
                String antiCSRFToken = AntiCSRF.getToken(applicationContext, preRequestLocalSessionState.lastAccessTokenUpdate);

                if (antiCSRFToken != null) {
                    refreshRequestBuilder.header(Constants.CSRF_HEADER_KEY, antiCSRFToken);
                }
            }

            refreshRequestBuilder.header("rid", SuperTokens.rid);
            refreshRequestBuilder.header("fdi-version", Utils.join(Version.supported_fdi, ","));
            refreshRequestBuilder.header("st-auth-mode", SuperTokens.config.tokenTransferMethod);

            Map<String, String> customRefreshHeaders = SuperTokens.config.customHeaderMapper.getRequestHeaders(CustomHeaderProvider.RequestType.REFRESH);
            if (customRefreshHeaders != null) {
                for (Map.Entry<String, String> entry : customRefreshHeaders.entrySet()) {
                    refreshRequestBuilder.header(entry.getKey(), entry.getValue());
                }
            }

            Request refreshRequest = refreshRequestBuilder.build();
            refreshResponse = makeRequest(chain, refreshRequest);

            Utils.saveTokenFromHeaders(refreshResponse, applicationContext);
            final int code = refreshResponse.code();

            boolean isUnauthorised = code == SuperTokens.config.sessionExpiredStatusCode;

            if (isUnauthorised && refreshResponse.header(Constants.FRONT_TOKEN_HEADER_KEY) != null) {
                FrontToken.setItem(applicationContext, "remove");
            }

            String frontTokenInHeaders = refreshResponse.header(Constants.FRONT_TOKEN_HEADER_KEY);

            Utils.fireSessionUpdateEventsIfNecessary(
                    preRequestLocalSessionState.status == Utils.LocalSessionStateStatus.EXISTS,
                    code,
                    frontTokenInHeaders == null ? "remove" : frontTokenInHeaders
            );

            if (code < 200 || code >= 300) {
                String responseMessage = refreshResponse.message();
                throw new IOException(responseMessage);
            }

            if (Utils.getLocalSessionState(applicationContext).status == Utils.LocalSessionStateStatus.NOT_EXISTS) {
                // The execution should never come here.. but just in case.
                // removed by server. So we logout
                // we do not send "UNAUTHORISED" event here because
                // this is a result of the refresh API returning a session expiry, which
                // means that the frontend did not know for sure that the session existed
                // in the first place.
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
            }

            SuperTokens.config.eventHandler.handleEvent(EventHandler.EventType.REFRESH_SESSION);
            return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.RETRY);

        } catch (Exception e) {
            e.printStackTrace();
            IOException ioe = new IOException(e);
            if (e instanceof IOException) {
                ioe = (IOException) e;
            }
            if (Utils.getLocalSessionState(applicationContext).status == Utils.LocalSessionStateStatus.NOT_EXISTS) {
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
            }

            return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.API_ERROR, ioe);

        } finally {
            refreshAPILock.writeLock().unlock();
            if (refreshResponse != null) {
                refreshResponse.close();
            }

            if (Utils.getLocalSessionState(applicationContext).status == Utils.LocalSessionStateStatus.NOT_EXISTS) {
                AntiCSRF.removeToken(applicationContext);
                FrontToken.removeToken(applicationContext);
            }
        }
    }
}
