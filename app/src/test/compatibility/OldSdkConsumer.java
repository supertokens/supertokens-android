package compatibility;

import android.content.Context;
import com.supertokens.session.SuperTokens;

public final class OldSdkConsumer {
    public static void exerciseAbsentSession(Context context) throws Exception {
        new SuperTokens.Builder(context, "https://example.com")
                .tokenTransferMethod("header").build();
        if (SuperTokens.doesSessionExist(context)) throw new AssertionError("Unexpected session");
        if (SuperTokens.getAccessToken(context) != null) throw new AssertionError("Unexpected access token");
        try {
            SuperTokens.getUserId(context);
            throw new AssertionError("Expected an absent-session error");
        } catch (IllegalStateException expected) {
            // The published API throws when no session exists.
        }
    }
}
