const assert = require("assert");
const find = require("./findFixtureTenant");

async function main() {
    const tenant = { tenantId: "owned" };
    const sdk = { listAllTenants: async () => ({ status: "OK", tenants: [{ tenantId: "public" }, tenant] }),
        getTenant: () => { throw new Error("Direct missing-tenant lookup is not an absence oracle"); } };
    assert.strictEqual(await find(sdk, "owned"), tenant);
    assert.strictEqual(await find(sdk, "missing"), undefined);
    for (const result of [undefined, {}, { status: "ERROR", tenants: [] }, { status: "OK" },
        { status: "OK", tenants: {} }, { status: "OK", tenants: [null] },
        { status: "OK", tenants: [{}] }, { status: "OK", tenants: [{ tenantId: "" }] }]) {
        await assert.rejects(find({ listAllTenants: async () => result }, "missing"), /Invalid fixture tenant-list/);
    }
    for (const message of ["400 AppId or tenantId not found", "400 malformed request", "401 invalid API key",
        "403 forbidden", "500 Core failure", "ETIMEDOUT", "ECONNREFUSED"]) {
        const error = new Error(message);
        await assert.rejects(find({ listAllTenants: async () => { throw error; } }, "missing"), value => value === error);
    }
    console.log("Tenant lookup: confirmed absence, presence, malformed responses and error identity passed");
}

main().catch(error => { console.error(error); process.exitCode = 1; });
