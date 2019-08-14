package io.supertokens.session;

import android.app.Application;
import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.supertokens.session.utils.IdRefreshToken;
import io.supertokens.session.utils.SuperTokensPersistentCookieStore;

import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.util.ArrayList;

@SuppressWarnings({"Convert2Diamond", "ManualArrayToCollectionCopy"})
public class SuperTokens {
    static int sessionExpiryStatusCode = 440;
    static String apiDomain;
    static boolean isInitCalled = false;
    static final String TAG = "io.supertokens.session";
    static String refreshTokenEndpoint;
    static WeakReference<Application> contextWeakReference;
    static SuperTokensPersistentCookieStore persistentCookieStore;


    @SuppressWarnings("unused")
    public static void init(Application applicationContext, @NonNull String refreshTokenEndpoint, @Nullable Integer sessionExpiryStatusCode) throws MalformedURLException {
        if ( SuperTokens.isInitCalled ) {
            return;
        }
        persistentCookieStore = new SuperTokensPersistentCookieStore(applicationContext);
        contextWeakReference = new WeakReference<Application>(applicationContext);
        SuperTokens.isInitCalled = true;
        SuperTokens.refreshTokenEndpoint = refreshTokenEndpoint;
        if ( sessionExpiryStatusCode != null ) {
            SuperTokens.sessionExpiryStatusCode = sessionExpiryStatusCode;
        }

        SuperTokens.apiDomain = SuperTokens.getApiDomain(refreshTokenEndpoint);
    }

    private static String getApiDomain(@NonNull String refreshTokenEndpoint) throws MalformedURLException {
        if ( refreshTokenEndpoint.startsWith("http://") || refreshTokenEndpoint.startsWith("https://") ) {
            String[] splitArray = refreshTokenEndpoint.split("/");
            ArrayList<String> apiDomainArray = new ArrayList<String>();
            for(int i=0; i<=2; i++) {
                apiDomainArray.add(splitArray[i]);
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
