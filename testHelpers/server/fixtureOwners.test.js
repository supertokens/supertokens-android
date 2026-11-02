const assert = require("assert");
const fixtureOwners = require("./fixtureOwners");

// Fault-injecting SDK seam; these tests establish cleanup control flow, not Core parity.
function harness(version = '17.1.0') {
    const users = new Map();
    const mappings = new Map();
    const tenants = new Set();
    const tenantCalls = { create: 0, delete: 0 };
    const faults = {};
    let nextId = 0;
    const SuperTokens = {
        async createUserIdMapping({ superTokensUserId, externalUserId }) {
            if (faults.mappingBefore) throw new Error("mapping before write");
            mappings.set(superTokensUserId, externalUserId);
            if (faults.mappingAfter) throw new Error("mapping response lost");
            return { status: "OK" };
        },
        async getUserIdMapping({ userId }) {
            const entry = Array.from(mappings).find(([raw, external]) => raw === userId || external === userId);
            return entry ? { status: "OK", superTokensUserId: entry[0], externalUserId: entry[1] } : { status: "UNKNOWN_MAPPING_ERROR" };
        },
        async getUser(id) {
            return users.get(id) || users.get(Array.from(mappings).find(([, external]) => external === id)?.[0]);
        },
        async listUsersByAccountInfo(tenant, { thirdParty }) {
            return Array.from(users.values()).filter(user => user.tenant === tenant && user.loginMethods[0].thirdParty.userId === thirdParty.userId);
        },
        async deleteUser(id, removeAllLinkedAccounts) {
            assert.strictEqual(removeAllLinkedAccounts, false);
            if (faults.deleteUser) throw new Error("delete unavailable");
            const user = await this.getUser(id);
            if (user) users.delete(user.id);
            return { status: "OK" };
        },
        async deleteUserIdMapping({ userId }) {
            if (faults.deleteMapping) throw new Error("mapping delete unavailable");
            const existed = mappings.delete(userId);
            if (faults.deleteMappingAfter) throw new Error("mapping deletion response lost");
            return { status: existed ? "OK" : "UNKNOWN_MAPPING_ERROR" };
        }
    };
    const ThirdParty = {
        async manuallyCreateOrUpdateUser(tenant, provider, external) {
            const id = "recipe-" + ++nextId;
            const recipeUserId = { getAsString: () => id };
            const user = { id, tenant, loginMethods: [{ recipeId: "thirdparty", recipeUserId, thirdParty: { id: provider, userId: external } }] };
            users.set(id, user);
            if (faults.creationAfter) throw new Error("creation response lost");
            return { status: "OK", recipeUserId, user };
        }
    };
    const Multitenancy = {
        async getTenant(id) {
            if (tenants.has(id)) return { status: "OK" };
            throw new Error("SuperTokens core threw an error for a GET request to path: '/" + id +
                "/recipe/multitenancy/tenant' with status code: 400 and message: AppId or tenantId not found => " +
                "Tenant with the following connectionURIDomain, appId and tenantId combination not found: (, public, " + id + ")\n");
        },
        async listAllTenants() {
            if (faults.tenantList) throw faults.tenantList;
            return { status: "OK", tenants: ["public", ...tenants].map(tenantId => ({ tenantId })) };
        },
        async createOrUpdateTenant(id, config) {
            tenantCalls.create++;
            assert.deepStrictEqual(config, Number(version.split('.')[0]) < 19 ? { thirdPartyEnabled: true } : { firstFactors: ['thirdparty'] });
            const createdNew = !tenants.has(id);
            if (faults.tenantCollision) { tenants.add(id); return { status: 'OK', createdNew: false }; }
            if (faults.license) throw new Error('HTTP 402 missing multitenancy license');
            if (faults.tenantCreateDelayed) { faults.delayedTenantId = id; throw new Error('tenant response lost'); }
            tenants.add(id);
            if (faults.tenantCreateAfter) throw new Error("tenant creation response lost");
            return { status: "OK", createdNew };
        },
        async deleteTenant(id) {
            tenantCalls.delete++;
            if (faults.tenantDelete) throw new Error("tenant delete unavailable");
            return { status: "OK", didExist: tenants.delete(id) };
        }
    };
    return { owners: fixtureOwners(SuperTokens, ThirdParty, Multitenancy, version), users, mappings, tenants, tenantCalls, faults, Multitenancy };
}

async function main() {
    const collision = harness();
    const crypto = require("crypto");
    const randomBytes = crypto.randomBytes;
    const collisionId = "android-test-" + Buffer.alloc(16, 7).toString("hex");
    collision.tenants.add(collisionId);
    crypto.randomBytes = () => Buffer.alloc(16, 7);
    try {
        await assert.rejects(collision.owners.create("collision@example.com", async () => { throw new Error("must not create"); }, true), /already exists/);
    } finally { crypto.randomBytes = randomBytes; }
    assert.deepStrictEqual(collision.tenantCalls, { create: 0, delete: 0 });
    assert.strictEqual(collision.users.size, 0);
    const modern = harness('22.1.2');
    const modernOwner = await modern.owners.create('modern@example.com', async id => ({ getHandle: () => id }), true);
    await modern.owners.cleanupById(modernOwner.owner.id);
    for (const version of ['18.0.3', '19.0.2', '24.1.0']) {
        const pair = harness(version);
        const { owner } = await pair.owners.create('version@example.com', async id => ({ getHandle: () => id }), true);
        await pair.owners.cleanupById(owner.id);
    }
    const unknown = harness('25.0.0');
    await assert.rejects(unknown.owners.create('unknown@example.com', async () => {}, true), /Unsupported fixture tenant configuration/);
    assert.deepStrictEqual(unknown.tenantCalls, { create: 0, delete: 0 });
    const denied = harness('22.1.2');
    denied.faults.license = true;
    await assert.rejects(denied.owners.create('denied@example.com', async () => {}, true), /cleanup also failed/);
    await assert.rejects(denied.owners.cleanupAll(), /ambiguous/);
    assert.strictEqual(denied.tenantCalls.delete, 0);
    const race = harness('22.1.2');
    race.faults.tenantCollision = true;
    await assert.rejects(race.owners.create('race@example.com', async () => {}, true), /creation failed/);
    assert.strictEqual(race.tenantCalls.delete, 0);
    assert.strictEqual(race.tenants.size, 1);
    const ambiguousTenant = harness('22.1.2');
    ambiguousTenant.faults.tenantCreateAfter = true;
    await assert.rejects(ambiguousTenant.owners.create('ambiguous@example.com', async () => {}, true), /cleanup also failed/);
    await assert.rejects(ambiguousTenant.owners.cleanupAll(), /ambiguous/);
    assert.strictEqual(ambiguousTenant.tenantCalls.delete, 0);
    const delayed = harness('22.1.2');
    delayed.faults.tenantCreateDelayed = true;
    await assert.rejects(delayed.owners.create('delayed@example.com', async () => {}, true), /cleanup also failed/);
    await assert.rejects(delayed.owners.cleanupAll(), /ambiguous/);
    delayed.tenants.add(delayed.faults.delayedTenantId);
    await assert.rejects(delayed.owners.cleanupAll(), /ambiguous/);
    assert.strictEqual(delayed.tenants.size, 1);
    assert.strictEqual(delayed.tenantCalls.delete, 0);
    const tenant = harness();
    const createSession = async (id, tenantId) => {
        assert.notStrictEqual(tenantId, "public");
        assert.strictEqual(id, tenantId);
        return { getHandle: () => id };
    };
    const a = await tenant.owners.create("a@example.com", createSession, true);
    const b = await tenant.owners.create("b@example.com", createSession, true);
    assert.strictEqual(await tenant.owners.cleanupById("unknown"), false);
    await tenant.owners.cleanupById(a.owner.id);
    assert.deepStrictEqual(Array.from(tenant.tenants), [b.owner.tenantId]);
    assert.strictEqual(tenant.users.size, 1);
    tenant.faults.tenantDelete = true;
    await assert.rejects(tenant.owners.cleanupById(b.owner.id), /tenant delete/);
    tenant.faults.tenantDelete = false;
    assert.strictEqual(await tenant.owners.cleanupById(b.owner.id), true);
    assert.strictEqual(await tenant.owners.cleanupById(b.owner.id), true);
    assert.strictEqual(tenant.tenants.size, 0);
    for (const error of [new Error("HTTP 400 malformed request"), new Error("HTTP 401 invalid API key"),
        new Error("HTTP 403 forbidden"), new Error("HTTP 500 Core failure"), new Error("ECONNREFUSED")]) {
        const pending = harness();
        const created = await pending.owners.create("pending@example.com", createSession, true);
        pending.faults.tenantList = error;
        await assert.rejects(pending.owners.cleanupById(created.owner.id), value => value === error);
        assert.strictEqual(pending.users.size, 1);
        assert.strictEqual(pending.tenants.size, 1);
        pending.faults.tenantList = null;
        assert.strictEqual(await pending.owners.cleanupById(created.owner.id), true);
        assert.strictEqual(pending.users.size, 0);
        assert.strictEqual(pending.tenants.size, 0);
    }
    for (const fault of ["creationAfter", "mappingAfter", "session"]) {
        const failed = harness();
        failed.faults[fault] = true;
        await assert.rejects(failed.owners.create("failure@example.com", async () => { throw new Error("session failed"); }, true));
        assert.strictEqual(failed.tenants.size, 0, fault);
        assert.strictEqual(failed.users.size, 0, fault);
        assert.strictEqual(failed.mappings.size, 0, fault);
    }
    for (const fault of ["mappingBefore", "mappingAfter", "creationAfter", "session"]) {
        const h = harness();
        h.faults[fault] = true;
        await assert.rejects(h.owners.create("test@example.com", async () => { throw new Error("session failed"); }));
        assert.strictEqual(h.users.size, 0, fault + " leaked a user");
        assert.strictEqual(h.mappings.size, 0, fault + " leaked a mapping");
    }

    const h = harness();
    const { owner } = await h.owners.create("test@example.com", async () => ({ getHandle: () => "session" }));
    assert.strictEqual(await h.owners.cleanupById("unowned-user"), false);
    assert.strictEqual(h.users.size, 1);
    assert.strictEqual(await h.owners.cleanupByHandle("session"), true);
    assert.strictEqual(await h.owners.cleanupById(owner.id), true);
    assert.strictEqual(h.owners.ownsSession(owner.id, "session"), true);
    assert.strictEqual(h.owners.ownsSession(owner.id, "someone-else"), false);
    assert.strictEqual(h.owners.ownsSession("unknown", "session"), false);
    assert.strictEqual(h.users.size, 0);
    assert.strictEqual(h.mappings.size, 0);

    const lost = harness();
    const first = await lost.owners.create("first@example.com", async () => ({ getHandle: () => "first" }));
    const second = await lost.owners.create("second@example.com", async () => ({ getHandle: () => "second" }));
    lost.faults.deleteMappingAfter = true;
    await assert.rejects(lost.owners.cleanupById(first.owner.id), /response lost/);
    lost.faults.deleteMappingAfter = false;
    assert.strictEqual(await lost.owners.cleanupById(first.owner.id), true);
    assert.strictEqual(await lost.owners.cleanupById(first.owner.id), true);
    assert.strictEqual(await lost.owners.cleanupById("unknown-user"), false);
    assert.strictEqual(lost.users.size, 1, "cleanup must preserve the other fixture");
    assert.strictEqual(lost.owners.ownsSession(second.owner.id, "second"), true);

    for (const fault of ["deleteUser", "deleteMapping"]) {
        const retry = harness();
        retry.faults[fault] = true;
        await assert.rejects(retry.owners.create("test@example.com", async () => { throw new Error("session failed"); }), /cleanup also failed/);
        await assert.rejects(retry.owners.cleanupAll(), /remain pending/);
        retry.faults[fault] = false;
        await retry.owners.cleanupAll();
        assert.strictEqual(retry.users.size, 0);
        assert.strictEqual(retry.mappings.size, 0);
    }

    const abandoned = harness();
    await abandoned.owners.create("test@example.com", async () => ({ getHandle: () => "never-returned" }));
    await abandoned.owners.cleanupAll();
    assert.strictEqual(abandoned.users.size, 0);
    assert.strictEqual(abandoned.mappings.size, 0);
    console.log("Fixture owner cleanup success, partial failure, retry and abandoned-response cases passed");
}

module.exports = { harness };
if (require.main === module) main().catch(error => { console.error(error); process.exitCode = 1; });
