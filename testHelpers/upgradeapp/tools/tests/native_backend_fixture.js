// Actual observer/owner modules with explicit SDK/HTTP doubles; no listener or real Core.
const fs = require('fs');
const path = require('path');
const { EventEmitter } = require('events');
const crypto = require('crypto');
const nativeEvidence = require('../../../server/nativeEvidence');
const { harness } = require('../../../server/fixtureOwners.test');

async function main(input) {
    const { owned, source, stages, sibling } = input;
    fs.mkdirSync(owned, { mode: 0o700 });
    const save = (file, value) => fs.writeFileSync(file, JSON.stringify(value), { mode: 0o600, flag: 'wx' });
    const identity = { nodeSdkVersion: source.plan.nodeVersion, coreVersion: source.plan.coreVersion,
        sdkImplementationSha256: source.nodeSdkSha256, coreSha256: source.coreSha256, effectiveClasspathSha256: source.coreManifestSha256 };
    const snapshot = { identity, qualifiedIdentitySha256: crypto.createHash('sha256').update(JSON.stringify(identity)).digest('hex'),
        dependenciesSha256: source.dependenciesSha256, coreManifestSha256: source.coreManifestSha256 };
    save(path.join(owned, 'owner.json'), { root: owned, runId: source.runId });
    save(path.join(owned, 'exposure-config.json'), { identity, runId: source.runId, instance: source.instance });
    fs.writeFileSync(path.join(owned, 'dependencies.sha256'), source.dependenciesSha256, { mode: 0o600 });
    fs.mkdirSync(path.join(owned, 'native-evidence'), { mode: 0o700 });
    const directory = path.join(owned, 'native-evidence', source.nativeNonce);
    fs.mkdirSync(directory, { mode: 0o700 });
    const control = { schema: 1, kind: 'native-backend-control', runId: source.runId, instance: source.instance,
        nativeNonce: source.nativeNonce, challenge: source.challenge, finalizeKey: crypto.randomBytes(24).toString('hex') };
    save(path.join(directory, 'control.json'), control);
    const h = harness();
    const sessions = new Map();
    const native = nativeEvidence({ owners: h.owners, Session: { getSessionInformation: async handle => sessions.get(handle) },
        env: { CORE_PROTOCOL_TESTS: '1', INSTALL_PATH: owned, ST_TEST_RUN_ID: source.runId, ST_TEST_INSTANCE_ID: source.instance },
        snapshot: () => snapshot });
    const routes = new Map();
    native.install({ post: (name, handler) => routes.set(name, handler) });
    async function request(stage, route, method, body, operation, extra = {}) {
        const req = new EventEmitter(); Object.assign(req, { method, path: route, body });
        req.headers = { 'x-st-test-run-id': source.runId, 'x-st-native-nonce': source.nativeNonce,
            'x-st-native-stage': stage, 'fdi-version': source.context.fdi, ...extra };
        req.rawHeaders = Object.entries(req.headers).flat();
        const res = new EventEmitter(); res.statusCode = 200; res.headers = {};
        res.status = code => { res.statusCode = code; return res; };
        res.setHeader = (name, value) => { res.headers[name] = value; };
        res.json = value => { res.body = value; return res; };
        let accepted = false; native.middleware(req, res, () => { accepted = true; });
        if (!accepted) throw new Error('Actual observer refused fixture request');
        if (operation) res.body = await operation(req, res);
        else routes.get(route)(req, res, error => { throw error; });
        res.emit('finish'); res.emit('close');
        if (res.statusCode !== 200) throw new Error('Fixture response failed');
        return res;
    }
    let fixture, handle, loginCount = 0;
    const startedMs = Date.now();
    for (const stage of stages) {
        stage.revocationResponses = [];
        for (const operation of stage.clientOperations) {
            const route = operation === 'sdk.refresh' ? '/auth/session/refresh' : operation === 'sdk.signOut' ? '/auth/signout' : operation.split(':')[1];
            const method = ['/test/capabilities', '/featureFlags', '/'].includes(route) ? 'GET' : 'POST';
            if (route === '/test/native/challenge') {
                stage.challengeResponse = (await request(stage.stage, route, method, { challenge: source.challenge })).body;
            } else if (route === '/test/email-change-owner') {
                await request(stage.stage, route, method, { requestId: source.nativeNonce + '-owner' }, async req => {
                    const value = await native.provision(req, () => h.owners.create('host-only@example.com', async id => ({
                        getUserId: () => id, getHandle: () => 'provisioning' })));
                    fixture = value.owner.id; return {};
                });
            } else if (route === '/login') {
                const response = await request(stage.stage, route, method, { userId: fixture }, async (req, res) => {
                    const newHandle = 'actual-login-' + ++loginCount;
                    sessions.set(newHandle, {});
                    await native.login(req, res, async () => ({ getUserId: () => fixture, getHandle: () => newHandle, getTenantId: () => 'public' }));
                    return {};
                }, { 'x-st-native-fixture-id': fixture });
                handle = response.headers['x-st-native-session-handle'];
            } else if (route === '/test/owner-session-state') {
                const response = await request(stage.stage, route, method, { fixtureId: fixture, handle }, req => native.sessionState(req));
                stage.revocationResponses.push({ fixtureId: fixture, handle, ...response.body });
            } else if (route === '/test/cleanup-owner') {
                await request(stage.stage, route, method, { fixtureId: fixture }, req => native.cleanup(req));
            } else {
                await request(stage.stage, route, method, {}, async () => {
                    if (route === '/auth/signout') sessions.delete(handle);
                    return {};
                });
            }
        }
        stage.challengeResponse = stages[0].challengeResponse;
    }
    const endedMs = Date.now();
    await request('cleanup', '/test/native/finalize', 'POST', {}, undefined, { 'x-st-native-finalize-key': control.finalizeKey });
    fs.mkdirSync(path.dirname(sibling), { recursive: true, mode: 0o700 });
    fs.mkdirSync(sibling, { mode: 0o700 });
    for (const name of ['events.jsonl', 'complete.json']) {
        fs.copyFileSync(path.join(directory, name), path.join(sibling, name));
        fs.chmodSync(path.join(sibling, name), 0o600);
    }
    return { stages, startedMs, endedMs };
}

let input = '';
process.stdin.on('data', value => { input += value; });
process.stdin.on('end', () => main(JSON.parse(input)).then(value => console.log(JSON.stringify(value))).catch(error => {
    console.error(error); process.exitCode = 1;
}));
