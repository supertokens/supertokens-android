const qualification = require("./accountLinkingQualification");

module.exports = function accountLinkingCapability(enabled, compatible, api, pair, evidence) {
    const sdkApiAvailable = api !== undefined && typeof api.createPrimaryUser === "function" && typeof api.linkAccounts === "function";
    const reason = !enabled ? "FIXTURES_DISABLED" : !compatible ? "INCOMPATIBLE_OR_UNAVAILABLE_CORE" : undefined;
    return { sdkApiAvailable, identity: pair || null,
        ...(reason ? { state: "unqualified", refreshUserIdChange: null, fixtureAvailable: false, reason } : qualification.classify(pair, evidence)) };
};
