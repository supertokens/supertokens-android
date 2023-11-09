package com.supertokens.supertokensexample

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import com.supertokens.session.SuperTokens
import com.supertokens.supertokensexample.services.network.APIManager
import kotlin.concurrent.thread

class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val userId = findViewById<TextView>(R.id.tvUserId)
        userId.text = SuperTokens.getUserId(this)

        val sessionInfoTv = findViewById<TextView>(R.id.tvSessionInfo)

        val callApiButton = findViewById<AppCompatButton>(R.id.btCallApi)
        callApiButton.setOnClickListener {
            thread {
                val response = APIManager.getInstance().retrofitService.sessionInfo().execute()
                val body = response.body()

                if (body != null) {
                    runOnUiThread {
                        sessionInfoTv.text = body.string()
                    }
                }
            }
        }

        val signOutButton = findViewById<AppCompatButton>(R.id.btSignOut)
        signOutButton.setOnClickListener {
            thread {
                SuperTokens.signOut(baseContext)

                runOnUiThread {
                    val intent = Intent(baseContext, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                }
            }
        }
    }
}