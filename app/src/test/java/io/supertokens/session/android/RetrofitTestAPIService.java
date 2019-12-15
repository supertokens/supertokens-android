package io.supertokens.session.android;

import io.supertokens.session.TestUtils;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.POST;

// TODO: Nemi, what is this for?
public interface RetrofitTestAPIService {
    @POST("login")
    Call<Void> login();

    @GET("userInfo")
    Call<Void> userInfo();

    @POST("logout")
    Call<Void> logout();

    @POST("testReset")
    @Headers({
        "Content-Type: application/json",
        "Accept: application/json"
    })
    Call<Void> reset(@Header("atValidity") int validity);

    @GET("testRefreshCounter")
    Call<TestUtils.GetRefreshCounterResponse> refreshCounter();

    @GET("testHeader")
    Call<TestUtils.HeaderTestResponse> testHeader(@Header("st-custom-header") String header);
}
