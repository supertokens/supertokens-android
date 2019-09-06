package io.supertokens.session;

import android.app.Application;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.util.List;

class SuperTokensResponseCookieHandler {
    static void saveIdRefreshFromSetCookieOkhttp(Application applicationContext, List<String> setCookie) {
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

    static void saveIdRefreshFromSetCookieHttpUrlConnection(Application applicationContext, List<String> setCookie, HttpURLConnection connection, CookieManager manager) throws URISyntaxException {
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
}
