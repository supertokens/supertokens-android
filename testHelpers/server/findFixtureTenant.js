module.exports = async function findFixtureTenant(Multitenancy, tenantId) {
    // Core 8 returns HTTP 400 for getTenant(missing), not the SDK's expected
    // TENANT_NOT_FOUND_ERROR. Only a successful, complete list establishes absence.
    const result = await Multitenancy.listAllTenants();
    if (!result || result.status !== "OK" || !Array.isArray(result.tenants) ||
        result.tenants.some(tenant => !tenant || typeof tenant.tenantId !== "string" || !tenant.tenantId)) {
        throw new Error("Invalid fixture tenant-list response");
    }
    return result.tenants.find(tenant => tenant.tenantId === tenantId);
};
