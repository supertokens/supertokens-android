package io.supertokens.session

import android.content.Context
import android.text.TextUtils
import android.util.Log
import io.supertokens.session.utils.AntiCSRF
import io.supertokens.session.utils.IdRefreshToken
import java.lang.Exception
import java.net.*

class SuperTokensAuthHTTPRequest private constructor(var refreshTokenEndpoint: String, var sessionExpiredStatusCode: Int?) {
    companion object {
        @JvmStatic
        private var isInitCalled = false

        @JvmStatic
        private var apiDomain: String? = null

        @JvmStatic
        private var sessionExpiryCode: Int? = null

        @Volatile private var instance: SuperTokensAuthHTTPRequest? = null

        @JvmStatic
        private val lock = Object()

        @JvmStatic
        private fun initialize(refreshTokenEndpoint: String, sessionExpiredStatusCode: Int?) = SuperTokensAuthHTTPRequest(refreshTokenEndpoint, sessionExpiredStatusCode)

        /**
         * If instance is not null returns instance
         * If instance is null, creates a new instance and returns that in a thread safe manner
         */
        @JvmStatic
        fun init(refreshTokenEndpoint: String, sessionExpiredStatusCode: Int?) {
            synchronized(lock) {
                if ( instance == null ) {
                    initialize(refreshTokenEndpoint, sessionExpiredStatusCode).also { instance = it }
                }
            }
//            instance ?: synchronized(lock) {
//            instance ?: initialize(refreshTokenEndpoint, sessionExpiredStatusCode).also { instance = it }
        }

        @JvmStatic
        fun sessionPossibleExists(context: Context): Boolean {
            val idRefreshToken = IdRefreshToken.getToken(context)
            return idRefreshToken != null
        }

        @JvmStatic
        fun getInstance() = instance ?: throw Exception("SuperTokensAuthHTTPRequest.init() not called")
    }

    private val TAG = "io.supertokens.session"

    init {
        sessionExpiryCode = sessionExpiredStatusCode ?: 440
        apiDomain = getAPIDomain()
        isInitCalled = true
    }

    private fun getAPIDomain(): String {
        if ( refreshTokenEndpoint.startsWith("http://") || refreshTokenEndpoint.startsWith("https://") ) {
            return refreshTokenEndpoint.split("/")
                .filterIndexed{ index, _ -> index <= 2 }
                .joinToString(separator = "/")
        } else {
            throw Exception("Refresh token endpoint must start with http or https")
        }
    }

    private fun handleUnauthorisedResponse(originalConnection: HttpURLConnection, preRequestIdRefreshToken: String?): HttpURLConnection? {
        if ( preRequestIdRefreshToken == null ) {
            return null
        }

        val refreshURL = URL(refreshTokenEndpoint)
        val refreshConnection = refreshURL.openConnection() as HttpURLConnection
        refreshConnection.requestMethod = "POST"
        return null
    }

    private fun duplicateConnection(connection: HttpURLConnection) {
        val url = connection.url
        val newConnection = url.openConnection() as HttpURLConnection
        newConnection.requestMethod = connection.requestMethod
        newConnection.instanceFollowRedirects = connection.instanceFollowRedirects
        newConnection.allowUserInteraction = connection.allowUserInteraction
        newConnection.connectTimeout = connection.connectTimeout
        newConnection.defaultUseCaches = connection.defaultUseCaches
        newConnection.doInput = connection.doInput
        newConnection.doOutput = connection.doOutput
        newConnection.ifModifiedSince = connection.ifModifiedSince
        newConnection.readTimeout = connection.readTimeout
        connection.requestProperties.forEach{
            val property = connection.getRequestProperty(it.key)
            newConnection.setRequestProperty(it.key, property)
        }
    }

    fun makeRequest(context: Context, connection: HttpURLConnection): HttpURLConnection? {
        try {
            // Get default cookie manager, if the user sets their own this will make sure we use it
            // Get idrefresh and anti-csrf tokens from storage
            val preRequestToken = IdRefreshToken.getToken(context)
            val antiCSRF = AntiCSRF.getToken(context, preRequestToken)
            // Add anti-csrf to the headers of the request
            if ( antiCSRF != null ) {
                connection.setRequestProperty("anti-csrf", antiCSRF)
            }

            val originalConnection = connection

            var cookieManager = CookieManager.getDefault()
            if ( cookieManager == null ) {
                cookieManager = CookieManager()
                CookieManager.setDefault(cookieManager)
            }
            cookieManager = cookieManager as CookieManager
            connection.readTimeout
            // establish connection
            connection.connect()
            connection.connect()
            if ( connection.responseCode == sessionExpiredStatusCode ) {
                val unauthResponse = handleUnauthorisedResponse(originalConnection, preRequestToken)
                if ( unauthResponse == null ) {
                    return connection
                }
                return null // TODO Make refresh api call, then original if it succeeds and return
            }

            val setCookie = connection.headerFields["Set-Cookie"]
            // This explicitly adds cookies to the manager, this is a safety operation
            if ( setCookie != null ) {
                setCookie.forEach {
                    val current = HttpCookie.parse(it)[0]
                    cookieManager.cookieStore.add(connection.url.toURI(), current)
                    // Save idRefresh token
                    if ( current.name == context.getString(R.string.supertokensIdRefreshCookieKey) ) {
                        if ( current.hasExpired() ) {
                            IdRefreshToken.removeToken(context)
                        } else {
                            IdRefreshToken.setToken(context, current.value)
                        }
                    }
                }
            }

            // Save the anti-csrf received from the response header
            val antiCSRFHeader = connection.headerFields["anti-csrf"]
            if ( antiCSRFHeader != null ) {
                AntiCSRF.setToken(context, IdRefreshToken.getToken(context), antiCSRFHeader[0])
            }
            return connection

        } finally {
            if ( IdRefreshToken.getToken(context) == null ) {
                AntiCSRF.removeToken(context)
                var supertoken = SuperTokensHttpURLConnection("")
                supertoken.readTimeout
            }
        }
    }

}