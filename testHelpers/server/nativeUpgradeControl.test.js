const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { createControl, finalize, verifyBackendArchive } = require('./nativeUpgradeControl');
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const root = path.resolve(__dirname, '../..');
function fixture(t) {
    const owned = fs.mkdtempSync(path.join(root, 'backend-control-host-'));
    t.after(() => fs.rmSync(owned, { recursive: true, force: true }));
    const binding = { runId: 'a'.repeat(48), instance: 'b'.repeat(48), qualifiedIdentitySha256: 'c'.repeat(64),
        dependenciesSha256: 'd'.repeat(64), coreManifestSha256: 'e'.repeat(64),
        context: { backend: 'http://127.0.0.1:18081', fdi: '1.16' } };
    fs.writeFileSync(path.join(owned, 'owner.json'), JSON.stringify({ root: owned, runId: binding.runId }), { mode: 0o600 });
    const nativeNonce = 'f'.repeat(48), control = createControl(owned, binding, nativeNonce);
    const directory = path.join(owned, 'native-evidence', nativeNonce), archive = path.join(owned, 'archive', nativeNonce);
    function complete() {
        const events = JSON.stringify({ schema: 1, runId: binding.runId, instance: binding.instance, nativeNonce, sequence: 1,
            timeMs: Date.now(), type: 'row-complete' }) + '\n';
        fs.writeFileSync(path.join(directory, 'events.jsonl'), events, { mode: 0o600 });
        fs.writeFileSync(path.join(directory, 'complete.json'), JSON.stringify({ schema: 1, kind: 'native-backend-complete',
            runId: binding.runId, instance: binding.instance, nativeNonce, eventsSha256: hash(events), eventCount: 1,
            qualifiedIdentitySha256: binding.qualifiedIdentitySha256, dependenciesSha256: binding.dependenciesSha256,
            coreManifestSha256: binding.coreManifestSha256 }), { mode: 0o600 });
    }
    const fetch = async (url, options) => {
        assert.equal(url, binding.context.backend + '/test/native/finalize');
        assert.equal(options.headers['x-st-native-finalize-key'], control.finalizeKey);
        assert.equal(options.headers['x-st-native-nonce'], nativeNonce);
        assert.equal(options.headers['fdi-version'], binding.context.fdi);
        complete(); return { ok: true, json: async () => ({ finalizing: true }) };
    };
    return { owned, binding, nativeNonce, control, directory, archive, fetch,
        finalize: () => finalize(owned, binding, nativeNonce, archive, fetch),
        verify: () => verifyBackendArchive(owned, binding, nativeNonce, archive) };
}
test('supervisor creates unique private challenge/finalize control and archives only server output', async t => {
    const f = fixture(t);
    assert.notEqual(f.control.challenge, f.control.finalizeKey);
    assert.notEqual(f.control.challenge, f.nativeNonce);
    assert.equal(fs.statSync(path.join(f.directory, 'control.json')).mode & 0o777, 0o600);
    await f.finalize(); assert.doesNotThrow(f.verify);
    assert.deepEqual(fs.readdirSync(f.archive).sort(), ['complete.json', 'events.jsonl']);
    assert.throws(() => createControl(f.owned, f.binding, f.nativeNonce), /EEXIST/);
});
test('late backend request invalidates previously copied completion', async t => {
    const f = fixture(t); await f.finalize();
    fs.appendFileSync(path.join(f.directory, 'events.jsonl'), '{"type":"late-request"}\n');
    assert.throws(f.verify, /mismatch/);
});
test('deleted live completion after late request blocks offline acceptance', async t => {
    const f = fixture(t); await f.finalize(); fs.unlinkSync(path.join(f.directory, 'complete.json'));
    assert.throws(f.verify, /ENOENT/);
});
test('edited archive cannot replace host-owned output', async t => {
    const f = fixture(t); await f.finalize();
    fs.writeFileSync(path.join(f.archive, 'events.jsonl'), '{}\n');
    assert.throws(f.verify, /changed/);
});
test('wrong instance cannot reuse a complete native archive', async t => {
    const f = fixture(t); await f.finalize(); f.binding.instance = '1'.repeat(48);
    assert.throws(f.verify, /mismatch/);
});
test('completion symlink rejected even when bytes match', async t => {
    const f = fixture(t); await f.finalize();
    fs.unlinkSync(path.join(f.archive, 'complete.json'));
    fs.symlinkSync(path.join(f.directory, 'complete.json'), path.join(f.archive, 'complete.json'));
    assert.throws(f.verify, /Non-private/);
});
test('HTTP refusal creates no backend proof archive', async t => {
    const f = fixture(t);
    await assert.rejects(finalize(f.owned, f.binding, f.nativeNonce, f.archive, async () => ({ ok: false })), /rejected/);
    assert.equal(fs.existsSync(f.archive), false);
});

test('supervisor composes real backend challenge, owned cleanup and finalizer without a listener', async t => {
    const { EventEmitter } = require('events');
    const f = fixture(t), { owners, users } = require('./fixtureOwners.test').harness();
    f.binding.qualifiedIdentitySha256 = hash(JSON.stringify({ nodeSdkVersion: 'host-double', coreVersion: 'host-double' }));
    const native = require('./nativeEvidence')({ owners, Session: {}, env: { CORE_PROTOCOL_TESTS: '1', INSTALL_PATH: f.owned,
        ST_TEST_RUN_ID: f.binding.runId, ST_TEST_INSTANCE_ID: f.binding.instance }, snapshot: () => ({
        identity: { nodeSdkVersion: 'host-double', coreVersion: 'host-double' },
        qualifiedIdentitySha256: f.binding.qualifiedIdentitySha256, dependenciesSha256: f.binding.dependenciesSha256,
        coreManifestSha256: f.binding.coreManifestSha256 }) });
    const routes = new Map(); native.install({ post: (route, handler) => routes.set(route, handler) });
    function request(route, body, headers = {}) {
        const req = new EventEmitter(), res = new EventEmitter();
        Object.assign(req, { path: route, method: 'POST', body, headers: { 'x-st-test-run-id': f.binding.runId,
            'x-st-native-nonce': f.nativeNonce, 'x-st-native-stage': 'seed', 'fdi-version': f.binding.context.fdi, ...headers } });
        req.rawHeaders = Object.entries(req.headers).flat();
        Object.assign(res, { statusCode: 200, setHeader() {}, status(code) { this.statusCode = code; return this; },
            json(value) { this.body = value; return this; } });
        let accepted = false; native.middleware(req, res, () => { accepted = true; });
        const finish = () => { res.emit('finish'); res.emit('close'); };
        return { req, res, accepted, finish, async dispatch() {
            assert.ok(accepted);
            await routes.get(route)(req, res, error => { throw error; });
            finish(); return res.body;
        } };
    }
    const challenge = await request('/test/native/challenge', { challenge: f.control.challenge }).dispatch();
    assert.equal(challenge.challenge, f.control.challenge);
    const provision = request('/test/email-change-owner', { requestId: f.nativeNonce + '-owner' });
    const created = await native.provision(provision.req, () => owners.create('host@example.com', async id =>
        ({ getUserId: () => id, getHandle: () => 'host-provisioning' })));
    provision.finish(); assert.equal(users.size, 1);
    const cleanup = request('/test/cleanup-owner', { fixtureId: created.owner.id });
    await native.cleanup(cleanup.req); cleanup.finish(); assert.equal(users.size, 0);
    await finalize(f.owned, f.binding, f.nativeNonce, f.archive, async (url, options) => {
        assert.equal(url, f.binding.context.backend + '/test/native/finalize');
        const result = request('/test/native/finalize', {}, options.headers);
        const body = await result.dispatch();
        return { ok: result.res.statusCode === 200, json: async () => body };
    });
    assert.doesNotThrow(f.verify);
    const late = request('/test/native/challenge', { challenge: f.control.challenge }); late.finish();
    assert.equal(late.accepted, false);
    assert.throws(f.verify, /ENOENT|mismatch/);
});
