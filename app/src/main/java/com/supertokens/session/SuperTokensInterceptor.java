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


    // Returns true authorization header in the provided request matches the current local access token.
    // This is used to decide whether the authorization header should be removed before making the request.
    private boolean shouldRemoveAuthHeader(Request request, SuperTokens.SessionSnapshot snapshot) {
        String originalHeader = request.header("Authorization");

        if (originalHeader == null) {
            originalHeader = request.header("authorization");
        }

        if (originalHeader != null) {
            String accessToken = snapshot.accessToken;
            String refreshToken = snapshot.refreshToken;

            if (accessToken != null && refreshToken != null && (originalHeader.equals("Bearer " + accessToken) || originalHeader.equals("bearer " + accessToken))) {
                return true;
            }
        }

        return false;
    }


    private static Request setAuthorizationHeaderIfRequired(Request request, Request.Builder builder, SuperTokens.SessionSnapshot snapshot, boolean addRefreshToken) {
        String authHeader = snapshot.authorization(addRefreshToken);
        boolean hasExistingAuthHeader = request.header("Authorization") != null || request.header("authorization") != null;

        if (authHeader != null && !hasExistingAuthHeader) {
            builder.header("Authorization", authHeader);
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

        boolean wasAuthHeaderRemovedInitially = false;
        int sessionRefreshAttempts = 0;
        while (true) {
            Request.Builder requestBuilder = chain.request().newBuilder();
            Utils.LocalSessionState preRequestLocalSessionState;
            Response response;
            SessionStore.Change change = null;
            refreshAPILock.readLock().lock();
            SessionStore.enterRefreshLock();
            try {
                preRequestLocalSessionState = Utils.getLocalSessionState(applicationContext);
                String antiCSRFToken = preRequestLocalSessionState.snapshot.antiCsrfToken;

                if (antiCSRFToken != null) {
                    requestBuilder.header(Constants.CSRF_HEADER_KEY, antiCSRFToken);
                }

                requestBuilder.header("st-auth-mode", SuperTokens.config.tokenTransferMethod);

                Request request = requestBuilder.build();

                if (request.header("rid") == null) {
                    request = request.newBuilder().header("rid", "anti-csrf").build();
                }

                // Check if the Authorization header should be removed
                // This is necessary to ensure that if the auth header was removed initially,
                // it remains removed in subsequent retries even if the token has changed.
                if (wasAuthHeaderRemovedInitially || shouldRemoveAuthHeader(request, preRequestLocalSessionState.snapshot)) {
                    Request.Builder builder = request.newBuilder();
                    builder.removeHeader("Authorization");
                    builder.removeHeader("authorization");
                    request = builder.build();

                    wasAuthHeaderRemovedInitially = true;
                }

                request = setAuthorizationHeaderIfRequired(request, request.newBuilder(), preRequestLocalSessionState.snapshot, false);

                response = makeRequest(chain, request);
                change = SessionStore.response(applicationContext, preRequestLocalSessionState.snapshot.generation, response, false);
            } finally {
                refreshAPILock.readLock().unlock();
                SessionStore.leaveRefreshLock();
            }
            if (change != null) change.dispatch();

            if (response.code() == SuperTokens.config.sessionExpiredStatusCode) {
                /**
                 * An API may return a 401 error response even with a valid session, causing a session refresh loop in the interceptor.
                 * To prevent this infinite loop, we break out of the loop after retrying the original request a specified number of times.
                 * The maximum number of retry attempts is defined by maxRetryAttemptsForSessionRefresh config variable.
                 */
                if (sessionRefreshAttempts >= SuperTokens.config.maxRetryAttemptsForSessionRefresh) {
                    String errorMsg = "Received a 401 response from " + requestUrl + ". Attempted to refresh the session and retry the request with the updated session tokens " + SuperTokens.config.maxRetryAttemptsForSessionRefresh + " times, but each attempt resulted in a 401 error. The maximum session refresh limit has been reached. Please investigate your API. To increase the session refresh attempts, update maxRetryAttemptsForSessionRefresh in the config.";
                    System.err.println(errorMsg);
                    throw new IOException(errorMsg);
                }

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

                sessionRefreshAttempts++;

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
    }

    private static Utils.Unauthorised onUnauthorisedResponse(Utils.LocalSessionState preRequestLocalSessionState, Context applicationContext, Chain chain) {
        // this is intentionally not put in a loop because the loop in other projects is because locking has a timeout
        Response refreshResponse = null;
        SessionStore.Change change = null;
        try {
            refreshAPILock.writeLock().lock();
            SessionStore.enterRefreshLock();
            Utils.LocalSessionState postLockLocalSessionState = Utils.getLocalSessionState(applicationContext);
            if (postLockLocalSessionState.snapshot != preRequestLocalSessionState.snapshot) {
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.RETRY);
            }
            if (postLockLocalSessionState.status == Utils.LocalSessionStateStatus.NOT_EXISTS) {
                change = new SessionStore.Change(postLockLocalSessionState.snapshot, false);
                change.events.add(EventHandler.EventType.UNAUTHORISED);
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.SESSION_EXPIRED);
            }

            Request.Builder refreshRequestBuilder = new Request.Builder();
            refreshRequestBuilder.url(SuperTokens.refreshTokenUrl);
            refreshRequestBuilder.method("POST", new FormBody.Builder().build());
            
            Request refreshRequest = refreshRequestBuilder.build();

            if (preRequestLocalSessionState.status == Utils.LocalSessionStateStatus.EXISTS) {
                String antiCSRFToken = postLockLocalSessionState.snapshot.antiCsrfToken;

                if (antiCSRFToken != null) {
                    refreshRequestBuilder.header(Constants.CSRF_HEADER_KEY, antiCSRFToken);
                }
            }

            refreshRequestBuilder.header("rid", SuperTokens.rid);
            refreshRequestBuilder.header("fdi-version", Utils.join(Version.supported_fdi, ","));
            refreshRequestBuilder.header("st-auth-mode", SuperTokens.config.tokenTransferMethod);

            refreshRequestBuilder = setAuthorizationHeaderIfRequired(refreshRequest, refreshRequestBuilder, postLockLocalSessionState.snapshot, true).newBuilder();

            Map<String, String> customRefreshHeaders = SuperTokens.config.customHeaderMapper.getRequestHeaders(CustomHeaderProvider.RequestType.REFRESH);
            if (customRefreshHeaders != null) {
                for (Map.Entry<String, String> entry : customRefreshHeaders.entrySet()) {
                    refreshRequestBuilder.header(entry.getKey(), entry.getValue());
                }
            }

            refreshRequest = refreshRequestBuilder.build();
            refreshResponse = makeRequest(chain, refreshRequest);

            change = SessionStore.response(applicationContext, postLockLocalSessionState.snapshot.generation, refreshResponse, true);
            if (change == null) return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.RETRY);
            final int code = refreshResponse.code();

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

            return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.RETRY);

        } catch (Exception e) {
            if (change == null ? SessionStore.read(applicationContext).generation != preRequestLocalSessionState.snapshot.generation : !change.current()) {
                return new Utils.Unauthorised(Utils.Unauthorised.UnauthorisedStatus.RETRY);
            }
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
            SessionStore.leaveRefreshLock();
            if (refreshResponse != null) {
                refreshResponse.close();
            }
            if (change != null) change.dispatch();
        }
    }
}
