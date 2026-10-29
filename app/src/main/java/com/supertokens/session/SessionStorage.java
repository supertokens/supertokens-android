package com.supertokens.session;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.IOException;

/** Internal durable storage seam. Android preferences and fault-injecting adapters implement it. */
interface SessionStorage {
    @Nullable
    Record read(Context context) throws IOException;

    void write(Context context, Record record) throws IOException;

    final class Record {
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

        Record(int schema, SessionSnapshot.Status status, long revision,
               long accessTokenRevision, long refreshTokenRevision, String configurationId,
               @Nullable String adoptionId, @Nullable String adoptionCorrelation,
               @Nullable SessionTokens tokens, @Nullable String sessionHandle,
               @Nullable String userId, @Nullable String tenantId, long accessTokenExpiry,
               @Nullable String lastAccessTokenUpdate) {
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
        }
    }
}
