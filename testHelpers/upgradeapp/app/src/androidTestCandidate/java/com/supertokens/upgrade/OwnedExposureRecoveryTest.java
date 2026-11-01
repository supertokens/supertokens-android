package com.supertokens.upgrade;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import com.supertokens.session.SessionSnapshot;
import com.supertokens.session.SuperTokens;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

/** Explicit recovery only, after backend cleanup, with exclusive SDK/preferences writers; never a continuation result. */
public class OwnedExposureRecoveryTest {
    @Test public void clearAcknowledgedLocalResidue() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences receipt = context.getSharedPreferences("upgrade-receipt", 0);
        assertEquals("candidate", BuildConfig.SDK_NAME);
        assertNotNull(args.getString("runId"));
        assertNotNull(args.getString("scenario"));
        assertNotNull(args.getString("backend"));
        assertEquals(args.getString("runId"), receipt.getString("runId", null));
        assertEquals(args.getString("scenario"), receipt.getString("scenario", null));
        assertEquals(args.getString("backend"), receipt.getString("backend", null));
        assertEquals("cleanup", args.getString("stage"));
        assertEquals("[]", receipt.getString("owners", null));
        assertFalse(receipt.contains("pendingRequestId"));
        assertFalse(receipt.getBoolean("cleanupDone", true));
        SharedPreferences cookies = context.getSharedPreferences("supertokens-cookie-shared-prefs", 0);
        OwnedRecoveryGuard.requireNoCookieResidue(cookies.getAll());
        new SuperTokens.Builder(context, args.getString("backend")).tokenTransferMethod("cookie").build();
        final SessionSnapshot captured = SuperTokens.getSessionSnapshot(context);
        OwnedRecoveryGuard.recover(new OwnedRecoveryGuard.Captured() {
            @Override public String status() { return captured.getStatus().name(); }
            @Override public String userId() { return captured.getUserId(); }
            @Override public String handle() { return captured.getSessionHandle(); }
            @Override public boolean clearCapturedOwner() {
                return SuperTokens.clearSession(context, captured.getOwner()).isSuccess();
            }
        }, receipt.getString("userId", null), receipt.getString("handle", null), cookies.getAll());
        // Verification only; never recapture an owner for mutation or invoke signOut here.
        assertEquals(SessionSnapshot.Status.ABSENT, SuperTokens.getSessionSnapshot(context).getStatus());
        OwnedRecoveryGuard.requireNoCookieResidue(cookies.getAll());
        SharedPreferences persisted = context.getSharedPreferences("supertokens-android-shared-preferences", 0);
        assertTrue(persisted.edit().commit());
        assertTrue(cookies.edit().commit());
        assertNull("Persisted header access survived clear", persisted.getString("st-storage-item-st-access-token", null));
        assertNull("Persisted header refresh survived clear", persisted.getString("st-storage-item-st-refresh-token", null));
        assertNull("Unexpected access after clear", SuperTokens.getAccessToken(context));
        try {
            SuperTokens.getUserId(context);
            fail("Logged-out getter returned a user");
        } catch (IllegalStateException expected) { }
        assertTrue(receipt.edit().putBoolean("cleanupDone", true)
                .putString("process", UUID.randomUUID().toString()).putString("phase", "cleaned").commit());
    }
}
