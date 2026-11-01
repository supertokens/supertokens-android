const assert = require("assert");
const crypto = require("crypto");
const fetch = require("node-fetch");

const base = process.env.CORE_PROTOCOL_URL;
if (!base) throw new Error("Set CORE_PROTOCOL_URL to a running live-Core test server");

async function request(path, body, token) {
    return fetch(base + path, {
        method: body === undefined ? "GET" : "POST",
        headers: {
            "content-type": "application/json", "st-auth-mode": "header", rid: "session",
            ...(token ? { authorization: "Bearer " + token } : {})
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        timeout: 10000
    });
}

async function main() {
    const capabilities = await (await request("/test/capabilities")).json();
    assert.strictEqual(capabilities.mappedGoogleSession, true, "Mapped Google fixture unavailable: " + JSON.stringify(capabilities));
    assert.strictEqual(capabilities.qualificationProxy, false);
    assert.strictEqual((await request("/test/email-change-owner", { recipe: "unsupported" })).status, 400);
    assert.strictEqual((await request("/test/protected")).status, 401);
    const login = await request("/test/email-change-owner", {
        email: "android-live-" + crypto.randomBytes(16).toString("hex") + "@example.com", recipe: "google"
    });
    assert.strictEqual(login.status, 200, "Live fixture creation failed");
    const owner = await login.json();
    try {
        assert.strictEqual(owner.google, true);
        assert.ok(owner.userId.startsWith("android-test-"));
        assert.strictEqual(owner.refreshToken, login.headers.get("st-refresh-token"));
        assert.ok(login.headers.get("st-access-token"));
        assert.ok(login.headers.get("front-token"));
        const refreshed = await request("/auth/session/refresh", {}, owner.refreshToken);
        assert.strictEqual(refreshed.status, 200);
        const access = refreshed.headers.get("st-access-token");
        const refresh = refreshed.headers.get("st-refresh-token");
        assert.ok(access);
        assert.ok(refresh);
        assert.notStrictEqual(refresh, owner.refreshToken);
        const protectedResponse = await request("/test/protected", undefined, access);
        assert.strictEqual(protectedResponse.status, 200);
        assert.strictEqual((await protectedResponse.json()).userId, owner.userId);
        assert.ok(protectedResponse.headers.get("st-access-token"), "Core child-access promotion required");
        assert.ok(protectedResponse.headers.get("front-token"));
        assert.strictEqual(protectedResponse.headers.get("st-refresh-token"), null);
        const revoked = await request("/test/revoke-session", { handle: owner.handle });
        assert.strictEqual(revoked.status, 200);
        const cleanup = await revoked.json();
        assert.strictEqual(cleanup.revoked, true);
        assert.strictEqual(cleanup.ownerCleaned, true);
        assert.strictEqual((await request("/auth/session/refresh", {}, refresh)).status, 401);
        console.log("Live Core mapped Google session, refresh, promotion and revocation passed");
    } finally {
        const cleanup = await request("/test/cleanup-owner", { fixtureId: owner.fixtureId });
        assert.strictEqual(cleanup.status, 200, "Fixture owner cleanup failed");
    }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
