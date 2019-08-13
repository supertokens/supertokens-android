package io.supertokens.session.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.Nullable;
import io.supertokens.session.R;

import java.util.HashMap;

@SuppressWarnings("Convert2Diamond")
public class AntiCSRF {
    private static HashMap<String, String> antiCSRFTokenInfo;
    private static final  Object lock = new Object();
    private static final String antiCSRFMapKey = "antiCSRF";
    private static final String associatedIdRefreshMapKey = "associatedIdRefreshToken";

    @SuppressWarnings("ConstantConditions")
    public static String getToken(Context context, @Nullable String associatedRefreshToken) {
        synchronized (lock) {
            if ( associatedRefreshToken == null ) {
                return null;
            }

            if ( antiCSRFTokenInfo == null ) {
                SharedPreferences sharedPreferences = getSharedPreferences(context);
                String antiCSRF = sharedPreferences.getString(getSharedPrefsAntiCSRFKey(context), null);
                if ( antiCSRF == null ) {
                    return null;
                }

                antiCSRFTokenInfo = new HashMap<String, String>();
                antiCSRFTokenInfo.put(antiCSRFMapKey, antiCSRF);
                antiCSRFTokenInfo.put(associatedIdRefreshMapKey, associatedRefreshToken);
            } else if ( antiCSRFTokenInfo.get(associatedIdRefreshMapKey) != null && !antiCSRFTokenInfo.get(associatedIdRefreshMapKey).equals(associatedRefreshToken) ) {
                antiCSRFTokenInfo = null;
                return getToken(context, associatedRefreshToken);
            }

            return antiCSRFTokenInfo.get(antiCSRFMapKey);
        }
    }

    @SuppressLint("ApplySharedPref")
    public static void removeToken(Context context) {
        synchronized (lock) {
            SharedPreferences sharedPreferences = getSharedPreferences(context);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.remove(getSharedPrefsAntiCSRFKey(context));
            editor.commit();
        }
    }

    @SuppressLint("ApplySharedPref")
    public static void setToken(Context context, @Nullable String associatedRefreshToken, String antiCSRFToken) {
        synchronized (lock) {
            if ( associatedRefreshToken == null ) {
                antiCSRFTokenInfo = null;
                return;
            }

            SharedPreferences sharedPreferences = getSharedPreferences(context);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(getSharedPrefsAntiCSRFKey(context), antiCSRFToken);
            editor.commit();

            antiCSRFTokenInfo = new HashMap<String, String>();
            antiCSRFTokenInfo.put(antiCSRFMapKey, antiCSRFToken);
            antiCSRFTokenInfo.put(associatedIdRefreshMapKey, associatedRefreshToken);
        }
    }

    private static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(context.getString(R.string.supertokensSharedPrefsKey), Context.MODE_PRIVATE);
    }

    private static String getSharedPrefsAntiCSRFKey(Context context) {
        return context.getString(R.string.supertokensAntiCSRFTokenKey);
    }
}
