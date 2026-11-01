package com.supertokens.session;

import androidx.annotation.Nullable;

import java.io.IOException;

public final class SessionResolution {
    public enum Status {
        SUCCESS, STALE_OWNER, NO_SESSION, REFRESH_FAILED,
        RECOVERY_REQUIRED, NOT_CONFIGURED
    }

    private final Status status;
    @Nullable
    private final SessionSnapshot snapshot;
    @Nullable
    private final IOException error;
    final boolean legacyHandlerFailure;
    final boolean pendingReplacement;

    SessionResolution(Status status, @Nullable SessionSnapshot snapshot,
                      @Nullable IOException error) {
        this(status, snapshot, error, false);
    }

    SessionResolution(Status status, @Nullable SessionSnapshot snapshot,
                      @Nullable IOException error, boolean legacyHandlerFailure) {
        this(status, snapshot, error, legacyHandlerFailure, false);
    }

    SessionResolution(Status status, @Nullable SessionSnapshot snapshot,
                      @Nullable IOException error, boolean legacyHandlerFailure, boolean pendingReplacement) {
        this.status = status;
        this.snapshot = snapshot;
        this.error = error;
        this.legacyHandlerFailure = legacyHandlerFailure;
        this.pendingReplacement = pendingReplacement;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    @Nullable
    public String getAccessToken() {
        if (!isSuccess() || snapshot == null || snapshot.getTokens() == null) {
            return null;
        }
        return snapshot.getTokens().getAccessToken();
    }

    @Nullable
    public SessionSnapshot getSnapshot() {
        return snapshot;
    }

    @Nullable
    public IOException getError() {
        return error;
    }
}
