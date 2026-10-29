const SuperTokensRaw = require("supertokens-node/lib/build/supertokens").default;
const SessionRecipeRaw = require("supertokens-node/lib/build/recipe/session/recipe").default;

function optionalRecipe(path) {
    try {
        require.resolve(path);
    } catch (error) {
        if (error.code === "MODULE_NOT_FOUND") return undefined;
        throw error;
    }
    return require(path).default;
}

const optionalRecipes = [
    optionalRecipe("supertokens-node/lib/build/recipe/multitenancy/recipe"),
    optionalRecipe("supertokens-node/lib/build/recipe/usermetadata/recipe"),
    optionalRecipe("supertokens-node/lib/build/recipe/accountlinking/recipe")
];

module.exports = function resetRecipes() {
    SuperTokensRaw.reset();
    SessionRecipeRaw.reset();
    for (const recipe of optionalRecipes) {
        if (recipe !== undefined) recipe.reset();
    }
};
