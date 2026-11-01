module.exports = async function preflight({ sdkCdiVersions, missingApis, fetchCoreVersions }) {
    const coreVersions = await fetchCoreVersions();
    if (!Array.isArray(coreVersions) || !sdkCdiVersions.some(version => coreVersions.includes(version))) {
        throw new Error("No compatible live Core CDI observed; this is not a supported exclusion");
    }
    if (missingApis.length) {
        return { supported: false, reason: "SDK_ACCOUNT_LINKING_APIS_UNAVAILABLE", compatibleCoreObserved: true,
            missingApis, noFixturesCreated: true, cleanupVerified: true };
    }
    return undefined;
};
