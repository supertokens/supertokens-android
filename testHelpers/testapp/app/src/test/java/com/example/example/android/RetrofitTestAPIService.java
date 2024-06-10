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

package com.supertokens.session.android;

import com.example.TestUtils;
import com.google.gson.JsonObject;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.*;

public interface RetrofitTestAPIService {
    @POST("/login")
    @Headers({
            "Content-Type: application/json",
            "Accept: application/json"
    })
    Call<Void> login(@Body JsonObject body);

    @GET("/base-custom-auth")
    @Headers({
            "Content-Type: application/json",
            "Accept: application/json"
    })
    Call<Void> baseCustomAuth(@Header("authorization") String token);

    @POST("/login-2.18")
    @Headers({
            "Content-Type: application/json",
            "Accept: application/json"
    })
    Call<Void> login218(@Body JsonObject body);

    @GET("/")
    Call<ResponseBody> userInfo();

    @GET("/")
    Call<ResponseBody> userInfo(@Header("authorization") String token);

    @GET("/")
    Call<ResponseBody> userInfoCaps(@Header("Authorization") String token);

    @POST("/logout")
    Call<Void> logout();

    @POST("/logout-alt")
    Call<Void> logoutAlt();

    @POST("/testReset")
    @Headers({
        "Content-Type: application/json",
        "Accept: application/json"
    })
    Call<Void> reset(@Header("atValidity") int validity);

    @GET("/testRefreshCounter")
    Call<TestUtils.GetRefreshCounterResponse> refreshCounter();

    @GET("/header")
    Call<ResponseBody> testHeader(@Header("st-custom-header") String header);

    @GET("/checkDeviceInfo")
    Call<ResponseBody> checkDeviceInfo();

    @GET("/ping")
    Call<ResponseBody> testPing();

    @GET("/testError")
    Call<ResponseBody> testError();

    @GET("/refreshHeader")
    Call<ResponseBody> checkCustomHeaders();

    @POST("/multipleInterceptors")
    Call<ResponseBody> multipleInterceptors();

    @GET("/throw-401")
    Call<ResponseBody> throw401();
}
