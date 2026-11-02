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
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

@SuppressWarnings("unused")
public class SuperTokensInterceptor implements Interceptor {
    private final OkHttpClient refreshClient;

    public SuperTokensInterceptor() {
        refreshClient = null;
    }

    public SuperTokensInterceptor(OkHttpClient refreshClient) {
        if (refreshClient == null) throw new NullPointerException("refreshClient");
        this.refreshClient = OwnedOkHttpRefresh.createClient(refreshClient);
    }

    @NotNull
    @Override
    public Response intercept(@NotNull final Chain chain) throws IOException {
        if (!SuperTokens.isInitCalled) throw new IOException("SuperTokens.init function needs to be called before using interceptors");
        Context context = SuperTokens.contextWeakReference.get();
        if (context == null) throw new IOException("Context is null");
        Request original = chain.request();
        SessionRequestTag supplied = OkHttpSessionTags.get(original);
        SessionRequestTag logical = supplied == null ? SessionState.capture() : supplied;
        final SessionState.Configuration config = logical.configuration;
        String url = original.url().toString();
        if (!Utils.shouldDoInterceptionBasedOnUrl(url, config.values.apiDomain,
                config.values.sessionTokenBackendDomain) || url.equals(config.refreshTokenUrl)) {
            return chain.proceed(original);
        }
        if (supplied != null) SessionTransport.requireCurrent(logical);
        String explicit = original.header("Authorization");
        SessionTransport.AuthorizationPolicy authPolicy = SessionTransport.authorizationPolicy(explicit, logical.getSnapshot());
        boolean managed = original.headers("Authorization").size() <= 1
                && authPolicy != SessionTransport.AuthorizationPolicy.FOREIGN_BEARER;
        if (!managed && supplied != null) throw new IOException("Authorization does not match the captured session");
        SessionRequestTag wire = new SessionRequestTag(logical.getSnapshot(), config);
        int attempts = 0;
        Response previous = null;
        while (true) {
            if (!SessionState.isCurrent(logical.getOwner(), config)) {
                if (supplied == null && previous != null) return previous;
                if (previous != null) previous.close();
                if (supplied != null) SessionTransport.requireCurrent(logical);
            }
            Request.Builder builder = original.newBuilder().header("st-auth-mode", config.values.tokenTransferMethod);
            if (original.header("rid") == null) builder.header("rid", "anti-csrf");
            if (managed) {
                if (authPolicy == SessionTransport.AuthorizationPolicy.SDK_BEARER) {
                    builder.removeHeader("Authorization");
                    String auth = SessionTransport.authorization(wire.getSnapshot(), false);
                    if (auth != null) builder.header("Authorization", auth);
                }
                String anti = SessionTransport.antiCSRF(wire.getSnapshot());
                if (anti != null) builder.header(Constants.CSRF_HEADER_KEY, anti);
            }
            Request request = builder.build();
            try {
                SessionTransport.beforeDispatch();
            } catch (RuntimeException | Error error) {
                if (previous != null) previous.close();
                throw error;
            }
            // Dispatch linearizes here, before handing the request to downstream interceptors.
            if (!SessionState.isCurrent(logical.getOwner(), config)) {
                if (supplied == null && previous != null) return previous;
                if (previous != null) previous.close();
                if (supplied != null) throw new StaleSessionException();
                if (managed) {
                    Request.Builder stale = request.newBuilder().removeHeader(Constants.CSRF_HEADER_KEY);
                    if (authPolicy == SessionTransport.AuthorizationPolicy.SDK_BEARER) stale.removeHeader("Authorization");
                    request = stale.build();
                }
                return chain.proceed(request);
            }
            if (previous != null) previous.close();
            Response response = chain.proceed(request);
            try {
                if (!SessionTransport.isResponseInScope(request.url().toString(),
                        response.request().url().toString(), config)) {
                    if (supplied != null) throw new IOException("Response URL is outside captured session scope");
                    return response;
                }
                // Downstream auth replacement has no authority to mutate the managed session.
                boolean wireManaged = managed && response.request().headers("Authorization").size() <= 1
                        && SessionTransport.matches(
                        response.request().header("Authorization"), request.header("Authorization"));
                if (!wireManaged) {
                    if (supplied != null) throw new IOException("Authorization changed during dispatch");
                    return response;
                }
                boolean current = SessionTransport.apply(context, logical, wire,
                        SessionResponse.fromOkHttp(response), supplied != null);
                if (!current || response.code() != config.values.sessionExpiredStatusCode) return response;
                if (supplied == null && SessionTransport.isRecovering(wire.getSnapshot())) return response;
                if (!SessionState.isCurrent(logical.getOwner(), config)) {
                    if (supplied != null) throw new StaleSessionException();
                    return response;
                }
                if (attempts >= config.values.maxRetryAttemptsForSessionRefresh) {
                    throw new IOException(SessionTransport.retryLimitMessage(url,
                            config.values.maxRetryAttemptsForSessionRefresh));
                }
                if (refreshClient == null && response.body() != null) {
                    // The legacy chain cannot proceed again while its prior response is open.
                    okhttp3.ResponseBody saved = response.peekBody(Long.MAX_VALUE);
                    Response buffered = response.newBuilder().body(saved).build();
                    response.close();
                    response = buffered;
                }
                SessionResolution resolution = RefreshCoordinator.resolve(context, logical.getOwner(),
                        SessionTransport.access(wire.getSnapshot()) == null,
                        SessionTransport.access(wire.getSnapshot()), new RefreshCoordinator.Transport() {
                            @Override public SessionResponse execute(Context ctx,
                                    SessionState.Configuration configuration, SessionSnapshot captured)
                                    throws IOException {
                                if (configuration != config) throw new StaleSessionException();
                                return refresh(chain, configuration, captured);
                            }
                        }, wire.getSnapshot().getRefreshTokenRevision(), supplied == null);
                if (!resolution.isSuccess()) {
                    if (supplied == null && resolution.getStatus() == SessionResolution.Status.STALE_OWNER) return response;
                    if (resolution.getError() != null) throw resolution.getError();
                    return response;
                }
                wire = SessionTransport.nextWire(logical, supplied != null);
                if (wire == null) return response;
                attempts++;
            } catch (IOException | RuntimeException e) {
                response.close();
                throw e;
            }
            previous = response;
        }
    }

    private SessionResponse refresh(Chain chain, SessionState.Configuration configuration,
                                    SessionSnapshot captured) throws IOException {
        RefreshDeadline deadline = refreshClient == null ? null : new RefreshDeadline();
        SessionRequestTag tag = new SessionRequestTag(captured, configuration);
        SessionTransport.requireCurrent(tag);
        RequestBody body = new RequestBody() {
            @Override public MediaType contentType() { return MediaType.parse("application/x-www-form-urlencoded"); }
            @Override public long contentLength() { return 0; }
            @Override public boolean isOneShot() { return true; }
            @Override public void writeTo(BufferedSink sink) {}
        };
        Request.Builder builder = new Request.Builder().url(configuration.refreshTokenUrl).post(body)
                .header("rid", SuperTokens.rid).header("fdi-version", Utils.join(Version.supported_fdi, ","))
                .header("st-auth-mode", configuration.values.tokenTransferMethod);
        String auth = SessionTransport.authorization(captured, true);
        String anti = SessionTransport.antiCSRF(captured);
        if (auth != null) builder.header("Authorization", auth);
        if (anti != null) builder.header(Constants.CSRF_HEADER_KEY, anti);
        Map<String, String> headers = configuration.values.customHeaderMapper
                .getRequestHeaders(CustomHeaderProvider.RequestType.REFRESH);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) builder.header(entry.getKey(), entry.getValue());
        }
        Request request = builder.build();
        if (!SessionTransport.matches(request.header("Authorization"), auth)) {
            throw new RefreshCoordinator.ConfigurationException("Refresh callback changed authorization");
        }
        SessionTransport.requireCurrent(tag);
        final okhttp3.Call refreshCall = refreshClient == null ? null : refreshClient.newCall(request);
        java.util.concurrent.ScheduledFuture<?> timeout = null;
        if (refreshCall != null) {
            OwnedOkHttpRefresh.setTimeout(refreshCall, deadline.remainingMillis());
            timeout = deadline.cancelAfterDeadline(new Runnable() {
                @Override public void run() { refreshCall.cancel(); }
            });
        }
        try (Response response = refreshClient == null ? chain.proceed(request) : refreshCall.execute()) {
            if (deadline != null) deadline.remainingMillis();
            if (!SessionTransport.isResponseInScope(request.url().toString(),
                    response.request().url().toString(), configuration)) {
                throw new RefreshCoordinator.ConfigurationException("Refresh response URL is outside captured session scope");
            }
            if (response.request().headers("Authorization").size() > 1
                    || !SessionTransport.matches(response.request().header("Authorization"), auth)) {
                throw new RefreshCoordinator.ConfigurationException("Refresh authorization changed during dispatch");
            }
            return SessionResponse.fromOkHttp(response);
        } finally {
            if (timeout != null) RefreshDeadline.finished(timeout);
        }
    }
}
