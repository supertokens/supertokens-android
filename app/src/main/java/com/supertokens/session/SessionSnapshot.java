package com.supertokens.session;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

/** Immutable, defensively copied view of one durable session state. */
public final class SessionSnapshot {
    public enum Status { ABSENT, AVAILABLE, UNAVAILABLE, RECOVERY_REQUIRED }

    private final SessionOwner owner;
    private final long revision;
    private final long accessTokenRevision;
    private final long refreshTokenRevision;
    private final String configurationId;
    private final String adoptionId;
    @Nullable
    private final String adoptionCorrelation;
    private final Status status;
    @Nullable
    private final String sessionHandle;
    @Nullable
    private final String userId;
    @Nullable
    private final String tenantId;
    private final long accessTokenExpiry;
    @Nullable
    private final SessionTokens tokens;

    SessionSnapshot(SessionOwner owner, long revision, long accessTokenRevision,
                    long refreshTokenRevision, String configurationId, String adoptionId,
                    @Nullable String adoptionCorrelation, Status status,
                    @Nullable String sessionHandle, @Nullable String userId,
                    @Nullable String tenantId, long accessTokenExpiry,
                    @Nullable SessionTokens tokens) {
        this.owner = owner;
        this.revision = revision;
        this.accessTokenRevision = accessTokenRevision;
        this.refreshTokenRevision = refreshTokenRevision;
        this.configurationId = configurationId;
        this.adoptionId = adoptionId;
        this.adoptionCorrelation = adoptionCorrelation;
        this.status = status;
        this.sessionHandle = sessionHandle;
        this.userId = userId;
        this.tenantId = tenantId;
        this.accessTokenExpiry = accessTokenExpiry;
        this.tokens = tokens;
    }

    public SessionOwner getOwner() {
        return owner;
    }

    public long getRevision() {
        return revision;
    }

    public long getAccessTokenRevision() {
        return accessTokenRevision;
    }

    public long getRefreshTokenRevision() {
        return refreshTokenRevision;
    }

    public String getConfigurationId() {
        return configurationId;
    }

    public String getAdoptionId() {
        return adoptionId;
    }

    @Nullable
    public String getAdoptionCorrelation() {
        return adoptionCorrelation;
    }

    public Status getStatus() {
        return status;
    }

    /** Owner equality, not snapshot revision equality. Same-owner rotation stays current. */
    public boolean isCurrent() {
        return owner.isCurrent();
    }

    @Nullable
    public String getSessionHandle() {
        return sessionHandle;
    }

    @Nullable
    public String getUserId() {
        return userId;
    }

    @Nullable
    public String getTenantId() {
        return tenantId;
    }

    public long getAccessTokenExpiry() {
        return accessTokenExpiry;
    }

    @Nullable
    public SessionTokens getTokens() {
        if (status != Status.AVAILABLE || tokens == null || !tokens.hasCompleteHeaderTokens()) {
            return null;
        }
        return tokens;
    }

    @Nullable
    public JSONObject getFrontToken() {
        if (status != Status.AVAILABLE || tokens == null || tokens.getFrontToken() == null) {
            return null;
        }
        try {
            return SessionTokens.decodeFrontToken(tokens.getFrontToken());
        } catch (JSONException e) {
            throw new IllegalStateException("Invalid session snapshot", e);
        }
    }

    @Nullable
    public JSONObject getAccessTokenPayload() {
        JSONObject front = getFrontToken();
        if (front == null) {
            return null;
        }
        try {
            return front.getJSONObject("up");
        } catch (JSONException e) {
            throw new IllegalStateException("Invalid session snapshot", e);
        }
    }

    @Nullable
    SessionTokens getInternalTokens() {
        return tokens;
    }

    @Override
    public String toString() {
        return "SessionSnapshot(status=" + status + ", revision=" + revision
                + ", accessTokenRevision=" + accessTokenRevision
                + ", refreshTokenRevision=" + refreshTokenRevision
                + ", configurationId='" + configurationId + "')";
    }
}
