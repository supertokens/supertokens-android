package io.supertokens.session;

import android.app.Application;
import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings({"Convert2Diamond"})
public class SuperTokens {
    static int sessionExpiryStatusCode = 440;
    @SuppressWarnings("unused")
    static String apiDomain;
    static boolean isInitCalled = false;
    @SuppressWarnings("unused")
    static final String TAG = "io.supertokens.session";
    static String refreshTokenEndpoint;
    static WeakReference<Application> contextWeakReference;
    static Map<String, String> refreshAPICustomHeaders = new HashMap<>();


    @SuppressWarnings("unused")
    public static void init(Application applicationContext, @NonNull String refreshTokenEndpoint, @Nullable Integer sessionExpiryStatusCode,
                            @Nullable Map<String, String> refreshAPICustomHeaders) throws MalformedURLException {
        if ( SuperTokens.isInitCalled ) {
            return;
        }
        contextWeakReference = new WeakReference<Application>(applicationContext);
        SuperTokens.refreshTokenEndpoint = refreshTokenEndpoint;
        SuperTokens.refreshAPICustomHeaders = refreshAPICustomHeaders;
        if ( sessionExpiryStatusCode != null ) {
            SuperTokens.sessionExpiryStatusCode = sessionExpiryStatusCode;
        }

        SuperTokens.apiDomain = SuperTokens.getApiDomain(refreshTokenEndpoint);
        SuperTokens.isInitCalled = true;
    }

    static String getApiDomain(@NonNull String url) throws MalformedURLException {
        if ( url.startsWith("http://") || url.startsWith("https://") ) {
            String[] splitArray = url.split("/");
            ArrayList<String> apiDomainArray = new ArrayList<String>();
            for(int i=0; i<=2; i++) {
                try {
                    apiDomainArray.add(splitArray[i]);
                } catch(IndexOutOfBoundsException e) {
                    throw new MalformedURLException("Invalid URL provided for refresh token endpoint");
                }
            }
            return TextUtils.join("/", apiDomainArray);
        } else {
            throw new MalformedURLException("Refresh token endpoint must start with http or https");
        }
    }

    @SuppressWarnings("unused")
    public static boolean sessionPossiblyExists(Context context) {
        String idRefreshToken = IdRefreshToken.getToken(context);
        return idRefreshToken != null;
    }
}
