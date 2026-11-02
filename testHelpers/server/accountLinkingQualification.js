const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const assert = require("assert");
const { runtimeIdentity, serverIdentity, effectiveArtifacts } = require("./coreRuntimeIdentity");
const sdk = require("./accountLinkingSdk");

const evidencePath = () => process.env.ACCOUNT_LINKING_QUALIFICATION_FILE || path.join(__dirname, ".account-linking-qualification.json");
const digest = file => crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
const qualifierDigest = () => crypto.createHash("sha256").update([
    "qualifyAccountLinking.js", "accountLinkingPreflight.js", "accountLinking.live.test.js",
    "accountLinkingQualification.js", "coreRuntimeIdentity.js", "accountLinkingSdk.js"
].map(name => name + ":" + digest(path.join(__dirname, name))).join("\n")).digest("hex");

function identity(onUnavailable) {
    let stage = "install_path";
    const unavailable = () => {
        try { if (onUnavailable) onUnavailable(stage); } catch (_) {}
        return undefined;
    };
    if (!process.env.INSTALL_PATH) return unavailable();
    try {
        const run = (name, fn) => { stage = name; return fn(); };
        const root = path.resolve(process.env.INSTALL_PATH);
        const versions = run("version_file", () => fs.readFileSync(path.join(root, "version.yaml"), "utf8"));
        const core = versions.match(/^core_version:\s*([\d.]+)\s*$/m);
        const plugin = versions.match(/^plugin_interface_version:\s*([\d.]+)\s*$/m);
        if (!core || !plugin) { stage = !core ? "core_version" : "plugin_version"; return unavailable(); }
        const coreJar = path.join(root, "core", "core-" + core[1] + ".jar");
        const pluginJar = path.join(root, "plugin-interface", "plugin-interface-" + plugin[1] + ".jar");
        return {
            ...run("backend_identity", () => serverIdentity(root)),
            nodeSdkVersion: run("sdk_version", () => require("supertokens-node/lib/build/version").version),
            coreVersion: core[1],
            coreSha256: run("core_artifact", () => digest(coreJar)),
            pluginInterfaceVersion: plugin[1],
            pluginInterfaceSha256: run("plugin_artifact", () => digest(pluginJar)),
            coreRuntimeId: run("core_runtime", () => runtimeIdentity(root)),
            effectiveClasspathSha256: run("effective_artifacts", () => effectiveArtifacts(root).digest),
            sdkApiInventory: run("sdk_inventory", () => sdk.inspect().inventory),
            qualifierSha256: run("qualifier_digest", () => qualifierDigest())
        };
    } catch (_) { return unavailable(); }
}

function readEvidence() {
    try { return JSON.parse(fs.readFileSync(evidencePath(), "utf8")); } catch (_) { return undefined; }
}

function classify(pair, evidence) {
    const unqualified = reason => ({ state: "unqualified", reason, refreshUserIdChange: null, fixtureAvailable: false });
    if (!pair) return unqualified("PAIR_IDENTITY_UNAVAILABLE");
    if (!evidence || evidence.schema !== 1 || !evidence.identity) return unqualified("QUALIFICATION_REQUIRED");
    const fields = ["nodeSdkVersion", "sdkImplementationSha256", "expressRuntimeId", "coreVersion", "coreSha256", "pluginInterfaceVersion", "pluginInterfaceSha256", "coreRuntimeId", "effectiveClasspathSha256", "sdkApiInventory", "qualifierSha256"];
    if (fields.some(key => typeof pair[key] !== "string" || evidence.identity[key] !== pair[key])) return unqualified("QUALIFICATION_PAIR_MISMATCH");
    const observation = evidence.observation;
    if (!observation || observation.cleanupVerified !== true) return unqualified("QUALIFICATION_INCOMPLETE");
    if (observation.reason === "SDK_ACCOUNT_LINKING_APIS_UNAVAILABLE" && observation.supported === false &&
        observation.compatibleCoreObserved === true && observation.noFixturesCreated === true &&
        Array.isArray(observation.missingApis) && observation.missingApis.length > 0 &&
        JSON.stringify(observation.missingApis) === pair.sdkApiInventory) {
        return { state: "pair_proven_unsupported", reason: observation.reason, refreshUserIdChange: false, fixtureAvailable: false };
    }
    if (observation.accountLinked !== true) return unqualified("QUALIFICATION_INCOMPLETE");
    if (observation.supported === false && observation.reason === "LINK_REVOKED_SESSION" &&
        observation.sessionSurvivedLink === false && observation.refreshError === "UNAUTHORISED") {
        return { state: "backend_proven_unsupported", reason: "LINK_REVOKED_SESSION", refreshUserIdChange: false, fixtureAvailable: false };
    }
    if (observation.supported === true && observation.reason === "SAME_SESSION_UID_CHANGE_OBSERVED" &&
        observation.sessionSurvivedLink === true && observation.identitiesValid === true &&
        observation.uidChanged === true && observation.uidIsPrimary === true && observation.sameHandle === true && observation.sameTenant === true) {
        return { state: "fixture_unimplemented", reason: "QUALIFIED_SUPPORTED_PAIR_REQUIRES_FIXTURE", refreshUserIdChange: true, fixtureAvailable: false };
    }
    return unqualified("QUALIFICATION_OUTCOME_NOT_ACCEPTED");
}

function saveEvidence(pair, result) {
    const validIdentity = value => value && [value.userId, value.handle, value.tenant].every(item => typeof item === "string" && item.length > 0);
    const identitiesValid = !!(validIdentity(result.before) && validIdentity(result.after) &&
        typeof result.primaryUserId === "string" && result.primaryUserId.length > 0);
    const observation = {
        accountLinked: result.accountLinked, supported: result.supported,
        reason: result.reason, sessionSurvivedLink: result.sessionSurvivedLink,
        compatibleCoreObserved: result.compatibleCoreObserved, missingApis: result.missingApis,
        noFixturesCreated: result.noFixturesCreated,
        refreshError: result.refreshError, cleanupVerified: result.cleanupVerified,
        identitiesValid,
        uidChanged: identitiesValid ? result.before.userId !== result.after.userId : undefined,
        uidIsPrimary: identitiesValid ? result.after.userId === result.primaryUserId : undefined,
        sameHandle: identitiesValid ? result.before.handle === result.after.handle : undefined,
        sameTenant: identitiesValid ? result.before.tenant === result.after.tenant : undefined
    };
    const evidence = { schema: 1, identity: pair, qualifiedAt: new Date().toISOString(), observation };
    const temporary = evidencePath() + "." + process.pid + ".tmp";
    fs.writeFileSync(temporary, JSON.stringify(evidence, null, 2) + "\n", { mode: 0o600 });
    fs.renameSync(temporary, evidencePath());
    return evidence;
}

function requireExcluded(pair, evidence) {
    const result = classify(pair, evidence);
    if (!["backend_proven_unsupported", "pair_proven_unsupported"].includes(result.state)) throw new Error(result.state + ": " + result.reason);
    return result;
}

function requireQualified(pair, evidence) {
    const result = classify(pair, evidence);
    if (!["backend_proven_unsupported", "pair_proven_unsupported", "fixture_unimplemented"].includes(result.state)) {
        throw new Error(result.state + ": " + result.reason);
    }
    return result;
}

async function publishAndVerifyEvidence(pair, result, url, fetchGate = require("node-fetch"), currentIdentity = identity) {
    // The HTTP gate consumes this candidate. Any failed verification must revoke it.
    try {
        assert.deepStrictEqual(currentIdentity(), pair, "Pair changed before evidence publication");
        const qualified = requireQualified(pair, saveEvidence(pair, result));
        if (!url) {
            assert.deepStrictEqual(currentIdentity(), pair, "Pair changed during evidence publication");
            return qualified;
        }
        const response = await fetchGate(url + "/link-accounts", {
            method: "POST", headers: { "content-type": "application/json" }, body: "{}", timeout: 10000
        });
        assert.strictEqual(response.status, 501);
        const gate = await response.json();
        assert.strictEqual(gate.status, qualified.refreshUserIdChange === true ? "FIXTURE_UNIMPLEMENTED" : "UNSUPPORTED_TEST_CAPABILITY");
        assert.strictEqual(gate.capability, "refreshUserIdChange");
        assert.strictEqual(gate.details.reason, qualified.reason);
        assert.strictEqual(gate.details.state, qualified.state);
        if (qualified.refreshUserIdChange === true) {
            assert.strictEqual(gate.details.refreshUserIdChange, true);
            assert.strictEqual(gate.details.fixtureAvailable, false);
        }
        assert.deepStrictEqual(gate.details.identity, pair);
        assert.deepStrictEqual(currentIdentity(), pair, "Pair changed during evidence publication");
        return qualified;
    } catch (error) {
        saveEvidence(pair, { accountLinked: false, cleanupVerified: false, reason: "HTTP_VERIFICATION_FAILED" });
        throw error;
    }
}

module.exports = { identity, readEvidence, classify, saveEvidence, requireExcluded, requireQualified, evidencePath, publishAndVerifyEvidence };
