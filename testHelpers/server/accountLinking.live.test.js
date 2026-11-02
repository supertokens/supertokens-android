const assert = require("assert");
const SuperTokens = require("supertokens-node");
const Session = require("supertokens-node/recipe/session");
const qualify = require("./qualifyAccountLinking");
const fetch = require("node-fetch");
const qualification = require("./accountLinkingQualification");
const sdk = require("./accountLinkingSdk");
const preflight = require("./accountLinkingPreflight");
const { cdiSupported } = require("supertokens-node/lib/build/version");

if (!process.env.CORE_CONNECTION_URI) throw new Error("Set CORE_CONNECTION_URI to a disposable live test Core");

async function main() {
    const pair = qualification.identity(stage => console.error("Pair identity unavailable stage: " + stage));
    if (!pair) throw new Error("Exact pair identity unavailable for this Core installation and backend instance");
    const allowedCoreURLs = process.env.ST_TEST_RUN_ID
        ? ['http://127.0.0.1:' + process.env.ST_TEST_CORE_PORT]
        : ["http://localhost:9000", "http://127.0.0.1:9000"];
    if (!allowedCoreURLs.includes(process.env.CORE_CONNECTION_URI)) {
        throw new Error("Core URL differs from the instance whose runtime identity was verified");
    }
    // A failed new attempt must not leave a previous successful exclusion consumable.
    qualification.saveEvidence(pair, { accountLinked: false, cleanupVerified: false, reason: "QUALIFICATION_IN_PROGRESS" });
    const inspected = sdk.inspect();
    let observed = await preflight({ sdkCdiVersions: cdiSupported, missingApis: inspected.missing, fetchCoreVersions: async () => {
        const response = await fetch(process.env.CORE_CONNECTION_URI + "/apiversion", { timeout: 5000 });
        if (!response.ok) throw new Error("Live Core capability request failed: HTTP " + response.status);
        return (await response.json()).versions;
    } });
    if (!observed) {
        const ThirdParty = sdk.optional("supertokens-node/recipe/thirdparty");
        if (!ThirdParty || typeof ThirdParty.manuallyCreateOrUpdateUser !== "function" ||
            typeof SuperTokens.listUsersByAccountInfo !== "function" || typeof SuperTokens.deleteUser !== "function" ||
            typeof Session.createNewSessionWithoutRequestResponse !== "function" || typeof Session.refreshSessionWithoutRequestResponse !== "function") {
            throw new Error("Account-linking APIs exist but qualification fixture APIs are missing; fixture unimplemented, not a backend exclusion");
        }
        SuperTokens.init({
            appInfo: { appName: "Android linking qualification", apiDomain: process.env.ST_TEST_BACKEND_URL || "http://localhost:8080",
                websiteDomain: process.env.ST_TEST_BACKEND_URL || "http://localhost:8080" },
            supertokens: { connectionURI: process.env.CORE_CONNECTION_URI },
            recipeList: [ThirdParty.init(), Session.init({ antiCsrf: "NONE" })]
        });
        observed = await qualify({ SuperTokens, ThirdParty, AccountLinking: inspected.api, Session });
    }
    assert.deepStrictEqual(qualification.identity(), pair, "Pair changed during qualification");
    const qualified = await qualification.publishAndVerifyEvidence(pair, observed, process.env.CORE_PROTOCOL_URL);
    assert.strictEqual(observed.cleanupVerified, true);
    const label = qualified.refreshUserIdChange === true ? "SUPPORTED CAPABILITY; R7 FIXTURE UNIMPLEMENTED" : "EXCLUDED";
    console.log(label + ": " + qualified.reason + ". Exact live pair/runtime qualified; fixture cleanup verified. Not an R7 pass.");
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
