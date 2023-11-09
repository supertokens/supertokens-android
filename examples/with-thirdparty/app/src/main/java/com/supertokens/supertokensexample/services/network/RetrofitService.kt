package com.supertokens.supertokensexample.services.network

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface RetrofitService {
    @POST("/auth/signinup")
    fun signInUp(@Body body: RequestBody): Call<ResponseBody>

    @GET("/sessioninfo")
    fun sessionInfo(): Call<ResponseBody>
}