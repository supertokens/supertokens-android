const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { EventEmitter } = require('events');
const { test } = require('node:test');
const nativeEvidence = require('./nativeEvidence');
const { harness } = require('./fixtureOwners.test');

const nonce = 'a'.repeat(48);
const runId = 'b'.repeat(48);
const instance = 'c'.repeat(48);
const challenge = 'd'.repeat(48);
const finalizeKey = 'e'.repeat(48);
const hash = value => crypto.createHash('sha256').update(value).digest('hex');

// No listener or SDK/Core is started. Requests exercise the real observer with Node lifecycle events.
async function fixture(run) {
    const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'native-backend-host-')));
    const directory = path.join(root, 'native-evidence', nonce);
    fs.mkdirSync(path.dirname(directory), { mode: 0o700 });
    fs.mkdirSync(directory, { mode: 0o700 });
    fs.writeFileSync(path.join(root, 'owner.json'), JSON.stringify({ root, runId }), { mode: 0o600 });
    const control = { schema: 1, kind: 'native-backend-control', runId, instance, nativeNonce: nonce, challenge, finalizeKey };
    fs.writeFileSync(path.join(directory, 'control.json'), JSON.stringify(control), { mode: 0o600 });
    const env = { CORE_PROTOCOL_TESTS: '1', INSTALL_PATH: root, ST_TEST_RUN_ID: runId, ST_TEST_INSTANCE_ID: instance };
    const h = harness();
    let identity = { nodeSdkVersion: 'host-double', coreVersion: 'host-double', coreRuntimeId: 'host-runtime' };
    const sessions = new Map();
    const reads = [];
    const Session = { getSessionInformation: async handle => { reads.push(handle); return sessions.get(handle); } };
    const snapshot = () => ({ identity: { ...identity }, qualifiedIdentitySha256: hash(JSON.stringify(identity)),
        dependenciesSha256: '1'.repeat(64), coreManifestSha256: '2'.repeat(64) });
    const native = nativeEvidence({ owners: h.owners, Session, env, snapshot });
    const routes = new Map();
    native.install({ post: (route, handler) => routes.set(route, handler) });
    let count = 0;
    function request(route, body = {}, headers = {}, options = {}) {
        const req = new EventEmitter();
        req.path = route;
        req.method = options.method || 'POST';
        req.body = body;
        req.headers = options.untagged ? headers : { 'x-st-test-run-id': runId, 'x-st-native-nonce': nonce,
            'x-st-native-stage': 'seed', 'fdi-version': '4.1', ...headers };
        req.rawHeaders = Object.entries(req.headers).flat();
        if (options.duplicate) req.rawHeaders.push(options.duplicate, req.headers[options.duplicate]);
        const res = new EventEmitter();
        res.statusCode = 200;
        res.headers = {};
        res.setHeader = (name, value) => { res.headers[name] = value; };
        res.status = code => { res.statusCode = code; return res; };
        res.json = value => { res.body = value; return res; };
        let accepted = false;
        native.middleware(req, res, () => { accepted = true; });
        const finish = () => { res.emit('finish'); res.emit('close'); };
        const dispatch = async () => {
            assert.ok(accepted, 'Middleware refused request');
            let error;
            await routes.get(route)(req, res, value => { error = value; });
            if (error) { res.statusCode = 500; finish(); throw error; }
            finish();
            return res.body;
        };
        return { req, res, accepted, finish, dispatch };
    }
    async function start() {
        return request('/test/native/challenge', { challenge }).dispatch();
    }
    async function provision() {
        const r = request('/test/email-change-owner', { requestId: nonce + '-owner' });
        const result = await native.provision(r.req, () => h.owners.create('native@example.com', async id => ({ getUserId: () => id, getHandle: () => 'provisioning' })));
        r.finish();
        return result.owner.id;
    }
    async function login(id) {
        const r = request('/login', { userId: id }, { 'x-st-native-fixture-id': id });
        const handle = 'actual-login-' + ++count;
        const session = await native.login(r.req, r.res, async () => {
            sessions.set(handle, { userId: id });
            return { getUserId: () => id, getHandle: () => handle, getTenantId: () => 'public' };
        });
        r.finish();
        assert.strictEqual(r.res.headers['x-st-native-session-handle'], session.getHandle());
        return handle;
    }
    function events() { return fs.readFileSync(path.join(directory, 'events.jsonl'), 'utf8').trim().split('\n').map(JSON.parse); }
    const complete = () => fs.existsSync(path.join(directory, 'complete.json'));
    try { await run({ native, h, root, directory, control, env, snapshot, request, start, provision, login, sessions, reads,
        events, complete, changeIdentity: () => { identity = { ...identity, coreRuntimeId: 'replacement' }; } }); }
    finally { fs.rmSync(root, { recursive: true, force: true }); }
}

test('observed challenge, actual login handle, Core absence, cleanup and finish/close form private server archive', () => fixture(async f => {
    const observed = await f.start();
    assert.strictEqual(observed.challenge, challenge);
    assert.deepStrictEqual(observed.identity, f.snapshot().identity);
    const id = await f.provision();
    const handle = await f.login(id);
    let r = f.request('/test/owner-session-state', { fixtureId: id, handle });
    assert.deepStrictEqual(await f.native.sessionState(r.req), { owned: true, active: true }); r.finish();
    f.sessions.delete(handle);
    r = f.request('/test/owner-session-state', { fixtureId: id, handle });
    assert.deepStrictEqual(await f.native.sessionState(r.req), { owned: true, active: false }); r.finish();
    assert.deepStrictEqual(f.reads, [handle, handle]);
    r = f.request('/test/cleanup-owner', { fixtureId: id });
    assert.deepStrictEqual(await f.native.cleanup(r.req), { cleaned: true }); r.finish();
    r = f.request('/test/native/finalize', {}, { 'x-st-native-stage': 'cleanup', 'x-st-native-finalize-key': finalizeKey });
    assert.deepStrictEqual(await r.dispatch(), { finalizing: true });
    assert.ok(f.complete());
    const complete = JSON.parse(fs.readFileSync(path.join(f.directory, 'complete.json')));
    assert.strictEqual(complete.eventsSha256, hash(fs.readFileSync(path.join(f.directory, 'events.jsonl'))));
    const events = f.events();
    assert.deepStrictEqual(events.map(value => value.sequence), events.map((_, index) => index + 1));
    assert.ok(events.some(value => value.type === 'login-session' && value.handle === handle));
    assert.ok(events.some(value => value.type === 'session-state' && value.active === false && value.handle === handle));
    assert.ok(events.filter(value => value.type === 'request').every(value => value.headers['fdi-version'] === '4.1'));
    assert.ok(!fs.readFileSync(path.join(f.directory, 'events.jsonl'), 'utf8').includes(finalizeKey));
    assert.strictEqual(fs.statSync(path.join(f.directory, 'events.jsonl')).mode & 0o077, 0);
    const late = f.request('/test/capabilities', {}, {}, { method: 'GET' });
    assert.strictEqual(late.accepted, false); late.finish();
    assert.strictEqual(f.complete(), false, 'Late request invalidates completion');
}));

test('wrong run, absent/duplicate FDI, duplicate nonce, wrong stage and arbitrary mutation route refused', async () => {
    for (const variant of ['run', 'nonce', 'fdi', 'duplicate-fdi', 'duplicate-nonce', 'stage', 'route']) await fixture(async f => {
        await f.start();
        const headers = variant === 'run' ? { 'x-st-test-run-id': 'f'.repeat(48) } : variant === 'nonce' ? { 'x-st-native-nonce': 'f'.repeat(48) } :
            variant === 'fdi' ? { 'fdi-version': '' } : variant === 'stage' ? { 'x-st-native-stage': 'foreign' } : {};
        const r = f.request(variant === 'route' ? '/test/revoke-session' : '/login', {}, headers,
            { duplicate: variant === 'duplicate-fdi' ? 'fdi-version' : variant === 'duplicate-nonce' ? 'x-st-native-nonce' : undefined });
        assert.strictEqual(r.accepted, false, variant); r.finish();
        assert.ok(f.events().some(value => value.type === 'refusal'));
    });
});

test('challenge is preissued and runtime-derived, cannot echo caller identity or survive replacement', async () => {
    await fixture(async f => {
        const r = f.request('/test/native/challenge', { challenge: 'f'.repeat(48), identity: { forged: true } });
        await assert.rejects(r.dispatch(), /challenge mismatch/);
        assert.strictEqual(f.complete(), false);
    });
    await fixture(async f => {
        const r = f.request('/test/native/challenge', { challenge, identity: { forged: true }, dependenciesSha256: 'forged' });
        const result = await r.dispatch();
        assert.deepStrictEqual(result.identity, f.snapshot().identity);
        assert.strictEqual(result.dependenciesSha256, '1'.repeat(64));
        f.changeIdentity();
        const next = f.request('/test/capabilities', {}, {}, { method: 'GET' });
        assert.strictEqual(next.accepted, false); next.finish();
    });
});

test('native login rejects wrong fixture, user, actual session identity and provisioning handle', async () => {
    for (const variant of ['fixture', 'user', 'session-user', 'session-tenant', 'provisioning']) await fixture(async f => {
        await f.start();
        const id = await f.provision();
        let called = false;
        const r = f.request('/login', { userId: variant === 'user' ? 'unrelated' : id }, { 'x-st-native-fixture-id': variant === 'fixture' ? 'unrelated' : id });
        await assert.rejects(f.native.login(r.req, r.res, async () => {
            called = true;
            return { getUserId: () => variant === 'session-user' ? 'unrelated' : id,
                getTenantId: () => variant === 'session-tenant' ? 'other' : 'public',
                getHandle: () => variant === 'provisioning' ? 'provisioning' : 'login' };
        }));
        if (['fixture', 'user'].includes(variant)) assert.strictEqual(called, false);
        r.res.statusCode = 500; r.finish();
        assert.strictEqual(f.h.users.size, 1, 'Refusal must not delete arbitrary users');
    });
});

test('session-state rejects provisioning/arbitrary handles and post-cleanup absence as signout proof', () => fixture(async f => {
    await f.start(); const id = await f.provision(); const handle = await f.login(id);
    for (const selected of ['provisioning', 'arbitrary']) {
        const r = f.request('/test/owner-session-state', { fixtureId: id, handle: selected });
        await assert.rejects(f.native.sessionState(r.req), /Unknown native login/);
        r.finish();
    }
    assert.deepStrictEqual(f.reads, []);
    const cleanup = f.request('/test/cleanup-owner', { fixtureId: id });
    await f.native.cleanup(cleanup.req); cleanup.finish();
    const r = f.request('/test/owner-session-state', { fixtureId: id, handle });
    await assert.rejects(f.native.sessionState(r.req), /live mapped fixture/); r.finish();
    assert.deepStrictEqual(f.reads, []);
}));

test('cleanup cannot race session creation or Core absence read, login cannot race cleanup', () => fixture(async f => {
    await f.start(); const id = await f.provision();
    let release;
    const barrier = new Promise(resolve => { release = resolve; });
    const r = f.request('/login', { userId: id }, { 'x-st-native-fixture-id': id });
    const pending = f.native.login(r.req, r.res, async () => {
        await barrier;
        return { getUserId: () => id, getHandle: () => 'actual', getTenantId: () => 'public' };
    });
    await assert.rejects(f.h.owners.cleanupById(id), /in progress/);
    release(); await pending; r.finish();
    let releaseRead;
    const read = f.h.owners.nativeSessionState(id, 'actual', nonce, () => new Promise(resolve => { releaseRead = resolve; }));
    await assert.rejects(f.h.owners.cleanupById(id), /in progress/);
    releaseRead(undefined); await read;
    const cleanup = f.h.owners.cleanupById(id);
    await assert.rejects(f.h.owners.nativeLogin(id, id, nonce, async () => { throw new Error('must not execute'); }), /live mapped fixture/);
    await cleanup;
}));

test('lost login/creation responses and closed/aborted requests cannot produce accepted rows or retries', async () => {
    for (const kind of ['creation', 'login', 'close', 'aborted']) await fixture(async f => {
        await f.start();
        if (kind === 'creation') {
            const r = f.request('/test/email-change-owner', { requestId: nonce + '-owner' });
            await assert.rejects(f.native.provision(r.req, async () => { throw new Error('Core response lost'); }), /lost/);
            r.res.emit('close');
        } else {
            const id = await f.provision();
            const r = f.request('/login', { userId: id }, { 'x-st-native-fixture-id': id });
            if (kind === 'login') await assert.rejects(f.native.login(r.req, r.res, async () => { throw new Error('Core response lost'); }), /lost/);
            if (kind === 'aborted') r.req.emit('aborted');
            r.res.emit('close');
        }
        const retry = f.request('/test/email-change-owner', { requestId: nonce + '-owner' });
        assert.strictEqual(retry.accepted, false); retry.finish();
        assert.strictEqual(f.complete(), false);
    });
});

test('wrong owner cleanup and finalization without supervisor key or with inflight request fail closed', async () => {
    for (const kind of ['owner', 'key', 'inflight']) await fixture(async f => {
        await f.start(); const id = await f.provision();
        if (kind === 'owner') {
            const r = f.request('/test/cleanup-owner', { fixtureId: 'unrelated' });
            await assert.rejects(f.native.cleanup(r.req), /Unknown native fixture/); r.res.statusCode = 500; r.finish();
            assert.strictEqual(f.h.users.size, 1);
        } else {
            const r = f.request('/test/cleanup-owner', { fixtureId: id }); await f.native.cleanup(r.req); r.finish();
            if (kind === 'inflight') f.request('/test/capabilities', {}, {}, { method: 'GET' });
            const final = f.request('/test/native/finalize', {}, { 'x-st-native-stage': 'cleanup',
                'x-st-native-finalize-key': kind === 'key' ? 'f'.repeat(48) : finalizeKey });
            await assert.rejects(final.dispatch(), /not ready/);
        }
        assert.strictEqual(f.complete(), false);
    });
});

test('ordinary login delegates unchanged; native enablement/private directory/control are mandatory', async () => {
    await fixture(async f => {
        const r = f.request('/login', { userId: 'ordinary' }, {}, { untagged: true });
        const session = {};
        assert.strictEqual(await f.native.login(r.req, r.res, async () => session), session);
        assert.deepStrictEqual(r.res.headers, {});
        assert.strictEqual(fs.existsSync(path.join(f.directory, 'events.jsonl')), false);
    });
    for (const kind of ['control', 'permission', 'symlink']) await fixture(async f => {
        const file = path.join(f.directory, 'control.json');
        if (kind === 'control') fs.writeFileSync(file, JSON.stringify({ ...f.control, runId: 'f'.repeat(48) }));
        if (kind === 'permission') fs.chmodSync(file, 0o644);
        if (kind === 'symlink') { fs.renameSync(file, file + '.real'); fs.symlinkSync(file + '.real', file); }
        const r = f.request('/test/native/challenge', { challenge });
        assert.strictEqual(r.accepted, false);
        assert.strictEqual(fs.existsSync(path.join(f.directory, 'events.jsonl')), false);
    });
});

test('abandoned actual login and cleanup responses retain scope but permanently fail row completion', async () => {
    for (const kind of ['login', 'cleanup']) await fixture(async f => {
        await f.start(); const id = await f.provision();
        const r = f.request('/login', { userId: id }, { 'x-st-native-fixture-id': id });
        await f.native.login(r.req, r.res, async () => ({ getUserId: () => id, getHandle: () => 'lost-login', getTenantId: () => 'public' }));
        assert.ok(f.h.owners.ownsSession(id, 'lost-login'));
        if (kind === 'login') r.res.emit('close');
        else {
            r.finish();
            const cleanup = f.request('/test/cleanup-owner', { fixtureId: id });
            await f.native.cleanup(cleanup.req);
            cleanup.res.emit('close');
        }
        assert.strictEqual(await f.h.owners.cleanupById(id), true);
        assert.strictEqual(await f.h.owners.cleanupById('unrelated'), false);
        assert.ok(f.h.owners.ownsSession(id, 'lost-login'));
        assert.strictEqual(f.complete(), false);
        assert.ok(f.events().some(event => event.type === 'refusal' && event.reason === 'CLOSED_BEFORE_FINISH'));
        const retry = f.request('/test/cleanup-owner', { fixtureId: id });
        assert.strictEqual(retry.accepted, false); retry.finish();
    });
});

test('native owner nonce and tombstones cannot authorize another row or resurrect cleanup ownership', () => fixture(async f => {
    await f.start(); const id = await f.provision();
    assert.throws(() => f.h.owners.assertNativeOwner(id, 'f'.repeat(48)), /live mapped fixture/);
    assert.throws(() => f.h.owners.bindNativeOwner(id, nonce), /Cannot bind/);
    const handle = await f.login(id);
    await f.h.owners.cleanupById(id);
    assert.ok(f.h.owners.ownsSession(id, handle));
    await assert.rejects(f.h.owners.nativeLogin(id, id, nonce, () => { throw new Error('must not execute'); }), /live mapped fixture/);
    await assert.rejects(f.h.owners.nativeSessionState(id, handle, nonce, () => { throw new Error('must not execute'); }), /live mapped fixture/);
}));

test('finish without close cannot finalize; close after finish seals only when no other work remains', () => fixture(async f => {
    await f.start(); const id = await f.provision();
    const cleanup = f.request('/test/cleanup-owner', { fixtureId: id });
    await f.native.cleanup(cleanup.req); cleanup.finish();
    const final = f.request('/test/native/finalize', {}, { 'x-st-native-stage': 'cleanup', 'x-st-native-finalize-key': finalizeKey });
    let handler;
    f.native.install({ post: (route, selected) => { if (route === '/test/native/finalize') handler = selected; } });
    handler(final.req, final.res, error => { throw error; });
    assert.strictEqual(f.complete(), false);
    final.res.emit('finish');
    assert.strictEqual(f.complete(), false);
    final.res.emit('close');
    assert.strictEqual(f.complete(), true);
}));

test('disabled native handler cannot emit evidence even with valid supervisor files', () => fixture(async f => {
    const disabled = nativeEvidence({ owners: f.h.owners, Session: {}, env: { ...f.env, CORE_PROTOCOL_TESTS: '0' }, snapshot: () => { throw new Error('must not inspect'); } });
    const req = new EventEmitter();
    req.rawHeaders = ['x-st-native-nonce', nonce];
    const res = { status: code => { assert.strictEqual(code, 409); return res; }, json() {} };
    disabled.middleware(req, res, () => { throw new Error('must not accept'); });
    assert.strictEqual(fs.existsSync(path.join(f.directory, 'events.jsonl')), false);
}));

test('cleanup-all refuses an in-progress provisioning session instead of deleting its owner underneath it', () => fixture(async f => {
    await f.start();
    let release;
    const r = f.request('/test/email-change-owner', { requestId: nonce + '-owner' });
    const pending = f.native.provision(r.req, () => f.h.owners.create('race@example.com', id =>
        new Promise(resolve => { release = () => resolve({ getUserId: () => id, getHandle: () => 'provisioning' }); })));
    while (!release) await new Promise(resolve => setImmediate(resolve));
    await assert.rejects(f.h.owners.cleanupAll(), /remain pending/);
    assert.strictEqual(f.h.users.size, 1);
    release();
    const result = await pending; r.finish();
    assert.strictEqual(await f.h.owners.cleanupById(result.owner.id), true);
    assert.strictEqual(f.h.users.size, 0);
}));

test('partial user cleanup cannot be reinterpreted as signout revocation before its error response finishes', () => fixture(async f => {
    await f.start(); const id = await f.provision(); const handle = await f.login(id);
    f.h.faults.deleteMapping = true;
    const cleanup = f.request('/test/cleanup-owner', { fixtureId: id });
    await assert.rejects(f.native.cleanup(cleanup.req), /mapping delete/);
    assert.strictEqual(f.h.users.size, 0, 'Fault occurs after actual owned-user deletion in the host SDK double');
    const state = f.request('/test/owner-session-state', { fixtureId: id, handle });
    await assert.rejects(f.native.sessionState(state.req), /live mapped fixture/);
    assert.deepStrictEqual(f.reads, []);
    state.res.statusCode = 500; state.finish();
    cleanup.res.statusCode = 500; cleanup.finish();
    f.h.faults.deleteMapping = false;
    assert.strictEqual(await f.h.owners.cleanupById(id), true, 'Known-owner cleanup remains retryable');
}));

test('relogin binds another actual session to same fixture and records received FDI without substitution', () => fixture(async f => {
    const challengeRequest = f.request('/test/native/challenge', { challenge }, { 'fdi-version': '1.18' });
    await challengeRequest.dispatch();
    const id = await f.provision();
    const first = await f.login(id);
    const second = await f.login(id);
    assert.notStrictEqual(first, second);
    assert.ok(f.h.owners.ownsSession(id, first));
    assert.ok(f.h.owners.ownsSession(id, second));
    f.sessions.delete(first); f.sessions.delete(second);
    for (const handle of [first, second]) {
        const r = f.request('/test/owner-session-state', { fixtureId: id, handle });
        assert.deepStrictEqual(await f.native.sessionState(r.req), { owned: true, active: false }); r.finish();
    }
    assert.strictEqual(f.events().find(value => value.type === 'request').headers['fdi-version'], '1.18');
}));

test('finalizer admission fences later requests even when they close before the finalizer', async () => {
    for (const kind of ['tagged', 'untagged', 'second-finalizer']) await fixture(async f => {
        await f.start(); const id = await f.provision();
        const cleanup = f.request('/test/cleanup-owner', { fixtureId: id });
        await f.native.cleanup(cleanup.req); cleanup.finish();
        const final = f.request('/test/native/finalize', {}, { 'x-st-native-stage': 'cleanup', 'x-st-native-finalize-key': finalizeKey });
        let handler;
        f.native.install({ post: (route, selected) => { if (route === '/test/native/finalize') handler = selected; } });
        handler(final.req, final.res, error => { throw error; });
        assert.deepStrictEqual(final.res.body, { finalizing: true });
        const late = kind === 'second-finalizer'
            ? f.request('/test/native/finalize', {}, { 'x-st-native-stage': 'cleanup', 'x-st-native-finalize-key': finalizeKey })
            : f.request('/test/capabilities', {}, {}, { method: 'GET', untagged: kind === 'untagged' });
        assert.strictEqual(late.accepted, kind === 'untagged', 'Ordinary request behavior remains unchanged; tagged requests are refused');
        late.finish();
        final.finish();
        assert.strictEqual(f.complete(), false, kind);
        assert.ok(f.events().some(event => event.type === 'refusal'));
        assert.ok(!f.events().some(event => event.type === 'row-complete'));
    });
});

function loginRouter(native, Session, multitenancySupported = true, accountLinkingSupported = true) {
    const source = fs.readFileSync(path.join(__dirname, 'index.js'), 'utf8');
    const start = source.indexOf('app.post("/login",');
    const end = source.indexOf('app.post("/startst",', start);
    assert.ok(start >= 0 && end > start);
    const router = require('express').Router();
    require('vm').runInNewContext(source.slice(start, end), { app: router, coreProtocol: { native }, Session,
        multitenancySupported, accountLinkingSupported, SuperTokens: { convertToRecipeUserId: id => ({ recipeUserId: id }) } });
    return router;
}

async function dispatchLogin(router, request) {
    request.req.url = '/login';
    const errors = [];
    await new Promise((resolve, reject) => {
        request.res.send = body => { request.res.body = body; resolve(); return request.res; };
        router.use((error, req, res, next) => { errors.push(error); res.status(500).send('host-error-handler'); });
        router.handle(request.req, request.res, error => reject(error || new Error('Login route did not respond')));
    });
    request.finish();
    return errors;
}

test('actual index login registration forwards native/Core rejections through Express4 router without listener', { timeout: 3000 }, async () => {
    for (const kind of ['wrong-owner', 'core-failure']) await fixture(async f => {
        await f.start(); const id = await f.provision();
        const original = new Error('Host Core failure');
        let calls = 0;
        const router = loginRouter(f.native, { createNewSession: async () => { calls++; throw original; } });
        const r = f.request('/login', { userId: id }, { 'x-st-native-fixture-id': kind === 'wrong-owner' ? 'foreign' : id });
        const errors = await dispatchLogin(router, r);
        assert.strictEqual(errors.length, 1);
        if (kind === 'core-failure') { assert.strictEqual(errors[0], original); assert.strictEqual(calls, 1); }
        else { assert.match(errors[0].message, /Unknown native fixture/); assert.strictEqual(calls, 0); }
        assert.strictEqual(r.res.statusCode, 500);
        assert.ok(f.events().some(event => event.type === 'refusal'));
        assert.strictEqual(f.complete(), false);
    });
});

test('actual index login route preserves ordinary response and SDK argument variants', { timeout: 3000 }, async () => {
    for (const [multi, linking] of [[false, false], [true, false], [true, true]]) await fixture(async f => {
        let args;
        const router = loginRouter(f.native, { createNewSession: async (...values) => {
            args = values; return { getUserId: () => 'ordinary-user' };
        } }, multi, linking);
        const r = f.request('/login', { userId: 'ordinary-user' }, {}, { untagged: true });
        assert.deepStrictEqual(await dispatchLogin(router, r), []);
        assert.strictEqual(r.res.body, 'ordinary-user');
        assert.strictEqual(r.res.statusCode, 200);
        assert.strictEqual(args[0], r.req); assert.strictEqual(args[1], r.res);
        assert.strictEqual(args.length, multi ? 4 : 3);
        if (multi) {
            assert.strictEqual(args[2], 'public');
            assert.deepStrictEqual(args[3], linking ? { recipeUserId: 'ordinary-user' } : 'ordinary-user');
        } else assert.strictEqual(args[2], 'ordinary-user');
        assert.deepStrictEqual(r.res.headers, {});
    });
});
