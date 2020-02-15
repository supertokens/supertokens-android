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

package io.supertokens.session.android;

import io.supertokens.session.TestUtils;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.OPTIONS;
import retrofit2.http.POST;
import retrofit2.http.Query;

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

    @GET("header")
    Call<ResponseBody> testHeader(@Header("st-custom-header") String header);

    @GET("checkDeviceInfo")
    Call<ResponseBody> checkDeviceInfo();

    @GET("testPing")
    Call<ResponseBody> testPing();

    @GET("testError")
    Call<ResponseBody> testError();

    @GET("checkCustomHeader")
    Call<ResponseBody> checkCustomHeaders();

    @GET("multipleInterceptors")
    Call<ResponseBody> multipleInterceptors();

    @GET("testConfig")
    Call<ResponseBody> testConfig(@Query("key") String key);

}
