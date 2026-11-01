const assert = require("assert");
const adapter = require("./ownerSessionState");

async function main() {
    let calls = 0;
    let active = true;
    let failure = false;
    const owners = { ownsSession: (id, handle) => id === "owned" && handle === "handle" };
    const Session = { async getSessionInformation(handle) {
        calls++;
        assert.strictEqual(handle, "handle");
        if (failure) throw new Error("Core unavailable");
        return active ? { sessionHandle: handle } : undefined;
    } };
    async function request(body, enabled = true) {
        const result = { code: 200 };
        const response = { status(code) { result.code = code; return this; }, json(value) { result.body = value; } };
        await adapter(owners, Session, enabled)({ body }, response, error => { result.error = error; });
        return result;
    }
    assert.strictEqual((await request({ fixtureId: "other", handle: "handle" })).code, 404);
    assert.strictEqual((await request({ fixtureId: "owned", handle: "other" })).code, 404);
    assert.strictEqual((await request({ fixtureId: "owned", handle: "handle" }, false)).code, 501);
    assert.strictEqual(calls, 0, "unknown fixtures must never reach Core");
    assert.deepStrictEqual((await request({ fixtureId: "owned", handle: "handle" })).body, { owned: true, active: true });
    active = false;
    assert.deepStrictEqual((await request({ fixtureId: "owned", handle: "handle" })).body, { owned: true, active: false });
    failure = true;
    const failed = await request({ fixtureId: "owned", handle: "handle" });
    assert.strictEqual(failed.error.message, "Core unavailable");
    assert.strictEqual(failed.body, undefined, "lookup failure must not imply revocation");
    console.log("Scoped session-state adapter: active, revoked, unknown, disabled and failed lookup passed");
}

main().catch(error => { console.error(error); process.exitCode = 1; });
