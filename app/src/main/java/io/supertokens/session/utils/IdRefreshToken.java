package io.supertokens.session.utils;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import io.supertokens.session.R;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.util.List;

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

    public static void saveIdRefreshFromSetCookieOkhttp(Application applicationContext, List<String> setCookie) {
        if ( setCookie.size() > 0 ) {
            for(int i = 0; i < setCookie.size(); i++) {
                HttpCookie currentCookie = HttpCookie.parse(setCookie.get(i)).get(0);
                if (currentCookie.getName().equals(applicationContext.getString(R.string.supertokensIdRefreshCookieKey))) {
                    if ( currentCookie.hasExpired() ) {
                        IdRefreshToken.removeToken(applicationContext);
                    } else {
                        IdRefreshToken.setToken(applicationContext, currentCookie.getValue());
                    }
                }
            }
        }
    }

    public static void saveIdRefreshFromSetCookieHttpUrlConnection(Application applicationContext, List<String> setCookie, HttpURLConnection connection, CookieManager manager) throws URISyntaxException {
        if ( setCookie != null ) {
            for(int i=0; i<setCookie.size(); i++) {
                HttpCookie currentCookie = HttpCookie.parse(setCookie.get(i)).get(0);
                manager.getCookieStore().add(connection.getURL().toURI(), currentCookie);
                if (currentCookie.getName().equals(applicationContext.getString(R.string.supertokensIdRefreshCookieKey))) {
                    if ( currentCookie.hasExpired() ) {
                        IdRefreshToken.removeToken(applicationContext);
                    } else {
                        IdRefreshToken.setToken(applicationContext, currentCookie.getValue());
                    }
                }
            }
        }
    }

    private static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(context.getString(R.string.supertokensSharedPrefsKey), Context.MODE_PRIVATE);
    }

    private static String getSharedPrefsIdRefreshKey(Context context) {
        return context.getString(R.string.supertokensIdRefreshKey);
    }
}
