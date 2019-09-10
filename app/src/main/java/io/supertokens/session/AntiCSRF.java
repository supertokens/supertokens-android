package io.supertokens.session;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

//TODO verify about locking

class AntiCSRF {
    private static AntiCSRFTokenInfo antiCSRFTokenInfo;

    static String getToken(Context context, @Nullable String associatedIdRefreshToken) {
        if ( associatedIdRefreshToken == null ) {
            antiCSRFTokenInfo = null;
            return null;
        }

        if ( antiCSRFTokenInfo == null ) {
            SharedPreferences sharedPreferences = getSharedPreferences(context);
            String antiCSRF = sharedPreferences.getString(getSharedPrefsAntiCSRFKey(context), null);
            if ( antiCSRF == null ) {
                return null;
            }

            antiCSRFTokenInfo = new AntiCSRFTokenInfo(antiCSRF, associatedIdRefreshToken);
        } else if ( antiCSRFTokenInfo.idRefreshToken != null && !antiCSRFTokenInfo.idRefreshToken.equals(associatedIdRefreshToken) ) {
            antiCSRFTokenInfo = null;
            return getToken(context, associatedIdRefreshToken);
        }

        return antiCSRFTokenInfo.antiCSRF;
    }

    @SuppressLint("ApplySharedPref")
    static void removeToken(Context context) {
        SharedPreferences sharedPreferences = getSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove(getSharedPrefsAntiCSRFKey(context));
        editor.commit();

        antiCSRFTokenInfo = null;
    }

    @SuppressLint("ApplySharedPref")
    static void setToken(Context context, @Nullable String associatedRefreshToken, String antiCSRFToken) {
        if ( associatedRefreshToken == null ) {
            antiCSRFTokenInfo = null;
            return;
        }

        SharedPreferences sharedPreferences = getSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(getSharedPrefsAntiCSRFKey(context), antiCSRFToken);
        editor.commit();

        antiCSRFTokenInfo = new AntiCSRFTokenInfo(antiCSRFToken, associatedRefreshToken);
    }

    private static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(context.getString(R.string.supertokensSharedPrefsKey), Context.MODE_PRIVATE);
    }

    private static String getSharedPrefsAntiCSRFKey(Context context) {
        return context.getString(R.string.supertokensAntiCSRFTokenKey);
    }

    static class AntiCSRFTokenInfo {
        String antiCSRF;
        String idRefreshToken;

        AntiCSRFTokenInfo(String antiCSRF, String idRefreshToken) {
            this.antiCSRF = antiCSRF;
            this.idRefreshToken = idRefreshToken;
        }
    }
}
