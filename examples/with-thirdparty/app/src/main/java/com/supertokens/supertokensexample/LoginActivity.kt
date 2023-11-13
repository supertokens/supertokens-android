package com.supertokens.supertokensexample

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.SignInButton
import com.supertokens.supertokensexample.services.network.APIManager
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response


class LoginActivity : AppCompatActivity() {
    private lateinit var googleResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var githubResultLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val googleButton = findViewById<SignInButton>(R.id.btGoogle)
        val githubButton = findViewById<AppCompatButton>(R.id.btGithub)

        googleButton.setOnClickListener {
            signInWithGoogle()
        }

        githubButton.setOnClickListener {
            signInWithGitHub()
        }

        googleResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onGoogleResultReceived(it)
        }

        githubResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onGithubResultReceived(it)
        }
    }

    private fun signInWithGoogle() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken("GOOGLE_WEB_CLIENT_ID")
            .requestEmail()
            .build()

        val googleClient = GoogleSignIn.getClient(this, gso)
        val signInIntent = googleClient.signInIntent

        googleResultLauncher.launch(signInIntent)
    }

    private fun signInWithGitHub() {
        val serviceConfig = AuthorizationServiceConfiguration(Uri.parse("https://github.com/login/oauth/authorize"), Uri.parse("https://github.com/login/oauth/access_token"))
        val builder = AuthorizationRequest.Builder(
            serviceConfig,
            "GITHUB_CLIENT_ID",
            ResponseTypeValues.ID_TOKEN,
            Uri.parse("com.supertokens.supertokensexample://oauthredirect"),
        )
        val request = builder
            .setScope("user")
            .build()

        val service = AuthorizationService(this)
        val intent = service.getAuthorizationRequestIntent(request)

        githubResultLauncher.launch(intent)
    }

    private fun onGithubResultReceived(it: ActivityResult) {
        val data = it.data

        if (data == null) {
            Toast.makeText(baseContext, "Github sign in failed", Toast.LENGTH_SHORT).show()
            return
        }

        val response = AuthorizationResponse.fromIntent(data)
        val exception = AuthorizationException.fromIntent(data)

        if (exception != null) {
            exception.message?.let { msg -> Log.e("Login Activity", msg) }
            Toast.makeText(baseContext, "Github sign in failed", Toast.LENGTH_SHORT).show()
            return
        }

        if (response == null) {
            Toast.makeText(baseContext, "Github sign in failed", Toast.LENGTH_SHORT).show()
            return
        }

        val code = response.authorizationCode
        val idToken = response.idToken
        val state = response.state

        val body = JSONObject()
        body.put("thirdPartyId", "github")

        if (code != null && state != null) {
            val redirectURIInfo = JSONObject()
            redirectURIInfo.put("redirectURIOnProviderDashboard", "com.supertokens.supertokensexample://oauthredirect")

            val redirectURIQueryParams = JSONObject()
            redirectURIQueryParams.put("code", code)
            redirectURIQueryParams.put("state", state)

            redirectURIInfo.put("redirectURIQueryParams", redirectURIQueryParams)
            body.put("redirectURIInfo", redirectURIInfo)
        }

        val requestBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body.toString())
        APIManager.getInstance().retrofitService.signInUp(requestBody).enqueue(object: Callback<ResponseBody> {
            override fun onResponse(
                call: Call<ResponseBody>,
                response: Response<ResponseBody>
            ) {
                if (response.isSuccessful) {
                    val intent = Intent(baseContext, MainActivity::class.java)
                    intent.flags = FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                t.message?.let { msg -> Log.e("Login Activity", msg) }
                Toast.makeText(baseContext, "Github sign in failed", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun onGoogleResultReceived(it: ActivityResult) {
        val task = GoogleSignIn.getSignedInAccountFromIntent(it.data)

        try {
            val account = task.result
            val idToken = account.idToken

            val body = JSONObject()
            body.put("thirdPartyId", "google")

            val oauthTokens = JSONObject()
            oauthTokens.put("id_token", idToken)
            body.put("oAuthTokens", oauthTokens)
            val requestBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body.toString())
            APIManager.getInstance().retrofitService.signInUp(requestBody).enqueue(object: Callback<ResponseBody> {
                override fun onResponse(
                    call: Call<ResponseBody>,
                    response: Response<ResponseBody>
                ) {
                    if (response.isSuccessful) {
                        val intent = Intent(baseContext, MainActivity::class.java)
                        intent.flags = FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                    }
                }

                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                    t.message?.let { msg -> Log.e("Login Activity", msg) }
                    Toast.makeText(baseContext, "Google sign in failed", Toast.LENGTH_SHORT).show()
                }
            })
        } catch (e: Exception) {
            e.message?.let { msg -> Log.e("Login Activity", msg) }
            Toast.makeText(this, "Google sign in failed", Toast.LENGTH_SHORT).show()
        }
    }
}