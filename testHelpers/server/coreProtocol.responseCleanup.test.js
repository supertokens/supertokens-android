const assert = require("assert");
const fs = require("fs");
const vm = require("vm");
const fixtureOwners = require("./fixtureOwners");

// Exercise real route and registry control flow without a backend or network calls.
function harness(faultPoint, cleanupFails) {
    const original = new Error("response construction failed");
    const cleanupError = new Error("cleanup unavailable");
    const tenants = new Set();
    let user;
    let mapping;
    let ownerId;
    let deleteCalls = 0;
    let failCleanup = cleanupFails;
    const SuperTokens = {
        convertToRecipeUserId: id => id,
        async createUserIdMapping({ externalUserId }) {
            ownerId = externalUserId;
            mapping = externalUserId;
            return { status: "OK" };
        },
        async getUserIdMapping() {
            return mapping ? { status: "OK", externalUserId: mapping } : { status: "UNKNOWN_MAPPING_ERROR" };
        },
        async getUser() { return user; },
        async listUsersByAccountInfo() { return user ? [user] : []; },
        async deleteUser() {
            deleteCalls++;
            if (failCleanup) throw cleanupError;
            user = undefined;
            return { status: "OK" };
        },
        async deleteUserIdMapping() {
            mapping = undefined;
            return { status: "OK" };
        }
    };
    const ThirdParty = {
        async manuallyCreateOrUpdateUser() {
            user = { loginMethods: [{ recipeId: "thirdparty", thirdParty: { id: "google" } }] };
            return { status: "OK", recipeUserId: { getAsString: () => "recipe-id" } };
        }
    };
    const Multitenancy = {
        async listAllTenants() { return { status: "OK", tenants: [...tenants].map(tenantId => ({ tenantId })) }; },
        async createOrUpdateTenant(id) { tenants.add(id); return { status: "OK", createdNew: true }; },
        async deleteTenant(id) { tenants.delete(id); return { status: "OK" }; }
    };
    const session = {
        getHandle: () => "handle",
        getUserId() { if (faultPoint === "userId") throw original; return ownerId; },
        getAllSessionTokensDangerously() { if (faultPoint === "tokens") throw original; return { refreshToken: "token" }; }
    };
    const dependencies = {
        "./fixtureOwners": fixtureOwners,
        "./nativeEvidence": options => require("./nativeEvidence")({ ...options, env: {} }),
        "./accountLinkingCapability": () => ({}),
        "./accountLinkingQualification": { identity: () => ({}), readEvidence: () => ({}) },
        "node-fetch": async () => ({ ok: true, json: async () => ({ versions: ["test-cdi"] }) }),
        "supertokens-node": SuperTokens,
        "supertokens-node/recipe/session": { createNewSession: async () => session },
        "supertokens-node/recipe/session/framework/express": { verifySession: () => () => {} },
        "supertokens-node/lib/build/version": { version: "test", cdiSupported: ["test-cdi"] },
        "supertokens-node/recipe/thirdparty": ThirdParty,
        "supertokens-node/recipe/accountlinking": {},
        "supertokens-node/recipe/multitenancy": Multitenancy,
        "./ownerSessionState": () => () => {}
    };
    const context = {
        module: { exports: {} }, process: { env: { CORE_PROTOCOL_TESTS: "1" } }, AggregateError,
        require(name) {
            assert.ok(Object.prototype.hasOwnProperty.call(dependencies, name), "Unexpected dependency: " + name);
            return dependencies[name];
        }
    };
    vm.runInNewContext(fs.readFileSync(__dirname + "/coreProtocol.js", "utf8"), context, { filename: "coreProtocol.js" });
    const routes = new Map();
    context.module.exports.install({ post: (path, handler) => routes.set(path, handler), get() {} });
    async function cleanup(id) {
        const responses = [];
        const errors = [];
        await routes.get("/test/cleanup-owner")({ body: { fixtureId: id } }, { json: value => responses.push(value) }, error => errors.push(error));
        return { responses, errors };
    }
    return { routes, original, cleanupError, cleanup, session,
        id: () => ownerId, retry: () => { failCleanup = false; },
        state: () => ({ user: !!user, mapping: !!mapping, tenants: tenants.size, deleteCalls }) };
}

async function main() {
    let cases = 0;
    for (const path of ["/login-tenant", "/test/email-change-owner"]) {
        const points = path === "/login-tenant" ? ["userId", "json"] : ["tokens", "userId", "json"];
        for (const point of points) {
            for (const cleanupFails of [false, true]) {
                const h = harness(point, cleanupFails);
                const originalProperties = Object.getOwnPropertyDescriptors(h.original);
                const errors = [];
                let responses = 0;
                await h.routes.get(path)({ body: { email: "fixture@example.com", recipe: "google" } }, {
                    json() { responses++; throw h.original; }
                }, error => errors.push(error));
                assert.strictEqual(responses, point === "json" ? 1 : 0);
                assert.strictEqual(errors.length, 1);
                assert.deepStrictEqual(Object.getOwnPropertyDescriptors(h.original), originalProperties);
                if (cleanupFails) {
                    assert.ok(errors[0] instanceof AggregateError);
                    assert.strictEqual(errors[0].errors.length, 2);
                    assert.strictEqual(errors[0].errors[0], h.original);
                    assert.strictEqual(errors[0].errors[1], h.cleanupError);
                    assert.deepStrictEqual(h.state(), { user: true, mapping: true, tenants: path === "/login-tenant" ? 1 : 0, deleteCalls: 1 });
                    const failedRetry = await h.cleanup(h.id());
                    assert.deepStrictEqual(failedRetry.responses, [], "failed cleanup must not acknowledge success");
                    assert.strictEqual(failedRetry.errors.length, 1);
                    assert.strictEqual(failedRetry.errors[0], h.cleanupError);
                    assert.strictEqual(h.state().deleteCalls, 2, "owner must remain pending");
                    h.retry();
                } else {
                    assert.strictEqual(errors[0], h.original);
                }
                const completed = await h.cleanup(h.id());
                assert.deepStrictEqual(completed.errors, []);
                assert.strictEqual(completed.responses.length, 1);
                assert.strictEqual(completed.responses[0].cleaned, true);
                const expectedCalls = cleanupFails ? 3 : 1;
                assert.deepStrictEqual(h.state(), { user: false, mapping: false, tenants: 0, deleteCalls: expectedCalls });
                const repeated = await h.cleanup(h.id());
                assert.strictEqual(repeated.responses[0].cleaned, true);
                assert.deepStrictEqual(repeated.errors, []);
                assert.strictEqual(h.state().deleteCalls, expectedCalls, "completed tombstone must not rerun deletion");
                const unknown = await h.cleanup("unknown-owner");
                assert.strictEqual(unknown.responses[0].cleaned, false);
                assert.deepStrictEqual(unknown.errors, []);
                cases++;
            }
        }
    }
    console.log(cases + " response cleanup fault-injection cases passed");
}

main().catch(error => { console.error(error); process.exitCode = 1; });
