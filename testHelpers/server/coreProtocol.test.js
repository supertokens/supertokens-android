const assert = require("assert");
const express = require("express");
const fetch = require("node-fetch");

// Default test runs must not enable fixture creation merely because Core is reachable.
delete process.env.CORE_PROTOCOL_TESTS;
const protocol = require("./coreProtocol");

async function main() {
    const app = express();
    app.use(express.json());
    protocol.install(app);
    const server = app.listen(0, "127.0.0.1");
    await new Promise(resolve => server.on("listening", resolve));
    const base = "http://127.0.0.1:" + server.address().port;
    try {
        const capabilities = await (await fetch(base + "/test/capabilities")).json();
        assert.strictEqual(capabilities.enabled, false);
        assert.strictEqual(capabilities.mappedGoogleSession, false);
        assert.strictEqual(capabilities.nonPublicTenantSession, false);
        assert.strictEqual(capabilities.qualificationProxy, false);
        assert.strictEqual((await fetch(base + '/test/payload-promotion')).status, 501);
        assert.strictEqual(capabilities.accountLinking.refreshUserIdChange, null);
        const linking = await fetch(base + "/link-accounts", {
            method: "POST", headers: { "content-type": "application/json" }, body: '{"primaryUserId":"not-a-fixture"}'
        });
        assert.strictEqual(linking.status, 503);
        const excluded = await linking.json();
        assert.strictEqual(excluded.capability, "refreshUserIdChange");
        assert.strictEqual(excluded.details.reason, "FIXTURES_DISABLED");
        for (const path of ["/login-tenant", "/test/email-change-owner", "/test/revoke-session", "/test/cleanup-owner"]) {
            const response = await fetch(base + path, {
                method: "POST", headers: { "content-type": "application/json" }, body: "{}"
            });
            assert.strictEqual(response.status, 501);
            assert.strictEqual((await response.json()).status, "UNSUPPORTED_TEST_CAPABILITY");
        }
    } finally {
        await new Promise(resolve => server.close(resolve));
    }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
