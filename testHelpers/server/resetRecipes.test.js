const assert = require("assert");

process.env.TEST_MODE = "testing";
const SuperTokens = require("supertokens-node");
const Session = require("supertokens-node/recipe/session");
const SessionRecipeRaw = require("supertokens-node/lib/build/recipe/session/recipe").default;
const resetRecipes = require("./resetRecipes");

function initialise(antiCsrf) {
    SuperTokens.init({
        appInfo: {
            appName: "test",
            apiDomain: "http://localhost:8080",
            websiteDomain: "http://localhost:8080"
        },
        supertokens: { connectionURI: "http://localhost:9000" },
        recipeList: [Session.init({ antiCsrf })]
    });
}

initialise("VIA_TOKEN");
for (const antiCsrf of ["NONE", "VIA_TOKEN", "NONE"]) {
    const previousSessionRecipe = SessionRecipeRaw.getInstanceOrThrowError();
    resetRecipes();
    initialise(antiCsrf);
    assert.ok(SuperTokens.getAllCORSHeaders().length > 0);
    assert.notStrictEqual(SessionRecipeRaw.getInstanceOrThrowError(), previousSessionRecipe);
}
resetRecipes();
console.log("Repeated SDK reinitialisation passed");
