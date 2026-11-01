const assert = require("assert");
const mutations = require("./backendMutations");

async function main() {
    const original = { sub: "fixture-user", previous: "old", keep: "before" };
    const session = { getHandle: () => "fixture-handle", getAccessTokenPayload: () => original,
        getJWTPayload: () => original };
    for (const api of ["updateJWTPayload", "updateAccessTokenPayload", "mergeIntoAccessTokenPayload"]) {
        const calls = [];
        const sdk = { [api]: async (...args) => calls.push(args) };
        const payload = { keep: "after" };
        const result = await mutations.updateWithHandle(sdk, session, payload, key => key === "sub");
        assert.strictEqual(result, original, "Handle update must not pretend the request's token was updated");
        assert.deepStrictEqual(calls, [["fixture-handle", api === "mergeIntoAccessTokenPayload"
            ? { previous: null, keep: "after" } : payload]]);
        assert.deepStrictEqual(original, { sub: "fixture-user", previous: "old", keep: "before" });
    }
    for (const modern of [false, true]) {
        const calls = [];
        const owner = { revokeAllSessionsForUser: async id => { calls.push(id); return ["fixture-handle"]; } };
        assert.deepStrictEqual(await mutations.revokeAll(modern ? owner : {}, modern ? {} : owner, "fixture-user"), ["fixture-handle"]);
        assert.deepStrictEqual(calls, ["fixture-user"]);
    }
    const error = new Error("Core failure");
    await assert.rejects(mutations.updateWithHandle({ mergeIntoAccessTokenPayload: async () => { throw error; } }, session, {}, () => false), value => value === error);
    await assert.rejects(mutations.revokeAll({ revokeAllSessionsForUser: async () => { throw error; } }, {}, "fixture-user"), value => value === error);
    console.log("Backend mutation API selection, replacement semantics, unchanged request payload and error identity passed");
}

main().catch(error => { console.error(error); process.exitCode = 1; });
