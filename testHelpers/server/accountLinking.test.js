const assert = require("assert");
const qualify = require("./qualifyAccountLinking");
const capability = require("./accountLinkingCapability");
const qualification = require("./accountLinkingQualification");
const preflight = require("./accountLinkingPreflight");
const { validate } = require("./coreRuntimeIdentity");
const { spawnSync } = require("child_process");

// SDK fault seam for the qualification/cleanup algorithm; not a Core behavioural oracle.
function harness(mode) {
    const users = new Map([["unrelated", { id: "unrelated" }]]);
    const sessions = new Map();
    let counter = 0;
    let primary;
    const session = userId => ({
        getUserId: () => userId, getHandle: () => mode === "changed-handle" && userId === primary ? "different" : "fixture-session",
        getTenantId: () => mode === "changed-tenant" && userId === primary ? "different" : "public",
        getAllSessionTokensDangerously: () => ({ refreshToken: "opaque-fixture" })
    });
    const ThirdParty = {
        async manuallyCreateOrUpdateUser(tenant, provider, providerId) {
            const id = "recipe-" + ++counter;
            const recipeUserId = { getAsString: () => id };
            users.set(id, { id, loginMethods: [{ recipeUserId, thirdParty: { id: provider, userId: providerId } }] });
            if (mode === "creation-response-lost" && counter === 2) throw new Error("creation lost");
            return { status: "OK", recipeUserId };
        }
    };
    const SuperTokens = {
        async listUsersByAccountInfo(tenant, { thirdParty }) {
            return Array.from(users.values()).filter(user => user.loginMethods && user.loginMethods[0].thirdParty.userId === thirdParty.userId);
        },
        async deleteUser(id, removeAllLinkedAccounts) {
            assert.strictEqual(removeAllLinkedAccounts, false);
            assert.notStrictEqual(id, "unrelated");
            if (mode === "cleanup-failure" && id === "recipe-2") throw new Error("delete failed");
            users.delete(id);
            return { status: "OK" };
        },
        async getUser(id) { return users.get(id); }
    };
    const AccountLinking = {
        async createPrimaryUser(id) { primary = id.getAsString(); return { status: "OK", user: { id: primary } }; },
        async linkAccounts() {
            if (mode === "revoked" || mode === "link-response-lost") sessions.clear();
            if (mode === "link-response-lost") throw new Error("link lost");
            return { status: "OK", accountsAlreadyLinked: false, user: { id: primary } };
        }
    };
    const Session = {
        async createNewSessionWithoutRequestResponse(tenant, id) {
            sessions.set("fixture-session", true);
            return session(id.getAsString());
        },
        async getSessionInformation(handle) { return sessions.get(handle); },
        async refreshSessionWithoutRequestResponse() {
            if (!sessions.size) throw Object.assign(new Error("revoked"), { type: "UNAUTHORISED" });
            const refreshed = session(primary);
            sessions.set(refreshed.getHandle(), true);
            return refreshed;
        },
        async revokeSession(handle) { sessions.delete(handle); }
    };
    return { dependencies: { SuperTokens, ThirdParty, AccountLinking, Session }, users, sessions };
}

async function main() {
    const api = { createPrimaryUser() {}, linkAccounts() {} };
    assert.strictEqual(capability(false, true, api).reason, "FIXTURES_DISABLED");
    assert.strictEqual(capability(true, false, api).reason, "INCOMPATIBLE_OR_UNAVAILABLE_CORE");
    assert.strictEqual(capability(true, true, undefined).reason, "PAIR_IDENTITY_UNAVAILABLE");
    assert.strictEqual(capability(true, true, api).refreshUserIdChange, null);
    const pair = { nodeSdkVersion: "16.7.0", sdkImplementationSha256: "sdk-hash", expressRuntimeId: "express-hash", coreVersion: "8.0.0", coreSha256: "core-hash", pluginInterfaceVersion: "5.0.0", pluginInterfaceSha256: "plugin-hash", coreRuntimeId: "runtime-hash", effectiveClasspathSha256: "classpath-hash", sdkApiInventory: "[]", qualifierSha256: "qualifier-hash" };
    const evidence = { schema: 1, identity: pair, observation: {
        supported: false, reason: "LINK_REVOKED_SESSION", accountLinked: true,
        cleanupVerified: true, sessionSurvivedLink: false, refreshError: "UNAUTHORISED"
    } };
    assert.strictEqual(capability(true, true, api, pair, evidence).state, "backend_proven_unsupported");
    assert.strictEqual(qualification.requireExcluded(pair, evidence).refreshUserIdChange, false);
    assert.throws(() => qualification.requireExcluded(pair, undefined), /QUALIFICATION_REQUIRED/);
    assert.throws(() => qualification.requireExcluded(undefined, evidence), /PAIR_IDENTITY_UNAVAILABLE/);
    for (const key of Object.keys(pair)) {
        const changed = { ...pair, [key]: pair[key] + "-changed" };
        assert.throws(() => qualification.requireExcluded(changed, evidence), /QUALIFICATION_PAIR_MISMATCH/);
    }
    const supported = { ...evidence, observation: { accountLinked: true, cleanupVerified: true, supported: true,
        reason: "SAME_SESSION_UID_CHANGE_OBSERVED", sessionSurvivedLink: true, identitiesValid: true,
        uidChanged: true, uidIsPrimary: true, sameHandle: true, sameTenant: true } };
    assert.strictEqual(capability(true, true, api, pair, supported).state, "fixture_unimplemented");
    assert.strictEqual(capability(true, true, api, pair, supported).refreshUserIdChange, true);
    assert.throws(() => qualification.requireExcluded(pair, supported), /QUALIFIED_SUPPORTED_PAIR_REQUIRES_FIXTURE/);
    assert.throws(() => qualification.requireExcluded(pair, { ...evidence, observation: { ...evidence.observation, cleanupVerified: false } }), /QUALIFICATION_INCOMPLETE/);
    const missingApis = ["createPrimaryUser", "linkAccounts"];
    const oldObservation = await preflight({ sdkCdiVersions: ["2.21"], missingApis, fetchCoreVersions: async () => ["2.21"] });
    const olderPair = { ...pair, nodeSdkVersion: "older-test-sdk", sdkApiInventory: JSON.stringify(missingApis) };
    const olderEvidence = { schema: 1, identity: olderPair, observation: oldObservation };
    assert.strictEqual(qualification.requireExcluded(olderPair, olderEvidence).state, "pair_proven_unsupported");
    assert.strictEqual(capability(true, true, undefined, olderPair, olderEvidence).state, "pair_proven_unsupported");
    await assert.rejects(preflight({ sdkCdiVersions: ["2.21"], missingApis, fetchCoreVersions: async () => ["4.0"] }), /No compatible live Core/);
    await assert.rejects(preflight({ sdkCdiVersions: ["2.21"], missingApis, fetchCoreVersions: async () => { throw new Error("Core offline"); } }), /Core offline/);
    assert.throws(() => qualification.requireExcluded(olderPair, { ...olderEvidence, observation: { ...oldObservation, compatibleCoreObserved: false } }), /QUALIFICATION_INCOMPLETE/);
    const root = "/test/core";
    const started = "Thu Oct 29 15:09:40 2026";
    const runtime = { cwd: root, command: "java -Djava.security.egd=file:/dev/urandom -classpath ./core/*:./plugin-interface/* io.supertokens.Main ./ DEV host=localhost port=9000 test_mode", started, listening: true };
    validate(root, runtime, [Date.parse(started) - 1000]);
    for (const invalid of [{ cwd: "/other" }, { command: "java unrelated.Main" }, { listening: false }, { started: "unknown" }]) {
        assert.throws(() => validate(root, { ...runtime, ...invalid }, [Date.parse(started) - 1000]));
    }
    assert.throws(() => validate(root, runtime, [Date.parse(started) + 2000]), /artifacts changed/);
    const entryEnv = { ...process.env, INSTALL_PATH: "/nonexistent/android-qualification-test" };
    delete entryEnv.CORE_PROTOCOL_URL;
    const gateRun = spawnSync(process.execPath, ["accountLinking.gate.js"], { cwd: __dirname, env: entryEnv, encoding: "utf8" });
    assert.strictEqual(gateRun.status, 1);
    assert.match(gateRun.stderr, /PAIR_IDENTITY_UNAVAILABLE/);
    const npmRun = spawnSync("npm", ["run", "test:core"], { cwd: __dirname, env: entryEnv, encoding: "utf8", timeout: 15000 });
    assert.strictEqual(npmRun.status, 1);
    assert.match(npmRun.stderr, /qualification gate failed/);
    assert.ok(!npmRun.stderr.includes("Set CORE_PROTOCOL_URL"), "Live smoke must not execute after a failed gate");
    for (const mode of ["revoked", "preserved", "changed-handle", "changed-tenant", "creation-response-lost", "link-response-lost", "cleanup-failure"]) {
        const h = harness(mode);
        if (mode.includes("lost") || mode === "cleanup-failure") {
            await assert.rejects(qualify(h.dependencies), mode === "cleanup-failure" ? /cleanup failed/ : /lost/);
        } else {
            const result = await qualify(h.dependencies);
            assert.strictEqual(result.supported, mode === "preserved");
            assert.strictEqual(result.cleanupVerified, true);
            if (mode === "revoked") assert.strictEqual(result.reason, "LINK_REVOKED_SESSION");
        }
        assert.strictEqual(h.sessions.size, 0);
        assert.ok(h.users.has("unrelated"));
        assert.strictEqual(h.users.size, mode === "cleanup-failure" ? 2 : 1);
        assert.strictEqual(h.users.has("recipe-1"), false, "Cleanup must continue after another deletion fails");
    }
    console.log("Account-linking gate, identity checks and partial-failure cleanup tests passed");
}

module.exports = { harness };
if (require.main === module) main().catch(error => { console.error(error); process.exitCode = 1; });
