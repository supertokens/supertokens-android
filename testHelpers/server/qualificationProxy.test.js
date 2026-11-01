const assert = require("assert");
const http = require("http");
const createProxy = require("./qualificationProxy");
const PROFILE = "/auth/plugin/rownd/user", REFRESH = "/auth/session/refresh";

function deferred() {
    let resolve;
    const promise = new Promise(done => { resolve = done; });
    return { promise, resolve };
}

function call(base, path, body, headers = {}, method = body === undefined ? "GET" : "POST") {
    const bytes = body === undefined ? null : Buffer.isBuffer(body) ? body : Buffer.from(JSON.stringify(body));
    let request;
    const promise = new Promise((resolve, reject) => {
        request = http.request(base + path, { method, headers: {
            ...(bytes ? { "content-type": "application/json", "content-length": bytes.length } : {}), ...headers
        }, agent: false }, res => {
            const chunks = [];
            res.on("data", chunk => chunks.push(chunk));
            res.on("error", reject);
            res.on("end", () => resolve({ status: res.statusCode, headers: res.headers,
                body: Buffer.concat(chunks), json() { return JSON.parse(this.body.toString()); } }));
        });
        request.on("error", reject);
        request.setTimeout(3000, () => request.destroy(new Error("TEST_DEADLINE")));
        request.end(bytes);
    });
    // Callers deliberately abort held requests; attach before waiting for gate state.
    promise.catch(() => {});
    return { promise, abort() { request.destroy(new Error("TEST_ABORT")); } };
}

async function fixture(options = {}) {
    const sockets = new Set(), requests = [];
    const supported = { enabled: true, compatible: true, mappedGoogleSession: true, nodeVersion: "fixture-only",
        qualificationProxy: false, accountLinking: { state: "unqualified", refreshUserIdChange: null } };
    let behavior = null, capabilityBehavior = null, nextOwner = 0;
    const server = http.createServer((req, res) => {
        const chunks = [];
        req.on("data", chunk => chunks.push(chunk));
        req.on("end", () => {
            const item = { path: req.url, method: req.method, headers: req.headers, body: Buffer.concat(chunks), res };
            requests.push(item);
            if (req.url === "/test/capabilities") return capabilityBehavior ? capabilityBehavior(item) : res.end(JSON.stringify(supported));
            if (behavior) return behavior(item);
            if (req.url === "/test/email-change-owner") return res.end(JSON.stringify({ fixtureId: "fixture-" + ++nextOwner, handle: "handle-" + nextOwner }));
            if (req.url === "/test/cleanup-owner") return res.end('{"cleaned":true}');
            if (req.url === "/test/revoke-session") return res.end('{"revoked":true,"ownerCleaned":true}');
            res.setHeader("st-access-token", "synthetic-access-child");
            res.setHeader("front-token", "synthetic-front");
            if (req.url.split("?")[0] === REFRESH) res.setHeader("st-refresh-token", "synthetic-refresh-child");
            res.end("upstream-body");
        });
    });
    server.on("connection", socket => { sockets.add(socket); socket.on("close", () => sockets.delete(socket)); });
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    const upstream = "http://127.0.0.1:" + server.address().port;
    const proxy = createProxy({ enabled: true, target: upstream, ...options });
    await new Promise(resolve => proxy.server.listen(0, "127.0.0.1", resolve));
    const base = "http://127.0.0.1:" + proxy.server.address().port;
    return { proxy, base, upstream, requests, supported, behavior(fn) { behavior = fn; },
        capabilityBehavior(fn) { capabilityBehavior = fn; },
        async close() {
            await proxy.close();
            for (const socket of sockets) socket.destroy();
            await new Promise(resolve => server.close(resolve));
        } };
}

async function until(check) {
    const deadline = Date.now() + 2500;
    do {
        const result = await check();
        if (result) return result;
        await new Promise(resolve => setTimeout(resolve, 5));
    } while (Date.now() < deadline);
    throw new Error("Condition did not become true");
}

async function arm(f, paths = [PROFILE, REFRESH]) {
    assert.strictEqual((await call(f.base, "/__qualification/arm", { paths }).promise).status, 200);
}
async function ready(f, path) {
    return until(async () => {
        const state = (await call(f.base, "/__qualification/state").promise).json();
        return state.gates[path].responseReady && state.gates[path];
    });
}
const tests = [];
function test(name, fn) { tests.push([name, fn]); }

for (const first of [PROFILE, REFRESH]) test("completed response delivery order " + first, async f => {
    await arm(f);
    const profile = call(f.base, PROFILE, undefined, { authorization: "Bearer synthetic-access-parent" });
    const promotion = await ready(f, PROFILE);
    assert.strictEqual(promotion.claimed, true);
    assert.strictEqual(promotion.released, false);
    assert.strictEqual(promotion.delivered, false);
    assert.strictEqual(promotion.timedOut, false);
    assert.strictEqual(promotion.status, 200);
    assert.strictEqual(promotion.hasAccess, true);
    assert.strictEqual(promotion.hasFront, true);
    assert.strictEqual(promotion.hasRefresh, false);
    assert.strictEqual(promotion.accessChangedFromRequest, true);
    const refresh = call(f.base, REFRESH, {});
    assert.strictEqual((await ready(f, REFRESH)).hasRefresh, true);
    const pending = new Map([[PROFILE, profile], [REFRESH, refresh]]);
    for (const path of [first, first === PROFILE ? REFRESH : PROFILE]) {
        assert.strictEqual((await call(f.base, "/__qualification/release", { path }).promise).status, 200);
        assert.strictEqual((await pending.get(path).promise).body.toString(), "upstream-body");
    }
    await until(() => f.proxy.state().active === 0 && f.proxy.state().gates[REFRESH].delivered);
    for (const path of [PROFILE, REFRESH]) assert.deepStrictEqual(f.proxy.state().counts[path],
        { requests: 1, responses: 1, ready: 1, delivered: 1 });
    assert.deepStrictEqual(f.requests.filter(r => r.path !== "/test/capabilities").map(r => r.path), ["/test/protected", REFRESH]);
    assert(!JSON.stringify(f.proxy.state()).includes("synthetic-access"));
});

test("raw body, query, status and end-to-end headers preserved", async f => {
    const responseBytes = Buffer.from([0, 255, 10, 13, 42]);
    f.behavior(({ res }) => {
        res.writeHead(307, { "content-type": "application/octet-stream", "content-encoding": "gzip",
            "content-length": responseBytes.length, "set-cookie": ["a=x; Path=/", "b=y; Path=/"],
            "st-access-token": "actual-forwarded-value", "front-token": "actual-front",
            "connection": "close, x-hop", "x-hop": "remove-me", "location": "/unchanged" });
        res.end(responseBytes);
    });
    const body = Buffer.from(' { "opaque" : "original spacing" }\n');
    const result = await call(f.base, REFRESH + "?tenant=one", body, {
        authorization: "Bearer test-secret", cookie: "session=test-cookie", "anti-csrf": "test-csrf",
        rid: "session", "st-auth-mode": "header", connection: "close, x-private-hop", "x-private-hop": "strip"
    }).promise;
    assert.strictEqual(result.status, 307);
    assert.deepStrictEqual(result.body, responseBytes);
    assert.deepStrictEqual(result.headers["set-cookie"], ["a=x; Path=/", "b=y; Path=/"]);
    assert.strictEqual(result.headers["st-access-token"], "actual-forwarded-value");
    assert.strictEqual(result.headers["content-encoding"], "gzip");
    assert.strictEqual(result.headers["x-hop"], undefined);
    const seen = f.requests[0];
    assert.deepStrictEqual(seen.body, body);
    assert.strictEqual(seen.path, REFRESH + "?tenant=one");
    for (const name of ["authorization", "cookie", "anti-csrf", "rid", "st-auth-mode"]) assert(seen.headers[name]);
    assert.strictEqual(seen.headers["x-private-hop"], undefined);
    assert.strictEqual(seen.headers.host, new URL(f.upstream).host);
});

test("hold waits for upstream end rather than headers", async f => {
    const arrived = deferred(), complete = deferred();
    f.behavior(({ res }) => {
        res.writeHead(200, { "st-access-token": "child", "front-token": "front" });
        res.write("partial");
        arrived.resolve();
        complete.promise.then(() => res.end("-complete"));
    });
    await arm(f, [PROFILE]);
    const pending = call(f.base, PROFILE);
    await arrived.promise;
    assert.strictEqual(f.proxy.state().gates[PROFILE].responseReady, false);
    assert.strictEqual((await call(f.base, "/__qualification/release", { path: PROFILE }).promise).status, 409);
    complete.resolve();
    await ready(f, PROFILE);
    await call(f.base, "/__qualification/release", { path: PROFILE }).promise;
    assert.strictEqual((await pending.promise).body.toString(), "partial-complete");
});

test("drop consumes exactly one completed response and disarms", async f => {
    await arm(f, [REFRESH]);
    const pending = call(f.base, REFRESH, {});
    await ready(f, REFRESH);
    assert.strictEqual((await call(f.base, "/__qualification/drop", { path: REFRESH }).promise).status, 200);
    await assert.rejects(pending.promise);
    assert.strictEqual(f.proxy.state().gates[REFRESH].dropped, true);
    assert.strictEqual(f.proxy.state().gates[REFRESH].delivered, false);
    assert.strictEqual(f.proxy.state().counts[REFRESH].dropped, 1);
    assert.strictEqual((await call(f.base, REFRESH, {}).promise).status, 200);
    assert.strictEqual(f.proxy.state().counts[REFRESH].requests, 2);
});

test("client abort frees held response; late release cannot deliver", async f => {
    await arm(f, [PROFILE]);
    const pending = call(f.base, PROFILE);
    await ready(f, PROFILE);
    pending.abort();
    await assert.rejects(pending.promise);
    await until(() => f.proxy.state().gates[PROFILE].aborted);
    assert.strictEqual(f.proxy.state().active, 0);
    assert.strictEqual((await call(f.base, "/__qualification/release", { path: PROFILE }).promise).status, 409);
    assert.strictEqual(f.proxy.state().counts[PROFILE].aborted, 1);
    assert.strictEqual(f.proxy.state().counts[PROFILE].delivered, undefined);
});

test("reset cancels owned upstream; late completion cannot poison new gates", async f => {
    const arrived = deferred(), disconnected = deferred();
    let upstreamResponse;
    f.behavior(({ res }) => {
        upstreamResponse = res;
        res.on("close", disconnected.resolve);
        arrived.resolve();
    });
    await arm(f, [PROFILE]);
    const pending = call(f.base, PROFILE);
    await arrived.promise;
    assert.strictEqual((await call(f.base, "/__qualification/reset", {}).promise).status, 200);
    assert.strictEqual((await pending.promise).status, 503);
    await disconnected.promise;
    f.behavior(null);
    await arm(f, [PROFILE]);
    upstreamResponse.end("too-late");
    const fresh = call(f.base, PROFILE);
    await ready(f, PROFILE);
    await call(f.base, "/__qualification/release-all", {}).promise;
    assert.strictEqual((await fresh.promise).body.toString(), "upstream-body");
    assert.deepStrictEqual(f.proxy.state().counts[PROFILE], { requests: 1, responses: 1, ready: 1, delivered: 1 });
});

test("one controller, validated gates, unaffected direct upstream", async f => {
    assert.strictEqual((await call(f.base, "/__qualification/arm", { paths: ["/login"] }).promise).status, 400);
    await arm(f, [PROFILE]);
    assert.strictEqual((await call(f.base, "/__qualification/arm", { paths: [REFRESH] }).promise).status, 409);
    assert.strictEqual((await call(f.upstream, "/test/protected").promise).status, 200);
    assert.strictEqual(f.proxy.state().gates[PROFILE].claimed, false);
    assert.strictEqual((await call(f.base, "/beforeeach", {}).promise).status, 404);
    await call(f.base, "/__qualification/release-all", {}).promise;
});

test("capabilities preserve old SDK and account-linking qualification", async f => {
    const report = (await call(f.base, "/test/capabilities").promise).json();
    assert.strictEqual(report.qualificationProxy, true);
    assert.deepStrictEqual(report.accountLinking, f.supported.accountLinking);
    for (const key of ["enabled", "compatible", "mappedGoogleSession"]) {
        f.supported[key] = false;
        const unsupported = (await call(f.base, "/test/capabilities").promise).json();
        assert.strictEqual(unsupported.qualificationProxy, false);
        assert.deepStrictEqual(unsupported.accountLinking, f.supported.accountLinking);
        assert.strictEqual((await call(f.base, "/__qualification/arm", { paths: [PROFILE] }).promise).status, 503);
        f.supported[key] = true;
    }
});

test("fixture cleanup is ownership-scoped and survives reset", async f => {
    const before = f.requests.length;
    assert.strictEqual((await call(f.base, "/test/revoke-session", { handle: "unrelated" }).promise).status, 409);
    assert.strictEqual(f.requests.length, before);
    const owner = (await call(f.base, "/test/email-change-owner", { email: "fixture@example.com", recipe: "google" }).promise).json();
    assert.strictEqual(f.proxy.state().ownedFixtures, 1);
    assert.deepStrictEqual((await call(f.base, "/__qualification/owners").promise).json(), { fixtures: [owner], unresolvedCreations: 0 });
    await call(f.base, "/__qualification/reset", {}).promise;
    assert.strictEqual((await call(f.base, "/test/cleanup-owner", { fixtureId: owner.fixtureId }).promise).status, 200);
    assert.strictEqual(f.proxy.state().ownedFixtures, 0);
    assert.strictEqual((await call(f.base, "/test/cleanup-owner", { fixtureId: owner.fixtureId }).promise).status, 200);
});

for (const endpoint of ["/test/cleanup-owner", "/test/revoke-session"]) {
    test("conflicting cleanup fields preserve the other owner: " + endpoint, async f => {
        const live = new Map();
        let next = 0;
        f.behavior(({ path, body, res }) => {
            const input = JSON.parse(body.toString());
            if (path === "/test/email-change-owner") {
                const owner = { fixtureId: "owned-" + ++next, handle: "session-" + next };
                live.set(owner.fixtureId, { ...owner, active: true });
                return res.end(JSON.stringify(owner));
            }
            if (path === "/test/owner-session-state") {
                const owner = live.get(input.fixtureId);
                return res.end(JSON.stringify({ owned: !!owner && owner.handle === input.handle, active: owner.active }));
            }
            // Independent loopback backend contract: each endpoint ignores the other field.
            const owner = path === "/test/cleanup-owner" ? live.get(input.fixtureId) :
                Array.from(live.values()).find(value => value.handle === input.handle);
            assert(owner);
            owner.active = false;
            res.end(JSON.stringify(path === "/test/cleanup-owner" ? { cleaned: true } : { revoked: true, ownerCleaned: true }));
        });
        const a = (await call(f.base, "/test/email-change-owner", {}).promise).json();
        const b = (await call(f.base, "/test/email-change-owner", {}).promise).json();
        const conflict = endpoint === "/test/cleanup-owner" ? { fixtureId: a.fixtureId, handle: b.handle } :
            { fixtureId: b.fixtureId, handle: a.handle };
        assert.strictEqual((await call(f.base, endpoint, conflict).promise).status, 200);
        assert.strictEqual((await call(f.upstream, "/test/owner-session-state", a).promise).json().active, false);
        assert.deepStrictEqual((await call(f.upstream, "/test/owner-session-state", b).promise).json(), { owned: true, active: true });
        assert.strictEqual(f.proxy.state().ownedFixtures, 1);
        assert.deepStrictEqual((await call(f.base, "/__qualification/owners").promise).json().fixtures, [b]);
        const survivor = await call(f.base, "/test/owner-session-state", b).promise;
        assert.strictEqual(survivor.status, 200);
        assert.deepStrictEqual(survivor.json(), { owned: true, active: true });
        // Retrying A uses only A's tombstone and must not lose B either.
        assert.strictEqual((await call(f.base, endpoint, conflict).promise).status, 200);
        assert.deepStrictEqual((await call(f.base, "/__qualification/owners").promise).json().fixtures, [b]);
        assert.strictEqual((await call(f.base, "/test/cleanup-owner", { fixtureId: b.fixtureId }).promise).status, 200);
        assert.strictEqual(f.proxy.state().ownedFixtures, 0);
        assert.strictEqual(live.get(b.fixtureId).active, false);
    });
}

for (const outcome of ["delivered", "failed", "aborted"]) {
    test("ungated " + outcome + " across arm retains since-reset accounting", async f => {
        const path = "/test/protected";
        assert.strictEqual((await call(f.base, path).promise).status, 200);
        const before = f.proxy.state();
        const entered = deferred();
        let response;
        f.behavior(({ res }) => { response = res; entered.resolve(); });
        const pending = call(f.base, path);
        await entered.promise;
        await arm(f, [PROFILE]);
        assert(f.proxy.state().generation > before.generation);
        assert.strictEqual(f.proxy.state().gates[PROFILE].claimed, false);
        if (outcome === "delivered") {
            response.end("completed-before-reset");
            assert.strictEqual((await pending.promise).status, 200);
        } else if (outcome === "failed") {
            response.destroy();
            assert.strictEqual((await pending.promise).status, 502);
        } else {
            pending.abort();
            await assert.rejects(pending.promise);
        }
        await until(() => f.proxy.state().active === 0);
        const expected = { requests: 2, responses: 1, delivered: 1 };
        if (outcome === "delivered") { expected.responses++; expected.delivered++; }
        else expected[outcome] = 1;
        assert.deepStrictEqual(f.proxy.state().counts[path], expected);
        const history = f.proxy.state().events.filter(item => item.path === path);
        const totals = {};
        for (const item of history) totals[item.kind] = (totals[item.kind] || 0) + 1;
        assert.deepStrictEqual(totals, expected);
        assert.strictEqual(f.proxy.state().accountingEpoch, before.accountingEpoch);
        await call(f.base, "/__qualification/release-all", {}).promise;
    });
}

test("reset clears ungated accounting and fences late completion after arm", async f => {
    const path = "/test/protected";
    assert.strictEqual((await call(f.base, path).promise).status, 200);
    const entered = deferred(), disconnected = deferred();
    let response;
    f.behavior(({ res }) => {
        response = res;
        res.on("close", disconnected.resolve);
        entered.resolve();
    });
    const pending = call(f.base, path);
    await entered.promise;
    await arm(f, [PROFILE]);
    const epoch = f.proxy.state().accountingEpoch;
    await call(f.base, "/__qualification/reset", {}).promise;
    assert.strictEqual((await pending.promise).status, 503);
    await disconnected.promise;
    response.end("late-result-from-old-reset-epoch");
    assert.deepStrictEqual(f.proxy.state().counts, {});
    assert.deepStrictEqual(f.proxy.state().events, []);
    assert(f.proxy.state().accountingEpoch > epoch);
    f.behavior(null);
    assert.strictEqual((await call(f.base, path).promise).status, 200);
    assert.deepStrictEqual(f.proxy.state().counts[path], { requests: 1, responses: 1, delivered: 1 });
    assert(f.proxy.state().events.every(item => item.accountingEpoch === f.proxy.state().accountingEpoch));
});

test("upstream reset is a failure, never a ready fabricated response", async f => {
    await arm(f, [REFRESH]);
    f.behavior(({ res }) => res.destroy());
    assert.strictEqual((await call(f.base, REFRESH, {}).promise).status, 502);
    const gate = f.proxy.state().gates[REFRESH];
    assert.strictEqual(gate.failed, true);
    assert.strictEqual(gate.responseReady, false);
    assert.strictEqual(gate.delivered, false);
    assert.strictEqual(f.proxy.state().active, 0);
    assert.strictEqual(f.proxy.state().upstreamActive, 0);
});

test("cancelled creation remains explicitly unresolved, never falsely cleaned", async f => {
    const entered = deferred();
    f.behavior(() => entered.resolve());
    const request = call(f.base, "/test/email-change-owner", { email: "scoped@example.com" });
    await entered.promise;
    request.abort();
    await assert.rejects(request.promise);
    await until(() => f.proxy.state().unresolvedCreations === 1);
    f.behavior(null);
    const requests = f.requests.length;
    assert.strictEqual((await call(f.base, "/test/email-change-owner", { email: "do-not-create@example.com" }).promise).status, 409);
    assert.strictEqual(f.requests.length, requests);
    await call(f.base, "/__qualification/reset", {}).promise;
    assert.strictEqual(f.proxy.state().unresolvedCreations, 1);
});

test("release-all fences a pending arm capability response", async f => {
    const entered = deferred(), disconnected = deferred();
    let response;
    f.capabilityBehavior(({ res }) => {
        response = res;
        res.on("close", disconnected.resolve);
        entered.resolve();
    });
    const pending = call(f.base, "/__qualification/arm", { paths: [PROFILE] });
    await entered.promise;
    assert.strictEqual((await call(f.base, "/__qualification/release-all", {}).promise).status, 200);
    assert.strictEqual((await pending.promise).status, 409);
    await disconnected.promise;
    response.end(JSON.stringify(f.supported));
    assert.deepStrictEqual(f.proxy.state().gates, {});
    assert.strictEqual(f.proxy.state().upstreamActive, 0);
    f.capabilityBehavior(null);
    await arm(f, [PROFILE]);
    await call(f.base, "/__qualification/release-all", {}).promise;
});

test("malformed owner response fails without inventing ownership", async f => {
    f.behavior(({ res }) => res.end("null"));
    assert.strictEqual((await call(f.base, "/test/email-change-owner", {}).promise).status, 502);
    assert.strictEqual(f.proxy.state().ownedFixtures, 0);
    assert.strictEqual(f.proxy.state().unresolvedCreations, 1);
    assert.strictEqual(f.proxy.state().active, 0);
});

async function main() {
    let failures = 0;
    assert.strictEqual(createProxy.configuration({ CORE_PROTOCOL_TESTS: "1" }).enabled, false);
    assert.strictEqual(createProxy.configuration({ TEST_MODE: "testing", CORE_PROTOCOL_TESTS: "1" }).enabled, true);
    assert.throws(() => createProxy({ enabled: true, target: "http://example.com" }), /loopback/);
    for (const [name, run] of tests) {
        const f = await fixture();
        try { await run(f); console.log("PASS " + name); }
        catch (error) { failures++; console.error("FAIL " + name + "\n" + error.stack); }
        finally { await f.close(); }
    }
    const disabled = await fixture({ enabled: false });
    try {
        assert.strictEqual((await call(disabled.base, "/test/capabilities").promise).json().qualificationProxy, false);
        assert.strictEqual((await call(disabled.base, "/__qualification/arm", { paths: [PROFILE] }).promise).status, 501);
        assert.strictEqual(disabled.requests.length, 0);
        console.log("PASS disabled proxy never forwards");
    } finally { await disabled.close(); }
    const bounded = await fixture({ maxActive: 1, deadlineMs: 150 });
    try {
        await arm(bounded, [PROFILE]);
        const pending = call(bounded.base, PROFILE);
        await ready(bounded, PROFILE);
        assert.strictEqual((await call(bounded.base, REFRESH, {}).promise).status, 503);
        assert.strictEqual((await pending.promise).status, 504);
        assert.strictEqual(bounded.proxy.state().gates[PROFILE].timedOut, true);
        assert.strictEqual(bounded.proxy.state().active, 0);
        console.log("PASS capacity and deadline release held resources");
    } finally { await bounded.close(); }
    const limited = await fixture({ maxBytes: 512, maxOwners: 1 });
    try {
        assert.strictEqual((await call(limited.base, REFRESH, Buffer.alloc(513)).promise).status, 413);
        assert.strictEqual(limited.requests.length, 0);
        limited.behavior(({ res }) => res.end(Buffer.alloc(513)));
        assert.strictEqual((await call(limited.base, REFRESH, {}).promise).status, 502);
        assert.strictEqual(limited.proxy.state().active, 0);
        limited.behavior(null);
        assert.strictEqual((await call(limited.base, "/test/email-change-owner", {}).promise).status, 200);
        assert.strictEqual((await call(limited.base, "/test/email-change-owner", {}).promise).status, 503);
        assert.strictEqual(limited.proxy.state().ownedFixtures, 1);
        console.log("PASS request/response byte limits and fixture capacity");
    } finally { await limited.close(); }
    const stopping = await fixture();
    try {
        await arm(stopping, [PROFILE]);
        const pending = call(stopping.base, PROFILE);
        await ready(stopping, PROFILE);
        await stopping.proxy.close();
        await pending.promise.catch(() => {});
        assert.strictEqual(stopping.proxy.state().active, 0);
        assert.strictEqual(stopping.proxy.state().upstreamActive, 0);
        console.log("PASS shutdown cancels held resources");
    } finally { await stopping.close(); }
    console.log("Loopback proxy tests only; no real Core protocol assertions executed.");
    if (failures) throw new Error(failures + " loopback scenario(s) failed");
}

main().catch(error => { console.error(error.stack); process.exitCode = 1; });
