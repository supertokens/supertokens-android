package io.supertokens.session.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import io.supertokens.session.R;

public class IdRefreshToken {
    public static String getToken(Context context) {
        return getSharedPreferences(context).getString(context.getString(R.string.supertokensIdRefreshKey), null);
    }

    @SuppressLint("ApplySharedPref")
    public static void setToken(Context context, String idRefreshToken) {
        SharedPreferences.Editor editor = getSharedPreferences(context).edit();
        editor.putString(getSharedPrefsIdRefreshKey(context), idRefreshToken);
        editor.commit();
    }

    @SuppressLint("ApplySharedPref")
    public static void removeToken(Context context) {
        SharedPreferences.Editor editor = getSharedPreferences(context).edit();
        editor.remove(getSharedPrefsIdRefreshKey(context));
        editor.commit();
    }

    private static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(context.getString(R.string.supertokensSharedPrefsKey), Context.MODE_PRIVATE);
    }

    private static String getSharedPrefsIdRefreshKey(Context context) {
        return context.getString(R.string.supertokensIdRefreshKey);
    }
}
