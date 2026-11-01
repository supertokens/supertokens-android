package com.supertokens.session;

import java.io.IOException;

/** The captured logical request no longer has authority to dispatch, retry or publish. */
public class StaleSessionException extends IOException {
    public StaleSessionException() {
        super("Session owner changed");
    }

    public StaleSessionException(String message) {
        super(message);
    }
}
