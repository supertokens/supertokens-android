package com.supertokens.session;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.text.ParseException;

final class SessionResponse {
    final int code;
    final String message;
    private final Map<String, List<String>> headers;

    SessionResponse(int code, @Nullable String message, Map<String, List<String>> headers) {
        this.code = code;
        this.message = message == null ? "" : message;
        this.headers = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                this.headers.put(entry.getKey(), Collections.unmodifiableList(
                        new ArrayList<>(entry.getValue())));
            }
        }
    }

    static SessionResponse fromOkHttp(okhttp3.Response response) {
        return new SessionResponse(response.code(), response.message(), response.headers().toMultimap());
    }

    static SessionResponse fromHttp(java.net.HttpURLConnection connection) {
        Map<String, List<String>> headers = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            List<String> values = new ArrayList<>(entry.getValue());
            String selected = connection.getHeaderField(entry.getKey());
            if (selected != null && values.remove(selected)) values.add(selected);
            headers.put(entry.getKey(), values);
        }
        return new SessionResponse(safeCode(connection), safeMessage(connection), headers);
    }

    private static int safeCode(java.net.HttpURLConnection connection) {
        try {
            return connection.getResponseCode();
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    private static String safeMessage(java.net.HttpURLConnection connection) {
        try {
            return connection.getResponseMessage();
        } catch (java.io.IOException e) {
            return "";
        }
    }

    boolean hasCredentialHeaders() {
        return has(Constants.ACCESS_TOKEN_HEADER_KEY) || has(Constants.REFRESH_TOKEN_HEADER_KEY)
                || has(Constants.FRONT_TOKEN_HEADER_KEY) || has(Constants.CSRF_HEADER_KEY);
    }

    boolean has(String name) {
        return !values(name).isEmpty();
    }

    @Nullable
    String single(String name) {
        List<String> values = values(name);
        if (values.isEmpty()) return null;
        if (values.size() != 1) {
            throw new IllegalArgumentException("Duplicate session header: " + name);
        }
        String value = values.get(0);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty session header: " + name);
        }
        return value;
    }

    boolean isRemoval() {
        return isRemoval(false);
    }

    private boolean isRemoval(boolean compatible) {
        if (!has(Constants.FRONT_TOKEN_HEADER_KEY)) return false;
        List<String> frontValues = values(Constants.FRONT_TOKEN_HEADER_KEY);
        if (!compatible && frontValues.size() != 1) {
            throw new IllegalArgumentException("Duplicate front-token header");
        }
        return "remove".equalsIgnoreCase(frontValues.get(frontValues.size() - 1));
    }

    SessionResponse forSignOut() {
        if (!isRemoval() || credentialIfPresent(Constants.ACCESS_TOKEN_HEADER_KEY) == null) return this;
        // Session verification can regenerate access before sign-out adds its removal headers.
        // Discard only that superseded credential; ordinary responses remain strictly validated.
        Map<String, List<String>> removalHeaders = new HashMap<>(headers);
        for (String name : headers.keySet()) {
            if (name.equalsIgnoreCase(Constants.ACCESS_TOKEN_HEADER_KEY)) removalHeaders.remove(name);
        }
        return new SessionResponse(code, message, removalHeaders);
    }

    @Nullable
    Candidate ordinaryCandidate() {
        return ordinaryCandidate(false);
    }

    @Nullable
    Candidate ordinaryCandidate(boolean compatible) {
        if (!hasCredentialHeaders()) return null;
        if (isRemoval(compatible)) {
            if (compatible) return Candidate.removal();
            String access = optionalEmpty(Constants.ACCESS_TOKEN_HEADER_KEY);
            String refresh = optionalEmpty(Constants.REFRESH_TOKEN_HEADER_KEY);
            String anti = optionalEmpty(Constants.CSRF_HEADER_KEY);
            if (access != null || refresh != null || anti != null) {
                throw new IllegalArgumentException("Conflicting session removal headers");
            }
            return Candidate.removal();
        }

        String access = credentialIfPresent(Constants.ACCESS_TOKEN_HEADER_KEY, compatible);
        String refresh = credentialIfPresent(Constants.REFRESH_TOKEN_HEADER_KEY, compatible);
        String front = valueIfPresent(Constants.FRONT_TOKEN_HEADER_KEY, compatible);
        String anti = valueIfPresent(Constants.CSRF_HEADER_KEY, compatible);
        if (front == null || (access == null && refresh != null)) {
            throw new IllegalArgumentException("Incoherent session response");
        }
        if (access == null && refresh == null && anti == null && !has(Constants.FRONT_TOKEN_HEADER_KEY)) {
            return null;
        }
        return new Candidate(new SessionTokens(access, refresh, front, anti), false,
                access != null && refresh != null, access != null, has(Constants.REFRESH_TOKEN_HEADER_KEY));
    }

    Candidate refreshCandidate(SessionSnapshot captured) {
        if (isRemoval()) {
            throw new IllegalArgumentException("Refresh success removed the session");
        }
        String access = singleIfPresent(Constants.ACCESS_TOKEN_HEADER_KEY);
        String refresh = singleIfPresent(Constants.REFRESH_TOKEN_HEADER_KEY);
        String front = singleIfPresent(Constants.FRONT_TOKEN_HEADER_KEY);
        String anti = singleIfPresent(Constants.CSRF_HEADER_KEY);
        if (front == null) {
            throw new IllegalArgumentException("Refresh success has no front token");
        }
        SessionTokens old = captured.getInternalTokens();
        String retainedRefresh = old == null ? null : old.getRefreshToken();
        if (refresh == null) {
            refresh = retainedRefresh;
        }
        return new Candidate(new SessionTokens(access, refresh, front, anti), false,
                access != null && refresh != null, access != null,
                has(Constants.REFRESH_TOKEN_HEADER_KEY));
    }

    long retryAfterMillis(long now) {
        try {
            String value = singleIfPresent("Retry-After");
            if (value == null) return 0;
            try {
                long seconds = Long.parseLong(value);
                if (seconds <= 0) return 0;
                return Math.min(seconds, 86400L) * 1000L;
            } catch (NumberFormatException ignored) {
                SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                format.setLenient(false);
                Date date = format.parse(value);
                return Math.min(86400000L, Math.max(0, date.getTime() - now));
            }
        } catch (IllegalArgumentException | ParseException ignored) {
            return 0;
        }
    }

    private List<String> values(String name) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                result.addAll(entry.getValue());
            }
        }
        return result;
    }

    @Nullable
    private String singleIfPresent(String name) {
        return has(name) ? single(name) : null;
    }

    @Nullable
    private String credentialIfPresent(String name) {
        return credentialIfPresent(name, false);
    }

    @Nullable
    private String credentialIfPresent(String name, boolean compatible) {
        if (!has(name)) return null;
        List<String> values = values(name);
        if ("".equals(values.get(values.size() - 1)) && (compatible || values.size() == 1)) return null;
        return valueIfPresent(name, compatible);
    }

    @Nullable
    private String valueIfPresent(String name, boolean compatible) {
        if (!has(name)) return null;
        if (!compatible) return single(name);
        List<String> values = values(name);
        String value = values.get(values.size() - 1);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty session header: " + name);
        }
        return value;
    }

    @Nullable
    private String optionalEmpty(String name) {
        if (!has(name)) return null;
        List<String> values = values(name);
        if (values.size() != 1) {
            throw new IllegalArgumentException("Duplicate session header: " + name);
        }
        String value = values.get(0);
        if (value == null || value.isEmpty()) return null;
        throw new IllegalArgumentException("Removal contains a credential");
    }

    static final class Candidate {
        final SessionTokens tokens;
        final boolean removal;
        final boolean completeHeader;
        final boolean hasAccessAndFront;
        final boolean rotatesRefresh;

        private Candidate(SessionTokens tokens, boolean removal, boolean completeHeader,
                          boolean hasAccessAndFront, boolean rotatesRefresh) {
            this.tokens = tokens;
            this.removal = removal;
            this.completeHeader = completeHeader;
            this.hasAccessAndFront = hasAccessAndFront;
            this.rotatesRefresh = rotatesRefresh;
        }

        static Candidate removal() {
            return new Candidate(null, true, false, false, false);
        }
    }
}
