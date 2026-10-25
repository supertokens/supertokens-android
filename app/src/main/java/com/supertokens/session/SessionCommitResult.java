package com.supertokens.session;

import androidx.annotation.Nullable;

import java.io.IOException;

public final class SessionCommitResult {
    public enum Status {
        SUCCESS, STALE_OWNER, INVALID_SESSION, PERSISTENCE_FAILED,
        RECOVERY_REQUIRED, NOT_CONFIGURED
    }

    private final Status status;
    @Nullable
    private final SessionSnapshot snapshot;
    @Nullable
    private final IOException persistenceError;

    SessionCommitResult(Status status, @Nullable SessionSnapshot snapshot,
                        @Nullable IOException persistenceError) {
        this.status = status;
        this.snapshot = snapshot;
        this.persistenceError = persistenceError;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    @Nullable
    public SessionSnapshot getSnapshot() {
        return snapshot;
    }

    @Nullable
    public IOException getPersistenceError() {
        return persistenceError;
    }
}
