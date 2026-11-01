package com.supertokens.session;

import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.Charset;

/** Complete credential export. Values are validated by installSession, not this value object. */
public final class SessionTokens {
    private final String accessToken;
    private final String refreshToken;
    private final String frontToken;
    @Nullable
    private final String antiCSRF;

    public SessionTokens(String accessToken, String refreshToken, String frontToken) {
        this(accessToken, refreshToken, frontToken, null);
    }

    public SessionTokens(String accessToken, String refreshToken, String frontToken,
                         @Nullable String antiCSRF) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.frontToken = frontToken;
        this.antiCSRF = antiCSRF;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public String getFrontToken() {
        return frontToken;
    }

    @Nullable
    public String getAntiCSRF() {
        return antiCSRF;
    }

    static JSONObject decodeFrontToken(String value) throws JSONException {
        try {
            byte[] decoded = Base64.decode(value, Base64.DEFAULT);
            return new JSONObject(new String(decoded, Charset.forName("UTF-8")));
        } catch (IllegalArgumentException e) {
            throw new JSONException("Malformed front token encoding");
        }
    }

    boolean hasCompleteHeaderTokens() {
        return accessToken != null && refreshToken != null && frontToken != null;
    }

    @Override
    public String toString() {
        return "SessionTokens(<redacted>)";
    }
}
