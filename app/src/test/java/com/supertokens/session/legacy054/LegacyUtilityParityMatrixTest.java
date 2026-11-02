package com.supertokens.session.legacy054;

import com.supertokens.session.Legacy054Harness;
import com.supertokens.session.SessionTestFixture;
import com.supertokens.session.Utils;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class LegacyUtilityParityMatrixTest extends SessionTestFixture {
    @Override protected boolean initializeOnSetup() { return false; }

    @Test public void r23PreInitAuthorizationReadsStoredTokens() throws Exception {
        context.getSharedPreferences("supertokens-android-shared-preferences", 0).edit()
                .putString("st-storage-item-st-access-token", "access")
                .putString("st-storage-item-st-refresh-token", "refresh").commit();
        try (Legacy054Harness old = new Legacy054Harness(context)) {
            assertEquals(old.invoke("getAuthorizationHeaderIfExists", new Class<?>[]{boolean.class}, false),
                    Utils.getAuthorizationHeaderIfExists(false, context));
            assertEquals(old.invoke("getAuthorizationHeaderIfExists", new Class<?>[]{boolean.class}, true),
                    Utils.getAuthorizationHeaderIfExists(true, context));
        }
    }
}
