const fetch = require("node-fetch");

async function createLegacySession(coreUrl, userId, payload) {
    const response = await fetch(`${coreUrl}/recipe/session`, {
        method: "POST",
        headers: { "content-type": "application/json", "cdi-version": "2.18", rid: "session" },
        body: JSON.stringify({ userId, enableAntiCsrf: false, userDataInJWT: payload, userDataInDatabase: {} })
    });
    if (!response.ok) {
        throw new Error(`Legacy Core session request failed with HTTP ${response.status}`);
    }
    const result = await response.json();
    if (result.status !== "OK" || !result.accessToken || !result.refreshToken ||
        !result.accessToken.token || !result.refreshToken.token || result.accessToken.expiry === undefined) {
        throw new Error("Legacy Core session request returned an invalid response");
    }
    return result;
}

module.exports = createLegacySession;
