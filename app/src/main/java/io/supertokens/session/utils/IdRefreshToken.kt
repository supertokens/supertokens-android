package io.supertokens.session.utils

import android.content.Context
import android.content.SharedPreferences
import io.supertokens.session.R
import java.util.*

class IdRefreshToken {
    companion object {
        @JvmStatic
        private val lock = Object()

        @JvmStatic
        fun getToken(context: Context): String? {
            val sharedPreferences = getSharedPreferences(context)
            return sharedPreferences.getString(getSharedPrefsKey(context), null)
        }

        @JvmStatic
        fun setToken(context: Context, idRefreshToken: String) {
            val sharedPreferences = getSharedPreferences(context)
            with(sharedPreferences.edit()) {
                putString(getSharedPrefsKey(context), idRefreshToken)
                commit()
            }
        }

        @JvmStatic
        fun removeToken(context: Context) {
            val sharedPreferences = getSharedPreferences(context)
            with(sharedPreferences.edit()) {
                remove(getSharedPrefsKey(context))
                commit()
            }
        }

        @JvmStatic
        private fun getSharedPreferences(context: Context): SharedPreferences =
            context.getSharedPreferences(context.getString(R.string.supertokensSharedPrefsKey), Context.MODE_PRIVATE)

        @JvmStatic
        private fun getSharedPrefsKey(context: Context) = context.getString(R.string.supertokensIdRefreshKey)

    }
}