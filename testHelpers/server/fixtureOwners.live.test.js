const assert = require("assert");
const crypto = require("crypto");
const SuperTokens = require("supertokens-node");
const Session = require("supertokens-node/recipe/session");
const ThirdParty = require("supertokens-node/recipe/thirdparty");
const fixtureOwners = require("./fixtureOwners");

if (!process.env.CORE_CONNECTION_URI) throw new Error("Set CORE_CONNECTION_URI to a disposable live test Core");
SuperTokens.init({
    appInfo: { appName: "Android fixture cleanup check", apiDomain: "http://localhost:8080", websiteDomain: "http://localhost:8080" },
    supertokens: { connectionURI: process.env.CORE_CONNECTION_URI },
    recipeList: [Session.init({ antiCsrf: "NONE" }), ThirdParty.init()]
});

async function main() {
    for (const scenario of ["success", "session-failure", "mapping-response-lost", "creation-response-lost"]) {
        const email = "cleanup-" + crypto.randomBytes(16).toString("hex") + "@example.com";
        const api = scenario === "mapping-response-lost" ? {
            ...SuperTokens,
            createUserIdMapping: async input => {
                await SuperTokens.createUserIdMapping(input);
                throw new Error("Injected lost mapping response after real Core commit");
            }
        } : SuperTokens;
        const recipe = scenario === "creation-response-lost" ? {
            ...ThirdParty,
            manuallyCreateOrUpdateUser: async (...args) => {
                await ThirdParty.manuallyCreateOrUpdateUser(...args);
                throw new Error("Injected lost creation response after real Core commit");
            }
        } : ThirdParty;
        const owners = fixtureOwners(api, recipe);
        try {
            const create = () => owners.create(email, async id => {
                if (scenario === "session-failure") throw new Error("Injected session failure");
                return Session.createNewSessionWithoutRequestResponse("public", SuperTokens.convertToRecipeUserId(id));
            });
            if (scenario === "success") {
                const { owner, session } = await create();
                assert.strictEqual(await owners.cleanupByHandle(session.getHandle()), true);
                assert.strictEqual(await SuperTokens.getUser(owner.recipeUserId), undefined);
                assert.strictEqual((await SuperTokens.getUserIdMapping({ userId: owner.id, userIdType: "EXTERNAL" })).status, "UNKNOWN_MAPPING_ERROR");
                assert.strictEqual(await Session.getSessionInformation(session.getHandle()), undefined);
            } else {
                await assert.rejects(create(), /Injected/);
            }
            assert.strictEqual((await SuperTokens.listUsersByAccountInfo("public", { email })).length, 0);
        } finally {
            await owners.cleanupAll();
        }
    }
    console.log("Real Core user, mapping and session cleanup passed, including partial creation failures");
}

main().catch(error => { console.error(error); process.exitCode = 1; });
