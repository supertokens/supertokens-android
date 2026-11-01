package com.supertokens.upgrade;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import androidx.test.platform.app.InstrumentationRegistry;
import com.supertokens.session.SuperTokens;
import com.supertokens.session.SuperTokensInterceptor;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

/** Select individual methods; no Core fixture or modern OkHttp test dependency. */
public class OldRuntimeTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Before public void verifySdkIdentity() {
        String expected = InstrumentationRegistry.getArguments().getString("expectedSdkName");
        assertTrue("Explicit SDK identity required", "sdk054".equals(expected) || "candidate".equals(expected));
        assertEquals("Installed SDK flavor", expected, BuildConfig.SDK_NAME);
    }

    @Test public void legacyNoArgumentAndApplicationTag() throws Exception {
        exercise(false);
    }

    @Test public void candidateUntypedSessionTag() throws Exception {
        assertEquals("candidate", BuildConfig.SDK_NAME);
        exercise(true);
    }

    @Test public void candidateOwnedConstructorRejectsOldRuntime() throws Exception {
        assertEquals("candidate", BuildConfig.SDK_NAME);
        OkHttpClient client = new OkHttpClient();
        try {
            try {
                SuperTokensInterceptor.class.getConstructor(OkHttpClient.class).newInstance(client);
                fail("Owned refresh must reject OkHttp 3.3.0");
            } catch (InvocationTargetException expected) {
                assertTrue(expected.getCause() instanceof IllegalArgumentException);
                assertTrue(expected.getCause().getMessage().contains("requires OkHttp 3.14"));
            }
        } finally {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
        }
    }

    private void exercise(boolean tagged) throws Exception {
        final String preferencesName = "old-runtime-" + UUID.randomUUID();
        Context context = new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String ignored, int mode) {
                return super.getSharedPreferences(preferencesName, mode);
            }
        };
        Bundle args = InstrumentationRegistry.getArguments();
        String host = args.getString("runtimeHost", "127.0.0.1");
        assertTrue("Device loopback only", host.equals("127.0.0.1") || host.equals("localhost"));
        int port = Integer.parseInt(args.getString("runtimePort", "0"));
        final ServerSocket server = new ServerSocket(port, 1, InetAddress.getByName(host));
        server.setSoTimeout(8000);
        final String access = access();
        final String front = encode(new JSONObject().put("uid", "runtime-user").put("ate", 4102444800000L)
                .put("up", new JSONObject()).toString(), false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        OkHttpClient client = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS)
                .addInterceptor(new SuperTokensInterceptor()).build();
        Future<String> received = executor.submit(new Callable<String>() {
            @Override public String call() throws Exception {
                Socket socket = server.accept();
                try {
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF8));
                    String request = input.readLine();
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) { }
                    OutputStream output = socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 2\r\n"
                            + "st-access-token: " + access + "\r\nst-refresh-token: runtime-refresh\r\n"
                            + "front-token: " + front + "\r\n\r\nok").getBytes(UTF8));
                    output.flush();
                    return request;
                } finally { socket.close(); }
            }
        });
        try {
            String origin = "http://" + host + ":" + server.getLocalPort();
            new SuperTokens.Builder(context, origin).tokenTransferMethod("header").build();
            Object tag = tagged ? SuperTokens.class.getMethod("captureRequestTag", Context.class).invoke(null, context)
                    : new Object();
            Request request = new Request.Builder().url(origin + "/login").tag(tag).build();
            Response response = client.newCall(request).execute();
            try {
                assertEquals(200, response.code());
                assertEquals("ok", response.body().string());
                assertSame(tag, request.tag());
                assertSame(tag, response.request().tag());
            } finally { response.close(); }
            assertEquals("GET /login HTTP/1.1", received.get(10, TimeUnit.SECONDS));
            assertTrue(SuperTokens.doesSessionExist(context));
            assertEquals("runtime-user", SuperTokens.getUserId(context));
            assertEquals(access, SuperTokens.getAccessToken(context));
        } finally {
            server.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
            assertTrue(context.getSharedPreferences(preferencesName, 0).edit().clear().commit());
        }
    }

    private String access() throws Exception {
        JSONObject payload = new JSONObject().put("sessionHandle", "runtime-handle").put("userId", "runtime-user")
                .put("expiryTime", 4102444800000L).put("timeCreated", 1700000000000L)
                .put("userData", new JSONObject());
        return encode("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"version\":\"2\"}", true)
                + "." + encode(payload.toString(), true) + ".c2ln";
    }

    private String encode(String value, boolean url) {
        return Base64.encodeToString(value.getBytes(UTF8), url
                ? Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING : Base64.NO_WRAP);
    }
}
