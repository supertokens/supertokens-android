package com.supertokens.supertokensexample

import android.app.Application
import com.supertokens.session.SuperTokens
import com.supertokens.supertokensexample.resources.Constants

class MyApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        SuperTokens.Builder(applicationContext, Constants.apiDomain).build()
    }
}