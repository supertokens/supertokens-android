package com.supertokens.upgrade;

public final class MatrixNativePublishedTest extends MatrixNativeStage {
    @Override protected void continueSession() {
        throw new AssertionError("Candidate continuation requires candidate APK");
    }
}
