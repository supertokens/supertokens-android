const fs = require('fs');
const path = require('path');
const assert = require('assert');
const { OwnedProcess } = require('./ownedProcess');
const { unusedPort } = require('./ownedCore');
const { inspect } = require('./coreRuntimeIdentity');

function assertEmpty(state, owners) {
    for (const key of ['active', 'upstreamActive', 'ownedFixtures', 'ownerReservations', 'unresolvedCreations']) {
        assert.strictEqual(state[key], 0, 'Proxy leaked ' + key);
    }
    assert.deepStrictEqual(owners.fixtures, []);
    assert.strictEqual(owners.unresolvedCreations, 0);
    for (const gate of Object.values(state.gates)) assert.strictEqual(gate.released, true, 'Proxy retains a held gate');
}

async function qualify({ backend, env, evidence, runTests }) {
    const fetch = require(path.join(backend, 'node_modules/node-fetch'));
    const port = Number(env.NODE_PORT) + 1;
    if (port > 65535 || [8080, 9000, Number(env.ST_TEST_CORE_PORT)].includes(port)) throw new Error('No valid owned proxy port adjacent to backend');
    await unusedPort(port);
    const base = 'http://127.0.0.1:' + port;
    const proxy = new OwnedProcess(env.ST_TEST_RUN_ID);
    const log = fs.openSync(path.join(evidence, 'proxy.log'), 'a');
    const audit = entry => fs.appendFileSync(path.join(evidence, 'proxy-lifecycle.jsonl'), JSON.stringify(entry) + '\n');
    let ready = false;
    async function request(route, body) {
        proxy.verify();
        const identity = inspect(proxy.child.pid, port);
        if (!identity.listening || identity.cwd !== fs.realpathSync(backend)) throw new Error('Owned proxy listener mismatch');
        // Gradle blocks this event loop; pooled sockets can expire before cleanup resumes.
        const response = await fetch(base + route, { method: body === undefined ? 'GET' : 'POST',
            headers: { 'content-type': 'application/json', connection: 'close' }, body: body === undefined ? undefined : JSON.stringify(body), timeout: 10000 });
        if (!response.ok) throw new Error('Proxy ' + route + ': HTTP ' + response.status);
        proxy.verify();
        return response.json();
    }
    try {
        await proxy.start(process.execPath, [path.join(backend, 'runQualificationProxy.js')], {
            cwd: backend, env: { ...env, QUALIFICATION_PROXY_TARGET: env.ST_TEST_BACKEND_URL, QUALIFICATION_PROXY_PORT: String(port) },
            stdio: ['ignore', log, log]
        });
        audit({ action: 'STARTED', pid: proxy.child.pid, fingerprint: proxy.fingerprint, target: env.ST_TEST_BACKEND_URL, port });
        const deadline = Date.now() + 15000;
        while (Date.now() < deadline) {
            proxy.verify();
            try {
                const capabilities = await request('/test/capabilities');
                assert.strictEqual(capabilities.qualificationProxy, true);
                fs.writeFileSync(path.join(evidence, 'proxy-capabilities.json'), JSON.stringify(capabilities, null, 2));
                ready = true;
                break;
            } catch (error) {
                if (Date.now() + 100 >= deadline) throw error;
                await new Promise(resolve => setTimeout(resolve, 100));
            }
        }
        if (!ready) throw new Error('Owned proxy readiness failed');
        await runTests(base);
    } finally {
        try {
            if (ready) {
                await request('/__qualification/release-all', {});
                const owners = await request('/__qualification/owners');
                fs.writeFileSync(path.join(evidence, 'proxy-owners-before-cleanup.json'), JSON.stringify(owners, null, 2));
                for (const owner of owners.fixtures) {
                    const result = await request('/test/cleanup-owner', { fixtureId: owner.fixtureId });
                    assert.strictEqual(result.cleaned, true);
                }
                const finalOwners = await request('/__qualification/owners');
                const state = await request('/__qualification/state');
                fs.writeFileSync(path.join(evidence, 'proxy-final-state.json'), JSON.stringify({ state, owners: finalOwners }, null, 2));
                assertEmpty(state, finalOwners);
                audit({ action: 'ZERO_LEAKS_ACKNOWLEDGED' });
            }
        } finally {
            await proxy.stop(env.ST_TEST_RUN_ID);
            audit({ action: 'STOPPED' });
            fs.closeSync(log);
        }
    }
}
module.exports = { qualify, assertEmpty };
