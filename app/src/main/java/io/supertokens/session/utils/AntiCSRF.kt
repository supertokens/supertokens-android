package io.supertokens.session.utils

import android.content.Context
import android.content.SharedPreferences
import io.supertokens.session.R

class AntiCSRF {
    companion object {
        @JvmStatic
        private var anticsrftokenInfo: MutableMap<String, String>? = null

        @JvmStatic
        private val lock = Object()

        @JvmStatic
        fun getToken(context: Context, associatedIdRefreshToken: String?): String? = synchronized(lock) {
            if ( associatedIdRefreshToken == null ) {
                anticsrftokenInfo = null
                return null
            }
            if ( anticsrftokenInfo == null ) {
                val sharedPreferences = getSharedPreferences(context)
                val anticsrf = sharedPreferences.getString(getSharedPrefsAntiCSRFKey(context), null)
                if ( anticsrf == null ) {
                    return null
                }
                anticsrftokenInfo = mutableMapOf()
                anticsrftokenInfo!!["antiCSRF"] = anticsrf
                anticsrftokenInfo!!["associatedIdRefreshToken"] = associatedIdRefreshToken
            } else if(anticsrftokenInfo!!["associatedIdRefreshToken"] != associatedIdRefreshToken) {
                anticsrftokenInfo = null
                return getToken(context, associatedIdRefreshToken)
            }
            return anticsrftokenInfo!!["antiCSRF"]
        }

        @JvmStatic
        fun removeToken(context: Context) {
            synchronized(lock) {
                val sharedPreferences = getSharedPreferences(context)
                with(sharedPreferences.edit()) {
                    remove(getSharedPrefsAntiCSRFKey(context))
                    commit()
                }
            }
        }

        @JvmStatic
        fun setToken(context: Context, associatedIdRefreshToken: String?, antiCSRF: String) {
            synchronized(lock) {
                if ( associatedIdRefreshToken == null ) {
                    anticsrftokenInfo = null
                    return
                }
                val sharedPreferences = getSharedPreferences(context)
                with(sharedPreferences.edit()) {
                    putString(getSharedPrefsAntiCSRFKey(context), antiCSRF)
                    commit()
                }
                anticsrftokenInfo = mutableMapOf()
                anticsrftokenInfo!!["antiCSRF"] = antiCSRF
                anticsrftokenInfo!!["associatedIdRefreshToken"] = associatedIdRefreshToken
            }
        }

        @JvmStatic
        private fun getSharedPreferences(context: Context): SharedPreferences =
            context.getSharedPreferences(context.getString(R.string.supertokensSharedPrefsKey), Context.MODE_PRIVATE)

        @JvmStatic
        private fun getSharedPrefsAntiCSRFKey(context: Context): String = context.getString(R.string.supertokensAntiCSRFTokenKey)
    }
}