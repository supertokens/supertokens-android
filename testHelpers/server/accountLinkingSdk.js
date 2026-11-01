function optional(name) {
    try { require.resolve(name); } catch (error) {
        if (error.code === "MODULE_NOT_FOUND") return undefined;
        throw error;
    }
    return require(name);
}

function inspect() {
    const api = optional("supertokens-node/recipe/accountlinking");
    const missing = ["createPrimaryUser", "linkAccounts"].filter(name => !api || typeof api[name] !== "function");
    return { api, missing, inventory: JSON.stringify(missing) };
}

module.exports = { optional, inspect };
