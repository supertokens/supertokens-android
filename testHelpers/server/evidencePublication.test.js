const assert = require("assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const qualification = require("./accountLinkingQualification");

async function main() {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "android-evidence-test-"));
    const previous = process.env.ACCOUNT_LINKING_QUALIFICATION_FILE;
    process.env.ACCOUNT_LINKING_QUALIFICATION_FILE = path.join(directory, "evidence.json");
    const pair = Object.fromEntries(["nodeSdkVersion", "sdkImplementationSha256", "expressRuntimeId", "coreVersion", "coreSha256",
        "pluginInterfaceVersion", "pluginInterfaceSha256", "coreRuntimeId", "effectiveClasspathSha256", "sdkApiInventory", "qualifierSha256"]
        .map(key => [key, "test-" + key]));
    const observation = { accountLinked: true, supported: false, reason: "LINK_REVOKED_SESSION", sessionSurvivedLink: false,
        refreshError: "UNAUTHORISED", cleanupVerified: true };
    try {
        for (const failure of ["timeout", "wrong-status", "wrong-state", "wrong-identity"]) {
            qualification.saveEvidence(pair, observation);
            qualification.requireExcluded(pair, qualification.readEvidence());
            await assert.rejects(qualification.publishAndVerifyEvidence(pair, observation, "http://fixture.invalid", async () => {
                if (failure === "timeout") throw new Error("Injected HTTP timeout");
                return { status: failure === "wrong-status" ? 200 : 501, json: async () => ({
                    status: "UNSUPPORTED_TEST_CAPABILITY", capability: "refreshUserIdChange",
                    details: { reason: "LINK_REVOKED_SESSION",
                        state: failure === "wrong-state" ? "fixture_unimplemented" : "backend_proven_unsupported",
                        identity: failure === "wrong-identity" ? { ...pair, nodeSdkVersion: "changed" } : pair }
                }) };
            }, () => pair));
            assert.strictEqual(qualification.readEvidence().observation.reason, "HTTP_VERIFICATION_FAILED", failure);
            assert.throws(() => qualification.requireExcluded(pair, qualification.readEvidence()), /QUALIFICATION_INCOMPLETE/, failure);
        }
        await qualification.publishAndVerifyEvidence(pair, observation, "http://fixture.invalid", async () => ({ status: 501,
            json: async () => ({ status: "UNSUPPORTED_TEST_CAPABILITY", capability: "refreshUserIdChange",
                details: { reason: "LINK_REVOKED_SESSION", state: "backend_proven_unsupported", identity: pair } }) }), () => pair);
        qualification.requireExcluded(pair, qualification.readEvidence());
        console.log("Seeded valid evidence becomes failing after HTTP timeout/status/state/identity errors; verified success remains consumable");
    } finally {
        if (previous === undefined) delete process.env.ACCOUNT_LINKING_QUALIFICATION_FILE;
        else process.env.ACCOUNT_LINKING_QUALIFICATION_FILE = previous;
        fs.rmSync(directory, { recursive: true, force: true });
    }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
