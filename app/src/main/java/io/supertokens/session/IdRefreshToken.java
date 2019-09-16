package io.supertokens.session;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

//TODO verify about locking
class IdRefreshToken {
    private static String idRefreshTokenInMemory;

    static String getToken(Context context) {
        if ( idRefreshTokenInMemory == null ) {
            idRefreshTokenInMemory = getSharedPreferences(context).getString(context.getString(R.string.supertokensIdRefreshKey), null);
        }
        return idRefreshTokenInMemory;
    }

    @SuppressLint("ApplySharedPref")
    static void setToken(Context context, String idRefreshToken) {
        idRefreshTokenInMemory = idRefreshToken;
        SharedPreferences.Editor editor = getSharedPreferences(context).edit();
        editor.putString(getSharedPrefsIdRefreshKey(context), idRefreshToken);
        editor.commit();
    }

    @SuppressLint("ApplySharedPref")
    static void removeToken(Context context) {
        SharedPreferences.Editor editor = getSharedPreferences(context).edit();
        editor.remove(getSharedPrefsIdRefreshKey(context));
        editor.commit();
        idRefreshTokenInMemory = null;
    }

    private static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(context.getString(R.string.supertokensSharedPrefsKey), Context.MODE_PRIVATE);
    }

    private static String getSharedPrefsIdRefreshKey(Context context) {
        return context.getString(R.string.supertokensIdRefreshKey);
    }
}
