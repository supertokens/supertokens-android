package com.supertokens.session;

public interface SessionObserver {
    void onSessionChanged(SessionSnapshot snapshot);
}
