package com.supertokens.testing;

import android.content.Context;
import com.franmontiel.persistentcookiejar.PersistentCookieJar;
import com.franmontiel.persistentcookiejar.cache.SetCookieCache;
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor;
import java.net.CookieHandler;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.HttpUrl;

/** Both transports use the same persisted cookies, including after candidate restart. */
public final class UpgradeCookies extends CookieHandler {
    public final CookieJar jar;

    public UpgradeCookies(Context context) {
        jar = new PersistentCookieJar(new SetCookieCache(), new SharedPrefsCookiePersistor(context));
    }

    @Override public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) {
        StringBuilder value = new StringBuilder();
        for (Cookie cookie : jar.loadForRequest(HttpUrl.get(uri))) {
            if (value.length() > 0) value.append("; ");
            value.append(cookie.name()).append('=').append(cookie.value());
        }
        return value.length() == 0 ? Collections.<String, List<String>>emptyMap()
                : Collections.singletonMap("Cookie", Collections.singletonList(value.toString()));
    }

    @Override public void put(URI uri, Map<String, List<String>> values) {
        Headers.Builder headers = new Headers.Builder();
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            if (entry.getKey() == null) continue;
            for (String value : entry.getValue()) headers.add(entry.getKey(), value);
        }
        HttpUrl url = HttpUrl.get(uri);
        jar.saveFromResponse(url, Cookie.parseAll(url, headers.build()));
    }
}
