package com.supertokens.session;

import android.content.Context;
import java.io.IOException;

/** Shared ownership policy; transport operations never hold the session lock. */
final class SessionTransport {
    static String retryLimitMessage(String url, int attempts) {
        return "Received a 401 response from " + url + ". Attempted to refresh the session and retry the request with the updated session tokens "
                + attempts + " times, but each attempt resulted in a 401 error. The maximum session refresh limit has been reached. Please investigate your API. To increase the session refresh attempts, update maxRetryAttemptsForSessionRefresh in the config.";
    }

    private SessionTransport() {}

    static void requireCurrent(SessionRequestTag tag) throws StaleSessionException {
        if (!SessionState.isCurrent(tag.getOwner(), tag.configuration)) throw new StaleSessionException();
    }

    static SessionRequestTag nextWire(SessionRequestTag logical) throws StaleSessionException {
        SessionRequestTag wire = SessionState.capture();
        if (wire.getOwner() != logical.getOwner() || wire.configuration != logical.configuration) {
            throw new StaleSessionException();
        }
        return wire;
    }

    static String authorization(SessionSnapshot snapshot, boolean refresh) {
        SessionTokens tokens = snapshot.getInternalTokens();
        if (tokens == null) return null;
        String token = refresh ? tokens.getRefreshToken() : tokens.getAccessToken();
        return token == null ? null : "Bearer " + token;
    }

    static String antiCSRF(SessionSnapshot snapshot) {
        SessionTokens tokens = snapshot.getInternalTokens();
        return tokens == null ? null : tokens.getAntiCSRF();
    }

    static String access(SessionSnapshot snapshot) {
        SessionTokens tokens = snapshot.getInternalTokens();
        return tokens == null ? null : tokens.getAccessToken();
    }

    static boolean matches(String actual, String expected) {
        if (actual == null || expected == null) return actual == expected;
        return actual.length() >= 7 && expected.length() >= 7
                && actual.regionMatches(true, 0, "Bearer ", 0, 7)
                && actual.substring(7).equals(expected.substring(7));
    }

    static boolean apply(Context context, SessionRequestTag logical, SessionRequestTag wire,
                         SessionResponse response, boolean tagged) throws IOException {
        SessionCommitResult result = SessionState.applyOrdinaryResponse(context, wire, response);
        SessionSnapshot accepted = wire.getAcceptedSnapshot();
        if (accepted != null) logical.markAccepted(accepted);
        SessionState.dispatch();
        if (result.getPersistenceError() != null) throw result.getPersistenceError();
        if (result.getStatus() == SessionCommitResult.Status.RECOVERY_REQUIRED) {
            throw new IOException("Session storage recovery is required");
        }
        boolean current = SessionState.isCurrent(accepted == null ? logical.getOwner()
                : accepted.getOwner(), logical.configuration);
        if (!current && tagged) throw new StaleSessionException();
        return current;
    }
}
