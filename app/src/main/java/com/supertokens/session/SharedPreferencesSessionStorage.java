package com.supertokens.session;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
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

            String access = stringValue(all, ACCESS);
            String refresh = stringValue(all, REFRESH);
            String front = stringValue(all, Constants.FRONT_TOKEN_PREFS_KEY);
            String antiCSRF = stringValue(all, Constants.CSRF_TOKEN_PREFS_KEY);
            String lastUpdate = stringValue(all, LAST_UPDATE);
            SessionTokens tokens = access == null && refresh == null && front == null && antiCSRF == null
                    ? null : new SessionTokens(access, refresh, front, antiCSRF);

            if (schema == 0) {
                SessionSnapshot.Status status = front != null && lastUpdate != null
                        ? SessionSnapshot.Status.AVAILABLE : SessionSnapshot.Status.ABSENT;
                return new Record(0, status, 0, 0, 0, "", null, null,
                        tokens, null, null, null, 0, lastUpdate);
            }

            SessionSnapshot.Status status = parseStatus(required(all, STATUS));
            long revision = parsePositiveLong(required(all, REVISION), "revision");
            long accessRevision = parsePositiveLong(required(all, ACCESS_REVISION), "access revision");
            long refreshRevision = parsePositiveLong(required(all, REFRESH_REVISION), "refresh revision");
            String configuration = required(all, CONFIGURATION);
            String adoption = required(all, ADOPTION);
            String correlation = stringValue(all, CORRELATION);
            String handle = stringValue(all, HANDLE);
            String user = stringValue(all, USER);
            String tenant = stringValue(all, TENANT);
            long expiry = parseNonNegativeLong(required(all, EXPIRY), "expiry");
            return new Record(1, status, revision, accessRevision, refreshRevision,
                    configuration, adoption, correlation, tokens, handle, user, tenant,
                    expiry, lastUpdate);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Could not read session storage", e);
        }
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

            if (!editor.commit()) {
                throw new IOException("Session storage commit was not acknowledged");
            }
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Session storage commit failed", e);
        }
    }

    static void removeSessionMetadata(Context context) throws IOException {
        SharedPreferences.Editor editor = Utils.getSharedPreferences(context).edit();
        editor.remove(SCHEMA).remove(STATUS).remove(REVISION).remove(ACCESS_REVISION)
                .remove(REFRESH_REVISION).remove(CONFIGURATION).remove(ADOPTION)
                .remove(CORRELATION).remove(HANDLE).remove(USER).remove(TENANT).remove(EXPIRY);
        if (!editor.commit()) {
            throw new IOException("Could not invalidate legacy session metadata");
        }
    }

    private static boolean hasVersionedMetadata(Map<String, ?> all) {
        String[] keys = {SCHEMA, STATUS, REVISION, ACCESS_REVISION, REFRESH_REVISION,
                CONFIGURATION, ADOPTION, CORRELATION, HANDLE, USER, TENANT, EXPIRY};
        for (String key : keys) if (all.containsKey(key)) return true;
        return false;
    }

    private boolean hasKnownValue(Map<String, ?> all) {
        String[] keys = new String[]{ACCESS, REFRESH, Constants.FRONT_TOKEN_PREFS_KEY,
                Constants.CSRF_TOKEN_PREFS_KEY, LAST_UPDATE, SCHEMA, STATUS, REVISION,
                ACCESS_REVISION, REFRESH_REVISION, CONFIGURATION, ADOPTION, CORRELATION,
                HANDLE, USER, TENANT, EXPIRY};
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
