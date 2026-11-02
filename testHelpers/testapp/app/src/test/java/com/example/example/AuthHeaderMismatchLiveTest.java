package com.example.example;

import android.content.Context;
import android.content.SharedPreferences;
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensInterceptor;
import java.util.HashMap;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AuthHeaderMismatchLiveTest {
    private Context context;
    private OkHttpClient client;

    @BeforeClass public static void startFixture() { com.example.TestUtils.beforeAll(); }
    @AfterClass public static void stopFixture() { com.example.TestUtils.afterAll(); }

    @Before public void setup() throws Exception {
        com.example.TestUtils.beforeEach();
        com.example.TestUtils.startST();
        context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        Map<String, SharedPreferences> preferences = new HashMap<>();
        when(context.getSharedPreferences(anyString(), anyInt())).thenAnswer(call ->
                preferences.computeIfAbsent((String) call.getArguments()[0], key -> new SPMockBuilder().createSharedPreferences()));
        new SuperTokens.Builder(context, Constants.apiDomain).tokenTransferMethod("header").build();
        client = new OkHttpClient.Builder().addInterceptor(new SuperTokensInterceptor()).build();
    }

    @After public void cleanup() {
        SuperTokens.resetForTests();
        if (client != null) {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
        }
    }

    @Test public void foreignAuthorizationIsSentInsteadOfStoredToken() throws Exception {
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), "{\"userId\":\"header-mismatch\"}");
        try (Response login = client.newCall(new Request.Builder().url(Constants.apiDomain + "/login").post(body).build()).execute()) {
            assertEquals(200, login.code());
        }
        assertTrue(SuperTokens.doesSessionExist(context));
        String storedToken = SuperTokens.getAccessToken(context);
        assertNotNull(storedToken);
        Request foreign = new Request.Builder().url(Constants.apiDomain + "/base-custom-auth")
                .header("Authorization", "Bearer myOwnHeHe").build();
        try (Response response = client.newCall(foreign).execute()) {
            assertEquals(200, response.code());
        }
        assertEquals(storedToken, SuperTokens.getAccessToken(context));
    }
}
