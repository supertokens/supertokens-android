package com.supertokens.session;

import java.util.concurrent.TimeUnit;
import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;

/** Optional transport: OkHttp 3.14 introduced the public one-shot follow-up contract. */
final class OwnedOkHttpRefresh {
    private OwnedOkHttpRefresh() {}

    static OkHttpClient createClient(OkHttpClient base) {
        requireOneShotSupport();
        OkHttpClient.Builder builder = base.newBuilder()
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).cache(null)
                .callTimeout(RefreshCoordinator.NETWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .connectTimeout(RefreshCoordinator.NETWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(RefreshCoordinator.NETWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(RefreshCoordinator.NETWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        builder.interceptors().clear();
        builder.networkInterceptors().clear();
        return builder.build();
    }

    private static void requireOneShotSupport() {
        RequestBody probe = RequestBody.create(null, new byte[0]);
        try {
            probe.isOneShot();
        } catch (NoSuchMethodError unsupportedOneShot) {
            throw new IllegalArgumentException("SDK-owned refresh requires OkHttp 3.14 or newer "
                    + "with RequestBody.isOneShot() support. Use the no-argument "
                    + "SuperTokensInterceptor for historical OkHttp runtimes.", unsupportedOneShot);
        }
    }

    static void setTimeout(Call call, long remainingMillis) {
        call.timeout().timeout(remainingMillis, TimeUnit.MILLISECONDS);
    }
}
