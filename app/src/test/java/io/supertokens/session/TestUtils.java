package io.supertokens.session;

import com.google.gson.Gson;
import okhttp3.*;

import java.io.IOException;
import java.net.URL;

public class TestUtils {

    private static final String testBaseURL = "http://127.0.0.1:8080/";
    private static final String beforeEachAPIURL = testBaseURL + "beforeeach";
    private static final String afterAPIURL = testBaseURL + "after";
    private static final String startSTAPIURL = testBaseURL + "startst";
    private static final String stopAPIURL = testBaseURL + "stop";
    private static final String refreshCounterAPIURL = testBaseURL + "refreshCounter";

    public static void callBeforeEachAPI() {
        try {
            RequestBody reqbody = RequestBody.create(null, new byte[0]);
            OkHttpClient client = new OkHttpClient.Builder().build();
            Request request = new Request.Builder()
                    .url(new URL(beforeEachAPIURL))
                    .method("POST",reqbody)
                    .build();
            client.newCall(request).execute();
        } catch (Exception e) {
        }
    }

    public static void callAfterAPI() {
        try {
            RequestBody reqbody = RequestBody.create(null, new byte[0]);
            OkHttpClient client = new OkHttpClient.Builder().build();
            Request request = new Request.Builder()
                    .url(new URL(afterAPIURL))
                    .method("POST",reqbody)
                    .build();
            client.newCall(request).execute();
        } catch (Exception e) {

        }
    }

    public static void stopAPI() {
        try {
            OkHttpClient client = new OkHttpClient.Builder().build();
            Request request = new Request.Builder()
                    .url(new URL(stopAPIURL))
                    .build();
            client.newCall(request).execute();
        } catch (Exception e) {

        }
    }

    public static void startST() {
        startST(10);
    }

    public static void startST(long validity) {
        try {
            final MediaType JSON
                    = MediaType.parse("application/json; charset=utf-8");
            RequestBody body = RequestBody.create(JSON, "{\"accessTokenValidity\": " + validity + "}");
            OkHttpClient client = new OkHttpClient.Builder().build();
            Request request = new Request.Builder()
                    .url(new URL(startSTAPIURL))
                    .post(body)
                    .build();
            client.newCall(request).execute();
        } catch (Exception e) {
        }
    }

    public static int getRefreshTokenCounter() throws IOException {
        OkHttpClient refreshTokenCounterClient = new OkHttpClient.Builder().build();
        Request request = new Request.Builder()
                .url(new URL(refreshCounterAPIURL))
                .build();
        Response response = refreshTokenCounterClient.newCall(request).execute();
        if ( response.code() != 200 ) {
            throw new IOException("Could not connect to getRefreshCounter API");
        }

        if ( response.body() == null ) {
            throw new IOException("getRefreshCounter responded with an invalid format");
        }

        String body = response.body().string();
        GetRefreshCounterResponse counterResponse = new Gson().fromJson(body, GetRefreshCounterResponse.class);
        response.close();
        return counterResponse.counter;
    }

    private class GetRefreshCounterResponse {
        int counter;
    }

}
