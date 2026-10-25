package com.supertokens.session;

import androidx.annotation.Nullable;

/** Logical-request capture accepted as an OkHttp tag and URLConnection overload argument. */
public final class SessionRequestTag {
    private final SessionSnapshot snapshot;
    final SessionState.Configuration configuration;
    private volatile SessionSnapshot acceptedSnapshot;

    SessionRequestTag(SessionSnapshot snapshot, SessionState.Configuration configuration) {
        this.snapshot = snapshot;
        this.configuration = configuration;
    }

    public SessionOwner getOwner() {
        return snapshot.getOwner();
    }

    public SessionSnapshot getSnapshot() {
        return snapshot;
    }

    @Nullable
    public SessionSnapshot getAcceptedSnapshot() {
        return acceptedSnapshot;
    }

    void markAccepted(SessionSnapshot snapshot) {
        acceptedSnapshot = snapshot;
    }

    @Override
    public String toString() {
        return "SessionRequestTag(revision=" + snapshot.getRevision() + ", accepted="
                + (acceptedSnapshot != null) + ")";
    }
}
