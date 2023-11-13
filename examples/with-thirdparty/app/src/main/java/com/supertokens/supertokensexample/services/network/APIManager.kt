package com.supertokens.supertokensexample.services.network

import com.supertokens.session.SuperTokensInterceptor
import com.supertokens.supertokensexample.resources.Constants
import retrofit2.Retrofit

class APIManager private constructor(){
    companion object {
        @Volatile
        private var instance: APIManager? = null

        fun getInstance(): APIManager {
            instance ?: synchronized(this) {
                instance ?: APIManager().also { instance = it }
            }

            instance!!.initialiseService()
            return instance!!
        }

    }

    lateinit var retrofitService: RetrofitService

    private fun initialiseService() {
        val clientBuilder = okhttp3.OkHttpClient.Builder()
        clientBuilder.addInterceptor(SuperTokensInterceptor())

        val retrofit = Retrofit.Builder()
            .baseUrl(Constants.apiDomain)
            .client(clientBuilder.build())
            .build()

        retrofitService = retrofit.create(RetrofitService::class.java)
    }
}