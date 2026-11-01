const assert = require('assert');
const fetch = require('node-fetch');
const fs = require('fs');
const path = require('path');

async function main() {
    const base = process.env.ST_TEST_BACKEND_URL;
    const runId = process.env.ST_TEST_RUN_ID;
    async function call(route, body = {}, owner = runId, headers = {}) {
        return fetch(base + route, { method: 'POST', headers: {
            'content-type': 'application/json', 'x-st-test-run-id': owner, 'st-auth-mode': 'header', ...headers
        }, body: JSON.stringify(body), timeout: 20000 });
    }
    assert.strictEqual((await call('/beforeeach', {}, 'foreign')).status, 403);
    for (const route of ['/startst', '/stopst', '/beforeeach', '/after', '/test/startServer',
        '/test/shutdown-core', '/reinitialiseBackendConfig']) {
        for (const variant of [route, route + '/', route.toUpperCase(), route.toUpperCase() + '/']) {
            assert.strictEqual((await call(variant, {}, 'foreign')).status, 403, variant);
        }
    }
    assert.strictEqual((await call('/beforeeach')).status, 200);
    const markerDirectory = path.join(process.env.INSTALL_PATH, '.started');
    fs.mkdirSync(markerDirectory, { recursive: true });
    const marker = path.join(markerDirectory, '127.0.0.1-' + process.env.ST_TEST_CORE_PORT);
    fs.writeFileSync(marker, String(process.pid));
    assert.strictEqual((await call('/startst', { accessTokenValidity: 3 })).status, 500);
    assert.strictEqual(fs.readFileSync(marker, 'utf8'), String(process.pid));
    fs.unlinkSync(marker);
    const concurrent = await Promise.all([call('/STARTST/', { accessTokenValidity: 3 }),
        call('/startst', { accessTokenValidity: 3 })]);
    assert.deepStrictEqual(concurrent.map(response => response.status).sort(), [200, 409]);
    const qualification = require(path.join(process.env.INSTALL_PATH, 'backend', 'accountLinkingQualification'));
    const pair = qualification.identity();
    assert.ok(pair && pair.coreRuntimeId && pair.expressRuntimeId, 'Exact isolated qualification identity required');
    assert.strictEqual(qualification.classify(pair, { schema: 1, identity: { ...pair, coreRuntimeId: 'foreign-9000' } }).reason,
        'QUALIFICATION_PAIR_MISMATCH');
    fs.writeFileSync(path.join(process.env.INSTALL_PATH, 'qualification-identity.json'), JSON.stringify(pair));
    const login = await call('/login', { userId: 'owned-fixture-smoke' });
    assert.strictEqual(login.status, 200);
    const refreshed = await call('/auth/session/refresh', {}, runId, {
        authorization: 'Bearer ' + login.headers.get('st-refresh-token'), rid: 'session',
        'anti-csrf': login.headers.get('anti-csrf')
    });
    assert.strictEqual(refreshed.status, 200);
    const refreshToken = refreshed.headers.get('st-refresh-token');
    assert.ok(refreshToken);
    assert.strictEqual((await call('/stopst', { pid: process.pid })).status, 500);
    assert.strictEqual((await call('/login', { userId: 'still-alive' })).status, 200);
    assert.strictEqual((await call('/beforeeach')).status, 200);
    assert.strictEqual((await call('/startst', { accessTokenValidity: 1, enableAntiCsrf: false })).status, 200);
    assert.notStrictEqual(qualification.identity().coreRuntimeId, pair.coreRuntimeId);
    assert.strictEqual(qualification.classify(qualification.identity(), { schema: 1, identity: pair }).reason,
        'QUALIFICATION_PAIR_MISMATCH', 'pre-restart receipt must not qualify restarted Core');
    assert.strictEqual((await call('/auth/session/refresh', {}, runId, {
        authorization: 'Bearer ' + refreshToken, rid: 'session'
    })).status, 401);
    assert.strictEqual((await call('/login', { userId: 'owned-fixture-restarted' })).status, 200);
    assert.strictEqual((await call('/after')).status, 200);
    assert.strictEqual((await call('/after')).status, 200);
    console.log('PASS: loopback lifecycle, foreign owner/PID rejection, login/refresh, restart clears session storage, repeated cleanup');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
