package io.supertokens.session;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

//TODO verify about locking
class IdRefreshToken {
    private static String idRefreshTokenInMemory;

    static String getToken(Context context) {
        if ( idRefreshTokenInMemory == null ) {
            idRefreshTokenInMemory = getSharedPreferences(context).getString(context.getString(R.string.supertokensIdRefreshSharedPrefsKey), null);
        }
        if (idRefreshTokenInMemory != null) {
            String[] splitted = idRefreshTokenInMemory.split(";");
            long expiry = Long.parseLong(splitted[1]);
            if (expiry < System.currentTimeMillis()) {
                removeToken(context);
            }
        }
        return idRefreshTokenInMemory;
    }

    @SuppressLint("ApplySharedPref")
    static void setToken(Context context, String idRefreshToken) {
        if (idRefreshToken.equals("remove")) {
            removeToken(context);
            return;
        }
        String[] splitted = idRefreshToken.split(";");
        long expiry = Long.parseLong(splitted[1]);
        if (expiry < System.currentTimeMillis()) {
            removeToken(context);
        } else {
            SharedPreferences.Editor editor = getSharedPreferences(context).edit();
            editor.putString(context.getString(R.string.supertokensIdRefreshSharedPrefsKey), idRefreshToken);
            editor.apply();
            idRefreshTokenInMemory = idRefreshToken;
        }
    }

    @SuppressLint("ApplySharedPref")
    static void removeToken(Context context) {
        SharedPreferences.Editor editor = getSharedPreferences(context).edit();
        editor.remove(context.getString(R.string.supertokensIdRefreshSharedPrefsKey));
        editor.apply();
        idRefreshTokenInMemory = null;
    }

    private static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(context.getString(R.string.supertokensSharedPrefsKey), Context.MODE_PRIVATE);
    }
}
