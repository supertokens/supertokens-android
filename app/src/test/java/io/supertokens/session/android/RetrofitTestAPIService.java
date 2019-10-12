package io.supertokens.session.android;

import io.supertokens.session.SuperTokensRetrofitTest;
import retrofit2.Call;
import retrofit2.http.*;

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
    Call<SuperTokensRetrofitTest.GetRefreshCounterResponse> refreshCounter();

    @GET("testHeader")
    Call<SuperTokensRetrofitTest.HeaderTestResponse> testHeader(@Header("st-custom-header") String header);
}
