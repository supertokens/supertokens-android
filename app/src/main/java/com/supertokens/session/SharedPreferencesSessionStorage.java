package com.supertokens.session;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

final class SharedPreferencesSessionStorage implements SessionStorage {
    static final String SCHEMA = "supertokens-android-session-schema";
    static final String STATUS = "supertokens-android-session-status";
    static final String REVISION = "supertokens-android-session-revision";
    static final String ACCESS_REVISION = "supertokens-android-session-access-revision";
    static final String REFRESH_REVISION = "supertokens-android-session-refresh-revision";
    static final String CONFIGURATION = "supertokens-android-session-configuration";
    static final String ADOPTION = "supertokens-android-session-adoption";
    static final String CORRELATION = "supertokens-android-session-correlation";
    static final String HANDLE = "supertokens-android-session-handle";
    static final String USER = "supertokens-android-session-user";
    static final String TENANT = "supertokens-android-session-tenant";
    static final String EXPIRY = "supertokens-android-session-expiry";
    static final String VALIDATION = "supertokens-android-session-validation";
    static final String FINGERPRINT = "supertokens-android-session-fingerprint";
    static final String LEGACY_MARKER = "supertokens-android-session-legacy-marker";

    private static final String ACCESS = "st-storage-item-" + Constants.ACCESS_TOKEN_PREFS_KEY;
    private static final String REFRESH = "st-storage-item-" + Constants.REFRESH_TOKEN_PREFS_KEY;
    private static final String LAST_UPDATE = "st-storage-item-" + Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY;
    private static final String LEGACY_FRONTEND = "st-storage-item-sIRTFrontend";

    @Override
    public Record read(Context context) throws IOException {
        try {
            SharedPreferences preferences = Utils.getSharedPreferences(context);
            Map<String, ?> all = preferences.getAll();
            if (!hasKnownValue(all)) {
                return null;
            }

            String schemaValue = stringValue(all, SCHEMA);
            int schema = schemaValue == null ? 0 : parsePositiveInt(schemaValue, "schema");
            if (schema == 0 && hasVersionedMetadata(all)) {
                throw new IOException("Incomplete versioned session metadata");
            }
            if (schema != 0 && schema != 1) {
                throw new IOException("Unsupported session storage schema");
            }

            if (schema == 0) return legacyRecord(all);
            String fingerprint = stringValue(all, FINGERPRINT);
            if (fingerprint == null && (all.containsKey(VALIDATION) || all.containsKey(LEGACY_MARKER))) {
                throw new IOException("Incomplete tuple evidence");
            }
            if (fingerprint != null) {
                if (!fingerprint.matches("[0-9a-f]{64}")) throw new IOException("Invalid tuple evidence");
                // A mismatch proves change, not its author: old SDK writes and corruption are indistinguishable.
                if (!fingerprint.equals(fingerprint(all))) return legacyRecord(all);
                if (!equal(credentialValue(all, LEGACY_MARKER), credentialValue(all, LAST_UPDATE))) {
                    throw new IOException("Inconsistent legacy marker evidence");
                }
            }
            try {
                return versionedRecord(all, fingerprint == null);
            } catch (IOException invalidMetadata) {
                // Approved one-time recovery of ambiguous pre-evidence schema-1 data.
                if (fingerprint == null && hasLegacyMaterial(all)) return legacyRecord(all);
                throw invalidMetadata;
            }
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Could not read session storage", e);
        }
    }

    private static Record versionedRecord(Map<String, ?> all, boolean evidenceMissing) throws IOException {
        String access = credentialValue(all, ACCESS);
        String refresh = credentialValue(all, REFRESH);
        String front = credentialValue(all, Constants.FRONT_TOKEN_PREFS_KEY);
        String antiCSRF = credentialValue(all, Constants.CSRF_TOKEN_PREFS_KEY);
        SessionTokens tokens = access == null && refresh == null && front == null && antiCSRF == null
                ? null : new SessionTokens(access, refresh, front, antiCSRF);
        Record.Validation validation = Record.Validation.STRICT;
        if (!evidenceMissing) {
            String value = required(all, VALIDATION);
            if ("legacy".equals(value)) validation = Record.Validation.LEGACY;
            else if (!"strict".equals(value)) throw new IOException("Invalid validation provenance");
        }
        long expiry = validation == Record.Validation.LEGACY ? parseLong(required(all, EXPIRY), "expiry")
                : parseNonNegativeLong(required(all, EXPIRY), "expiry");
        return new Record(1, parseStatus(required(all, STATUS)), parsePositiveLong(required(all, REVISION), "revision"),
                parsePositiveLong(required(all, ACCESS_REVISION), "access revision"),
                parsePositiveLong(required(all, REFRESH_REVISION), "refresh revision"), required(all, CONFIGURATION),
                required(all, ADOPTION), stringValue(all, CORRELATION), tokens, stringValue(all, HANDLE),
                stringValue(all, USER), stringValue(all, TENANT), expiry, credentialValue(all, LAST_UPDATE),
                validation, evidenceMissing);
    }

    @Override
    public void write(Context context, Record record) throws IOException {
        if (record.revision <= 0 || record.revision == Long.MAX_VALUE
                || record.accessTokenRevision <= 0 || record.refreshTokenRevision <= 0
                || record.accessTokenRevision > record.revision
                || record.refreshTokenRevision > record.revision) {
            throw new IOException("Invalid or exhausted session revision");
        }
        try {
            SharedPreferences.Editor editor = Utils.getSharedPreferences(context).edit();
            putOrRemove(editor, ACCESS, value(record.tokens, TokenPart.ACCESS));
            putOrRemove(editor, REFRESH, value(record.tokens, TokenPart.REFRESH));
            putOrRemove(editor, Constants.FRONT_TOKEN_PREFS_KEY, value(record.tokens, TokenPart.FRONT));
            putOrRemove(editor, Constants.CSRF_TOKEN_PREFS_KEY, value(record.tokens, TokenPart.ANTI_CSRF));
            putOrRemove(editor, LAST_UPDATE, record.lastAccessTokenUpdate);
            editor.remove(LEGACY_FRONTEND);

            editor.putString(SCHEMA, "1");
            editor.putString(STATUS, statusValue(record.status));
            editor.putString(REVISION, Long.toString(record.revision));
            editor.putString(ACCESS_REVISION, Long.toString(record.accessTokenRevision));
            editor.putString(REFRESH_REVISION, Long.toString(record.refreshTokenRevision));
            editor.putString(CONFIGURATION, record.configurationId);
            editor.putString(ADOPTION, record.adoptionId);
            putOrRemove(editor, CORRELATION, record.adoptionCorrelation);
            putOrRemove(editor, HANDLE, record.sessionHandle);
            putOrRemove(editor, USER, record.userId);
            putOrRemove(editor, TENANT, record.tenantId);
            editor.putString(EXPIRY, Long.toString(record.accessTokenExpiry));
            editor.putString(VALIDATION, record.validation == Record.Validation.LEGACY ? "legacy" : "strict");
            putOrRemove(editor, LEGACY_MARKER, record.lastAccessTokenUpdate);
            Map<String, Object> tuple = new HashMap<>();
            tuple.put(ACCESS, value(record.tokens, TokenPart.ACCESS));
            tuple.put(REFRESH, value(record.tokens, TokenPart.REFRESH));
            tuple.put(Constants.FRONT_TOKEN_PREFS_KEY, value(record.tokens, TokenPart.FRONT));
            tuple.put(Constants.CSRF_TOKEN_PREFS_KEY, value(record.tokens, TokenPart.ANTI_CSRF));
            tuple.put(LAST_UPDATE, record.lastAccessTokenUpdate);
            editor.putString(FINGERPRINT, fingerprint(tuple));

            if (!editor.commit()) {
                throw new IOException("Session storage commit was not acknowledged");
            }
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Session storage commit failed", e);
        }
    }

    @Override
    public void editLegacy(Context context, LegacyEdit edit) throws IOException {
        try {
            SharedPreferences.Editor editor = Utils.getSharedPreferences(context).edit();
            for (int i = 0; i < edit.keys.length; i++) {
                if (edit.values[i] == null) editor.remove(edit.keys[i]);
                else editor.putString(edit.keys[i], edit.values[i]);
            }
            if (edit.invalidateMetadata) {
        editor.remove(SCHEMA).remove(STATUS).remove(REVISION).remove(ACCESS_REVISION)
                .remove(REFRESH_REVISION).remove(CONFIGURATION).remove(ADOPTION)
                .remove(CORRELATION).remove(HANDLE).remove(USER).remove(TENANT).remove(EXPIRY)
                .remove(VALIDATION).remove(FINGERPRINT).remove(LEGACY_MARKER);
            }
            if (!editor.commit()) throw new IOException("Legacy storage commit was not acknowledged");
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Legacy storage commit failed", e);
        }
    }

    private static boolean hasVersionedMetadata(Map<String, ?> all) {
        String[] keys = {SCHEMA, STATUS, REVISION, ACCESS_REVISION, REFRESH_REVISION,
                CONFIGURATION, ADOPTION, CORRELATION, HANDLE, USER, TENANT, EXPIRY, VALIDATION, FINGERPRINT, LEGACY_MARKER};
        for (String key : keys) if (all.containsKey(key)) return true;
        return false;
    }

    private boolean hasKnownValue(Map<String, ?> all) {
        String[] keys = new String[]{ACCESS, REFRESH, Constants.FRONT_TOKEN_PREFS_KEY,
                Constants.CSRF_TOKEN_PREFS_KEY, LAST_UPDATE, SCHEMA, STATUS, REVISION,
                ACCESS_REVISION, REFRESH_REVISION, CONFIGURATION, ADOPTION, CORRELATION,
                HANDLE, USER, TENANT, EXPIRY, VALIDATION, FINGERPRINT, LEGACY_MARKER};
        for (String key : keys) {
            if (all.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private static String required(Map<String, ?> all, String key) throws IOException {
        String value = stringValue(all, key);
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("Missing session storage field: " + key);
        }
        return value;
    }

    private static String stringValue(Map<String, ?> all, String key) throws IOException {
        Object value = all.get(key);
        if (value != null && !(value instanceof String)) {
            throw new IOException("Invalid session storage field: " + key);
        }
        return (String) value;
    }

    private static int parsePositiveInt(String value, String name) throws IOException {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid session storage " + name, e);
        }
    }

    private static long parsePositiveLong(String value, String name) throws IOException {
        long parsed = parseNonNegativeLong(value, name);
        if (parsed <= 0) {
            throw new IOException("Invalid session storage " + name);
        }
        return parsed;
    }

    private static long parseNonNegativeLong(String value, String name) throws IOException {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid session storage " + name, e);
        }
    }

    private static long parseLong(String value, String name) throws IOException {
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { throw new IOException("Invalid session storage " + name, e); }
    }

    private static boolean equal(String left, String right) { return left == null ? right == null : left.equals(right); }

    private static boolean hasLegacyMaterial(Map<String, ?> all) {
        return all.containsKey(ACCESS) || all.containsKey(REFRESH) || all.containsKey(Constants.FRONT_TOKEN_PREFS_KEY)
                || all.containsKey(Constants.CSRF_TOKEN_PREFS_KEY) || all.containsKey(LAST_UPDATE);
    }

    private static Record legacyRecord(Map<String, ?> all) {
        SessionTokens tokens = new SessionTokens(legacyString(all, ACCESS), legacyString(all, REFRESH),
                legacyString(all, Constants.FRONT_TOKEN_PREFS_KEY), legacyString(all, Constants.CSRF_TOKEN_PREFS_KEY));
        SessionSnapshot.Status status = tokens.getFrontToken() == null ? SessionSnapshot.Status.ABSENT : SessionSnapshot.Status.AVAILABLE;
        return new Record(0, status, 0, 0, 0, "", null, null,
                tokens, null, null, null, 0, legacyString(all, LAST_UPDATE), Record.Validation.LEGACY, false);
    }

    private static String legacyString(Map<String, ?> all, String key) {
        Object value = all.get(key);
        return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
    }

    private static String credentialValue(Map<String, ?> all, String key) throws IOException {
        String value = stringValue(all, key);
        return value == null || value.isEmpty() ? null : value;
    }

    private static String fingerprint(Map<String, ?> tuple) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String key : new String[]{ACCESS, REFRESH, Constants.FRONT_TOKEN_PREFS_KEY, Constants.CSRF_TOKEN_PREFS_KEY, LAST_UPDATE}) {
                Object value = tuple.get(key);
                // Match putOrRemove's canonical representation of empty strings.
                if ("".equals(value)) value = null;
                String part = value == null ? "null" : value.getClass().getName() + ":" + value;
                byte[] bytes = part.getBytes(Charset.forName("UTF-8"));
                digest.update((bytes.length + ":").getBytes(Charset.forName("UTF-8")));
                digest.update(bytes);
            }
            StringBuilder result = new StringBuilder();
            for (byte b : digest.digest()) {
                result.append("0123456789abcdef".charAt((b & 255) >>> 4));
                result.append("0123456789abcdef".charAt(b & 15));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
    }

    private static SessionSnapshot.Status parseStatus(String value) throws IOException {
        if ("available".equals(value)) return SessionSnapshot.Status.AVAILABLE;
        if ("absent".equals(value)) return SessionSnapshot.Status.ABSENT;
        if ("recovery".equals(value)) return SessionSnapshot.Status.RECOVERY_REQUIRED;
        throw new IOException("Invalid session storage status");
    }

    private static String statusValue(SessionSnapshot.Status status) {
        if (status == SessionSnapshot.Status.AVAILABLE) return "available";
        if (status == SessionSnapshot.Status.ABSENT) return "absent";
        if (status == SessionSnapshot.Status.RECOVERY_REQUIRED) return "recovery";
        throw new IllegalArgumentException("Transient status cannot be persisted");
    }

    private enum TokenPart { ACCESS, REFRESH, FRONT, ANTI_CSRF }

    private static String value(SessionTokens tokens, TokenPart part) {
        if (tokens == null) return null;
        switch (part) {
            case ACCESS: return tokens.getAccessToken();
            case REFRESH: return tokens.getRefreshToken();
            case FRONT: return tokens.getFrontToken();
            case ANTI_CSRF: return tokens.getAntiCSRF();
            default: throw new IllegalArgumentException();
        }
    }

    private static void putOrRemove(SharedPreferences.Editor editor, String key, String value) {
        if (value == null || value.isEmpty()) {
            editor.remove(key);
        } else {
            editor.putString(key, value);
        }
    }
}
