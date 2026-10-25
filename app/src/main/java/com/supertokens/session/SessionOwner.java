package com.supertokens.session;

/** Opaque process-local authority for one installed session or validated absence. */
public final class SessionOwner {
    private volatile boolean current = true;

    SessionOwner() {}

    boolean isCurrent() {
        return current;
    }

    void invalidate() {
        current = false;
    }

    @Override
    public String toString() {
        return "SessionOwner(current=" + current + ")";
    }
}
