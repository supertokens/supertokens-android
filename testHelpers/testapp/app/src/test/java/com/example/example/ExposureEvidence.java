package com.example.example;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Checks observed transport evidence without inferring responses from SDK callbacks. */
public final class ExposureEvidence {
    private final List<Integer> responses = new ArrayList<>();
    private final List<Boolean> authorizationAbsent = new ArrayList<>();

    public void protectedRequest(Map<String, List<String>> headers) {
        boolean absent = !has(headers, "Authorization");
        authorizationAbsent.add(absent);
        require(absent, "Cookie request leaked Authorization");
    }

    public void protectedResponse(int status) { responses.add(status); }

    public void assertRetry() {
        require(authorizationAbsent.equals(Arrays.asList(true, true)), "Expected two observed requests without Authorization");
        require(responses.equals(Arrays.asList(401, 200)), "Expected actual protected responses 401 -> 200: " + responses);
    }

    public String summary() {
        return "authorizationAbsent=" + authorizationAbsent + " protectedResponses=" + responses;
    }

    public static void refresh(Map<String, List<String>> headers, String sdkAccess) {
        require(headers != null, "Missing actual refresh response headers");
        String access = null;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if ("st-access-token".equalsIgnoreCase(entry.getKey()) && entry.getValue() != null && entry.getValue().size() == 1) {
                access = entry.getValue().get(0);
            }
        }
        require(access != null && !access.isEmpty(), "Missing exposed refresh access header");
        require(!has(headers, "st-refresh-token"), "Refresh header must be absent");
        require(access.equals(sdkAccess), "Refresh header differs from SDK access");
    }

    public static void handoff(String savedDigest, String actualDigest) {
        require(savedDigest != null && !savedDigest.isEmpty(), "Missing exposed access handoff digest");
        require(savedDigest.equals(actualDigest), "Exposed access changed across handoff");
    }

    private static boolean has(Map<String, List<String>> headers, String name) {
        for (String key : headers.keySet()) if (name.equalsIgnoreCase(key)) return true;
        return false;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
