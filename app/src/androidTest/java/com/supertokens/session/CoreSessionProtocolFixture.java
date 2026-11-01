package com.supertokens.session;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Before;
import org.junit.After;
import org.junit.Assume;
import static org.junit.Assert.*;

abstract class CoreSessionProtocolFixture {
    protected Context context;
    protected MockWebServer server;
    protected OkHttpClient client;

    protected String backend() {
        return InstrumentationRegistry.getArguments().getString("coreProtocolUrl");
    }

    @Before public void prepareRealAndroidState() throws Exception {
        Assume.assumeNotNull(backend());
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SuperTokens.resetForTests();
        assertTrue(context.getSharedPreferences(Constants.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
                .edit().clear().commit());
        server = new MockWebServer();
        server.start();
        new SuperTokens.Builder(context, server.url("/").toString()).build();
        client = new OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(new SuperTokensInterceptor()).build();
    }

    protected int request(boolean unused, SessionRequestTag tag, String path) throws Exception {
        Request.Builder builder = new Request.Builder().url(server.url(path));
        if (tag != null) builder.tag(SessionRequestTag.class, tag);
        try (Response response = client.newCall(builder.build()).execute()) { return response.code(); }
    }

    @After public void releaseResources() throws Exception {
        if (client != null) {
            client.dispatcher().cancelAll();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
        }
        if (server != null) server.shutdown();
        SuperTokens.resetForTests();
    }
}
