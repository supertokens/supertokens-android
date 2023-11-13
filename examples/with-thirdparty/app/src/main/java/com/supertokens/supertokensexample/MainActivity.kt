package com.supertokens.supertokensexample

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.supertokens.session.SuperTokens

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // We check if a session exists and route the user accordingly
        if (SuperTokens.doesSessionExist(applicationContext)) {
            startActivity(Intent(baseContext, HomeActivity::class.java))
        } else {
            startActivity(Intent(baseContext, LoginActivity::class.java))
        }
    }
}