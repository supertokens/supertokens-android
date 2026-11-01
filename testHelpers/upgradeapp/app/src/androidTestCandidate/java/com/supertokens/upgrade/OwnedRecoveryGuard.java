package com.supertokens.upgrade;

import java.util.Map;

final class OwnedRecoveryGuard {
    interface Captured {
        String status();
        String userId();
        String handle();
        boolean clearCapturedOwner();
    }

    static void requireNoCookieResidue(Map<String, ?> persistedCookies) {
        // Store reads can expire/delete cookies. Inspect raw persistence instead.
        // No cross-instance conditional removal exists, even with exact fingerprints.
        require(persistedCookies.isEmpty(),
                "Cookie residue requires manual reconciliation: exact recorded value/domain/path "
                + "evidence and exclusive writer control; automated deletion is unsupported");
    }

    static void recover(Captured captured, String recordedUser, String recordedHandle,
                        Map<String, ?> persistedCookies) {
        requireNoCookieResidue(persistedCookies);
        require("AVAILABLE".equals(captured.status()),
                "Ambiguous local status: no durable explicit identity proof for residual credentials");
        require(recordedUser != null && !recordedUser.isEmpty()
                        && recordedHandle != null && !recordedHandle.isEmpty(),
                "Missing recorded session identity");
        require(recordedUser.equals(captured.userId()) && recordedHandle.equals(captured.handle()),
                "Captured session does not match recorded identity");
        require(captured.clearCapturedOwner(), "Captured owner changed or durable clear failed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
