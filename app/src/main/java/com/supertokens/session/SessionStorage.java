package com.supertokens.session;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.IOException;

/** Internal durable storage seam. Android preferences and fault-injecting adapters implement it. */
interface SessionStorage {
    @Nullable
    Record read(Context context) throws IOException;

    void write(Context context, Record record) throws IOException;

    void editLegacy(Context context, LegacyEdit edit) throws IOException;

    final class LegacyEdit {
        final String[] keys;
        final String[] values;
        final boolean invalidateMetadata;

        LegacyEdit(String[] keys, String[] values) {
            this(keys, values, false);
        }

        private LegacyEdit(String[] keys, String[] values, boolean invalidateMetadata) {
            if (keys.length != values.length) throw new IllegalArgumentException("Mismatched legacy edits");
            this.keys = keys.clone();
            this.values = values.clone();
            this.invalidateMetadata = invalidateMetadata;
        }

        boolean changesSession() {
            for (String key : keys) {
                if (key.equals(Constants.FRONT_TOKEN_PREFS_KEY) || key.equals(Constants.CSRF_TOKEN_PREFS_KEY)
                        || key.equals("st-storage-item-" + Constants.ACCESS_TOKEN_PREFS_KEY)
                        || key.equals("st-storage-item-" + Constants.REFRESH_TOKEN_PREFS_KEY)
                        || key.equals("st-storage-item-" + Constants.LAST_ACCESS_TOKEN_UPDATE_PREFS_KEY)
                        || key.equals("st-storage-item-sIRTFrontend")) return true;
            }
            return false;
        }

        LegacyEdit forConfiguredSession(boolean configured) {
            return new LegacyEdit(keys, values, configured && changesSession());
        }
    }

    final class Record {
        enum Validation { STRICT, LEGACY }
        final int schema;
        final SessionSnapshot.Status status;
        final long revision;
        final long accessTokenRevision;
        final long refreshTokenRevision;
        final String configurationId;
        @Nullable
        final String adoptionId;
        @Nullable
        final String adoptionCorrelation;
        @Nullable
        final SessionTokens tokens;
        @Nullable
        final String sessionHandle;
        @Nullable
        final String userId;
        @Nullable
        final String tenantId;
        final long accessTokenExpiry;
        @Nullable
        final String lastAccessTokenUpdate;
        final Validation validation;
        final boolean evidenceMissing;

        Record(int schema, SessionSnapshot.Status status, long revision,
               long accessTokenRevision, long refreshTokenRevision, String configurationId,
               @Nullable String adoptionId, @Nullable String adoptionCorrelation,
               @Nullable SessionTokens tokens, @Nullable String sessionHandle,
               @Nullable String userId, @Nullable String tenantId, long accessTokenExpiry,
               @Nullable String lastAccessTokenUpdate) {
            this(schema, status, revision, accessTokenRevision, refreshTokenRevision, configurationId,
                    adoptionId, adoptionCorrelation, tokens, sessionHandle, userId, tenantId,
                    accessTokenExpiry, lastAccessTokenUpdate, Validation.STRICT, false);
        }

        Record(int schema, SessionSnapshot.Status status, long revision,
               long accessTokenRevision, long refreshTokenRevision, String configurationId,
               @Nullable String adoptionId, @Nullable String adoptionCorrelation,
               @Nullable SessionTokens tokens, @Nullable String sessionHandle,
               @Nullable String userId, @Nullable String tenantId, long accessTokenExpiry,
               @Nullable String lastAccessTokenUpdate, Validation validation, boolean evidenceMissing) {
            this.schema = schema;
            this.status = status;
            this.revision = revision;
            this.accessTokenRevision = accessTokenRevision;
            this.refreshTokenRevision = refreshTokenRevision;
            this.configurationId = configurationId;
            this.adoptionId = adoptionId;
            this.adoptionCorrelation = adoptionCorrelation;
            this.tokens = tokens;
            this.sessionHandle = sessionHandle;
            this.userId = userId;
            this.tenantId = tenantId;
            this.accessTokenExpiry = accessTokenExpiry;
            this.lastAccessTokenUpdate = lastAccessTokenUpdate;
            this.validation = validation;
            this.evidenceMissing = evidenceMissing;
        }
    }
}
