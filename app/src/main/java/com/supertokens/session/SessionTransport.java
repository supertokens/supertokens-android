package com.supertokens.session;

import android.content.Context;
import java.io.IOException;
import java.net.URL;

/** Shared ownership policy; transport operations never hold the session lock. */
final class SessionTransport {
    static volatile Runnable beforeDispatchForTests;

    static void beforeDispatch() {
        Runnable hook = beforeDispatchForTests;
        if (hook != null) hook.run();
    }

    static String retryLimitMessage(String url, int attempts) {
        return "Received a 401 response from " + url + ". Attempted to refresh the session and retry the request with the updated session tokens "
                + attempts + " times, but each attempt resulted in a 401 error. The maximum session refresh limit has been reached. Please investigate your API. To increase the session refresh attempts, update maxRetryAttemptsForSessionRefresh in the config.";
    }

    private SessionTransport() {}

    enum AuthorizationPolicy { SDK_BEARER, CALLER_NON_BEARER, FOREIGN_BEARER }

    static AuthorizationPolicy authorizationPolicy(String value, SessionSnapshot snapshot) {
        if (value == null || matches(value, authorization(snapshot, false))) return AuthorizationPolicy.SDK_BEARER;
        return isBearer(value) ? AuthorizationPolicy.FOREIGN_BEARER : AuthorizationPolicy.CALLER_NON_BEARER;
    }

    private static boolean isBearer(String value) {
        return value != null && value.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    static boolean isResponseInScope(String sentUrl, String finalUrl,
                                     SessionState.Configuration configuration) throws IOException {
        if (!Utils.shouldDoInterceptionBasedOnUrl(sentUrl, configuration.values.apiDomain,
                configuration.values.sessionTokenBackendDomain)) return false;
        URL sent = new URL(sentUrl);
        URL received = new URL(finalUrl);
        int sentPort = sent.getPort() < 0 ? sent.getDefaultPort() : sent.getPort();
        int receivedPort = received.getPort() < 0 ? received.getDefaultPort() : received.getPort();
        // Outbound compatibility trusts hosts; redirected responses additionally require the sent origin.
        return sent.getProtocol().equalsIgnoreCase(received.getProtocol())
                && sent.getHost().equalsIgnoreCase(received.getHost()) && sentPort == receivedPort
                && received.getUserInfo() == null;
    }

    static void requireCurrent(SessionRequestTag tag) throws StaleSessionException {
        if (!SessionState.isCurrent(tag.getOwner(), tag.configuration)) throw new StaleSessionException();
    }

    static void requireCurrentRefresh(SessionRequestTag tag) throws StaleSessionException {
        if (!SessionState.isCurrentRefresh(tag)) throw new StaleSessionException();
    }

    static SessionRequestTag nextWire(SessionRequestTag logical) throws StaleSessionException {
        return nextWire(logical, true);
    }

    static SessionRequestTag nextWire(SessionRequestTag logical, boolean tagged) throws StaleSessionException {
        SessionRequestTag wire = SessionState.capture();
        if (wire.getOwner() != logical.getOwner() || wire.configuration != logical.configuration) {
            if (tagged) throw new StaleSessionException();
            return null;
        }
        return wire;
    }

    static String authorization(SessionSnapshot snapshot, boolean refresh) {
        SessionTokens tokens = snapshot.getInternalTokens();
        if (tokens == null || tokens.getAccessToken() == null || tokens.getRefreshToken() == null) return null;
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
        if (!isBearer(expected)) return actual.equals(expected);
        return isBearer(actual) && actual.substring(7).equals(expected.substring(7));
    }

    static boolean isRecovering(SessionSnapshot snapshot) {
        return snapshot.getStatus() == SessionSnapshot.Status.RECOVERY_REQUIRED
                || snapshot.getStatus() == SessionSnapshot.Status.UNAVAILABLE;
    }

    static boolean apply(Context context, SessionRequestTag logical, SessionRequestTag wire,
                         SessionResponse response, boolean tagged) throws IOException {
        if (tagged && isRecovering(wire.getSnapshot())) {
            requireCurrent(wire);
            throw new IOException("Session storage recovery is required");
        }
        SessionCommitResult result = SessionState.applyOrdinaryResponse(context, wire, response, !tagged);
        SessionSnapshot accepted = wire.getAcceptedSnapshot();
        if (accepted != null) logical.markAccepted(accepted);
        SessionState.dispatch(result);
        if (result.getPersistenceError() != null) throw result.getPersistenceError();
        if (tagged && result.getStatus() == SessionCommitResult.Status.RECOVERY_REQUIRED) {
            throw new IOException("Session storage recovery is required");
        }
        boolean current = SessionState.isCurrent(accepted == null ? logical.getOwner()
                : accepted.getOwner(), logical.configuration);
        if (!current && tagged) throw new StaleSessionException();
        return current;
    }
}
