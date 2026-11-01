package com.supertokens.upgrade;

import java.util.List;
import java.util.Map;

/** Uses only Java APIs so the same evidence contract can be checked on the host. */
public final class ExposureEvidence {
    public static boolean isCookieReplay(Map<String, List<String>> headers) {
        return headers.size() == 1 && "Set-Cookie".equalsIgnoreCase(headers.keySet().iterator().next());
    }

    public static void refresh(Map<String, List<String>> headers, String sdkAccess) {
        require(headers != null, "Missing actual refresh response headers");
        String access = null;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            require(!"st-refresh-token".equalsIgnoreCase(entry.getKey()), "Refresh header must be absent");
            if ("st-access-token".equalsIgnoreCase(entry.getKey()) && entry.getValue() != null && entry.getValue().size() == 1) {
                access = entry.getValue().get(0);
            }
        }
        require(access != null && !access.isEmpty(), "Missing exposed refresh access header");
        require(access.equals(sdkAccess), "Refresh header differs from SDK access");
    }

    public static void handoff(String savedDigest, String actualDigest) {
        require(savedDigest != null && !savedDigest.isEmpty(), "Missing exposed access handoff digest");
        require(savedDigest.equals(actualDigest), "Exposed access changed across handoff");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
