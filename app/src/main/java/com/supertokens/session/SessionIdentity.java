package com.supertokens.session;

import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.Charset;
import java.math.BigDecimal;

final class SessionIdentity {
    @Nullable
    final String sessionHandle;
    final String userId;
    final String tenantId;
    final long accessTokenExpiry;
    final JSONObject frontToken;

    private SessionIdentity(@Nullable String sessionHandle, String userId, String tenantId,
                            long accessTokenExpiry, JSONObject frontToken) {
        this.sessionHandle = sessionHandle;
        this.userId = userId;
        this.tenantId = tenantId;
        this.accessTokenExpiry = accessTokenExpiry;
        this.frontToken = frontToken;
    }

    static SessionIdentity parse(SessionTokens tokens, long now) throws IllegalArgumentException {
        return parse(tokens, now, true);
    }

    static SessionIdentity parse(SessionTokens tokens, long now, boolean strictHeader)
            throws IllegalArgumentException {
        try {
            if (tokens == null || isBlank(tokens.getFrontToken())) {
                throw new IllegalArgumentException("Front token is required");
            }
            JSONObject front = SessionTokens.decodeFrontToken(tokens.getFrontToken());
            String frontUser = requiredNonBlank(front, "uid");
            long frontExpiry = integer(front, "ate");
            if (frontExpiry <= now) {
                throw new IllegalArgumentException("Session is expired");
            }
            JSONObject frontPayload = front.getJSONObject("up");

            String handle = null;
            String tenant = null;
            String accessUser = null;
            Long accessExpiry = null;

            String access = tokens.getAccessToken();
            if (strictHeader && isBlank(access)) {
                throw new IllegalArgumentException("Access token is required");
            }
            if (!isBlank(access) && access.indexOf('.') >= 0) {
                JSONObject accessPayload = decodeAccessPayload(access);
                JSONObject header = decodePart(access.split("\\.", -1)[0]);
                String version = header.has("version") ? requiredNonBlank(header, "version") : "5";
                if (!header.has("version")) {
                    requiredNonBlank(header, "kid");
                    if (!"JWT".equals(header.optString("typ")) || !"RS256".equals(header.optString("alg"))) {
                        throw new IllegalArgumentException("Unsupported JWT header");
                    }
                }
                if (!version.matches("[1-5]")) {
                    throw new IllegalArgumentException("Unsupported access token version");
                }
                int tokenVersion = Integer.parseInt(version);
                // Legacy front.up is application userData, not session identity.
                if (tokenVersion >= 3) {
                    handle = optionalNonBlank(frontPayload, "sessionHandle");
                }
                if (tokenVersion >= 4) {
                    tenant = optionalNonBlank(frontPayload, "tId");
                }
                String accessHandle = optionalNonBlank(accessPayload, "sessionHandle");
                if (accessHandle == null) {
                    throw new IllegalArgumentException("Access token has no session handle");
                }
                if (handle != null && !handle.equals(accessHandle)) {
                    throw new IllegalArgumentException("Access/front session handle mismatch");
                }
                handle = accessHandle;

                String accessTenant = tokenVersion < 4 ? "public"
                        : requiredNonBlank(accessPayload, "tId");
                if (tokenVersion >= 4 && tenant != null && !tenant.equals(accessTenant)) {
                    throw new IllegalArgumentException("Access/front tenant mismatch");
                }
                if (accessTenant != null) {
                    tenant = accessTenant;
                }

                accessUser = requiredNonBlank(accessPayload, tokenVersion < 3 ? "userId" : "sub");
                if (tokenVersion >= 3) {
                    accessExpiry = expiryMillis(integer(accessPayload, "exp"));
                } else {
                    accessExpiry = integer(accessPayload, "expiryTime");
                }
                String frontPrimary = tokenVersion < 3 ? null : optionalNonBlank(frontPayload, "sub");
                if (strictHeader && frontPrimary != null && !frontPrimary.equals(accessUser)) {
                    throw new IllegalArgumentException("Front payload user mismatch");
                }
                if (accessExpiry != null && accessExpiry <= now) {
                    throw new IllegalArgumentException("Access token is expired");
                }
            } else if (strictHeader || access != null) {
                throw new IllegalArgumentException("Unsupported opaque access token");
            }

            if (accessUser != null && !accessUser.equals(frontUser)) {
                throw new IllegalArgumentException("Access/front user mismatch");
            }

            if (strictHeader) {
                if (accessUser == null || !accessUser.equals(frontUser)) {
                    throw new IllegalArgumentException("Access/front user mismatch");
                }
                if (accessExpiry == null || accessExpiry != frontExpiry) {
                    throw new IllegalArgumentException("Access/front expiry mismatch");
                }
                if (handle == null) {
                    throw new IllegalArgumentException("A stable session handle is required");
                }
            }

            if (tenant == null) {
                tenant = "public";
            }
            long expiry = accessExpiry == null ? frontExpiry : Math.min(frontExpiry, accessExpiry);
            return new SessionIdentity(handle, accessUser == null ? frontUser : accessUser,
                    tenant, expiry, front);
        } catch (JSONException | ArithmeticException e) {
            throw new IllegalArgumentException("Malformed session tokens", e);
        }
    }

    boolean sameAs(SessionIdentity other) {
        return sessionHandle != null && other.sessionHandle != null
                && sessionHandle.equals(other.sessionHandle)
                && tenantId.equals(other.tenantId)
                && userId.equals(other.userId);
    }

    boolean hasDifferentHandleOrTenant(SessionIdentity other) {
        if (sessionHandle == null || other.sessionHandle == null) {
            return false;
        }
        return !sessionHandle.equals(other.sessionHandle) || !tenantId.equals(other.tenantId);
    }

    private static long expiryMillis(long seconds) {
        if (seconds <= 0 || seconds > Long.MAX_VALUE / 1000L) {
            throw new ArithmeticException("Invalid JWT expiry");
        }
        return seconds * 1000L;
    }

    private static long integer(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Number)) throw new JSONException("Invalid " + key);
        try {
            return new BigDecimal(value.toString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new JSONException("Invalid " + key);
        }
    }

    private static JSONObject decodeAccessPayload(String access) throws JSONException {
        String[] parts = access.split("\\.", -1);
        if (parts.length != 3 || isBlank(parts[0]) || isBlank(parts[1]) || isBlank(parts[2])) {
            throw new JSONException("Malformed access JWT");
        }
        return decodePart(parts[1]);
    }

    private static JSONObject decodePart(String value) throws JSONException {
        try {
            byte[] decoded = Base64.decode(value, Base64.URL_SAFE);
            return new JSONObject(new String(decoded, Charset.forName("UTF-8")));
        } catch (IllegalArgumentException e) {
            throw new JSONException("Malformed access JWT encoding");
        }
    }

    private static String requiredNonBlank(JSONObject object, String key) throws JSONException {
        Object raw = object.get(key);
        if (!(raw instanceof String)) throw new JSONException("Invalid " + key);
        String value = (String) raw;
        if (isBlank(value)) {
            throw new JSONException("Missing " + key);
        }
        return value;
    }

    @Nullable
    private static String optionalNonBlank(JSONObject object, String key) throws JSONException {
        if (!object.has(key) || object.isNull(key)) {
            return null;
        }
        return requiredNonBlank(object, key);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
