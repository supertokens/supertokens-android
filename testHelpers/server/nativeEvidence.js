const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const assert = require('assert');

const hex48 = value => typeof value === 'string' && /^[a-f0-9]{48}$/.test(value);
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const headerNames = ['x-st-test-run-id', 'x-st-native-nonce', 'x-st-native-stage', 'fdi-version',
    'x-st-native-fixture-id', 'x-st-native-finalize-key'];
const routes = new Set(['POST /test/native/challenge', 'POST /test/native/finalize', 'GET /test/capabilities',
    'GET /featureFlags', 'POST /test/email-change-owner', 'POST /login', 'POST /auth/session/refresh',
    'POST /auth/signout', 'GET /', 'POST /update-jwt', 'POST /test/owner-session-state', 'POST /test/cleanup-owner']);

module.exports = function nativeEvidence({ owners, Session, env = process.env, snapshot }) {
    const rows = new Map();
    const enabled = env.CORE_PROTOCOL_TESTS === '1' && hex48(env.ST_TEST_RUN_ID) && hex48(env.ST_TEST_INSTANCE_ID);

    function privatePath(file, directory = false) {
        const stat = fs.lstatSync(file);
        if (stat.isSymbolicLink() || (directory ? !stat.isDirectory() : !stat.isFile() || stat.nlink !== 1) ||
            stat.uid !== process.getuid() || (stat.mode & 0o077) !== 0 || fs.realpathSync(file) !== file) {
            throw new Error('Native evidence path must be canonical, private and owned');
        }
        return stat;
    }

    function readControl(nonce) {
        if (!enabled || !hex48(nonce) || !env.INSTALL_PATH) throw new Error('Owned native evidence disabled');
        const root = fs.realpathSync(env.INSTALL_PATH);
        const owner = JSON.parse(fs.readFileSync(path.join(root, 'owner.json'), 'utf8'));
        if (owner.root !== root || owner.runId !== env.ST_TEST_RUN_ID) throw new Error('Native supervisor owner mismatch');
        const base = path.join(root, 'native-evidence');
        const directory = path.join(base, nonce);
        privatePath(base, true);
        privatePath(directory, true);
        const file = path.join(directory, 'control.json');
        privatePath(file);
        const bytes = fs.readFileSync(file);
        const control = JSON.parse(bytes);
        if (Object.keys(control).sort().join(',') !== 'challenge,finalizeKey,instance,kind,nativeNonce,runId,schema') {
            throw new Error('Unexpected native control fields');
        }
        if (control.schema !== 1 || control.kind !== 'native-backend-control' || control.runId !== env.ST_TEST_RUN_ID ||
            control.instance !== env.ST_TEST_INSTANCE_ID || control.nativeNonce !== nonce ||
            !hex48(control.challenge) || !hex48(control.finalizeKey)) throw new Error('Native control mismatch');
        return { directory, control, controlHash: hash(bytes) };
    }

    function current(row) {
        const control = readControl(row.control.nativeNonce);
        if (control.controlHash !== row.controlHash || control.directory !== row.directory) throw new Error('Native control changed');
        const observed = snapshot();
        assert.deepStrictEqual(observed, row.snapshot, 'Native runtime changed');
        return observed;
    }

    function append(row, type, data = {}) {
        const file = path.join(row.directory, 'events.jsonl');
        const stat = privatePath(file);
        if (stat.ino !== row.inode || stat.dev !== row.device) throw new Error('Native event file replaced');
        const event = { schema: 1, runId: env.ST_TEST_RUN_ID, instance: env.ST_TEST_INSTANCE_ID,
            nativeNonce: row.control.nativeNonce, sequence: ++row.sequence, timeMs: Date.now(), type, ...data };
        const fd = fs.openSync(file, fs.constants.O_WRONLY | fs.constants.O_APPEND | fs.constants.O_NOFOLLOW);
        try {
            const opened = fs.fstatSync(fd);
            if (opened.ino !== stat.ino || opened.dev !== stat.dev) throw new Error('Native event file changed during open');
            fs.writeFileSync(fd, JSON.stringify(event) + '\n');
            fs.fsyncSync(fd);
        } finally { fs.closeSync(fd); }
    }

    function fail(row, requestId, reason) {
        row.failed = true;
        const completed = path.join(row.directory, 'complete.json');
        if (fs.existsSync(completed)) fs.unlinkSync(completed);
        append(row, 'refusal', { requestId, reason });
    }

    function load(nonce) {
        if (rows.has(nonce)) return rows.get(nonce);
        if ([...rows.values()].some(row => !row.sealed)) throw new Error('Native rows must be serialized');
        const control = readControl(nonce);
        const observed = snapshot();
        if (!observed || !observed.identity || !/^[a-f0-9]{64}$/.test(observed.dependenciesSha256) ||
            !/^[a-f0-9]{64}$/.test(observed.coreManifestSha256) ||
            observed.qualifiedIdentitySha256 !== hash(JSON.stringify(observed.identity))) throw new Error('Native runtime identity unavailable');
        const file = path.join(control.directory, 'events.jsonl');
        const fd = fs.openSync(file, 'wx', 0o600);
        const stat = fs.fstatSync(fd);
        fs.closeSync(fd);
        const row = { ...control, snapshot: observed, inode: stat.ino, device: stat.dev, sequence: 0, requests: 0,
            inflight: new Set(), fixtures: new Map(), pending: 0, creationAttempted: false, challenged: false, failed: false, finalizing: false, sealed: false };
        rows.set(nonce, row);
        append(row, 'row-open', observed);
        return row;
    }

    function headers(req) {
        const values = {};
        for (const name of headerNames) {
            const found = [];
            for (let i = 0; i < req.rawHeaders.length; i += 2) {
                if (req.rawHeaders[i].toLowerCase() === name) found.push(req.rawHeaders[i + 1]);
            }
            if (found.length > 1) throw new Error('Duplicate native correlation header');
            values[name] = found[0];
        }
        return values;
    }

    function observe(req, res, row, received) {
        const requestId = ++row.requests;
        const publicHeaders = { ...received };
        delete publicHeaders['x-st-native-finalize-key'];
        append(row, 'request', { requestId, method: req.method, path: req.path, headers: publicHeaders });
        row.inflight.add(requestId);
        let finished = false;
        const context = { row, requestId, received, finalize: false,
            record: (type, data) => { current(row); append(row, type, { requestId, ...data }); } };
        req.once('aborted', () => fail(row, requestId, 'REQUEST_ABORTED'));
        res.once('finish', () => {
            finished = true;
            try {
                current(row);
                append(row, 'response-finish', { requestId, statusCode: res.statusCode });
                if (res.statusCode < 200 || res.statusCode >= 300) fail(row, requestId, 'HTTP_STATUS');
            } catch (_) { fail(row, requestId, 'FINISH_IDENTITY_OR_WRITE_FAILURE'); }
        });
        res.once('close', () => {
            try {
                append(row, 'response-close', { requestId, afterFinish: finished });
                row.inflight.delete(requestId);
                if (!finished) fail(row, requestId, 'CLOSED_BEFORE_FINISH');
                if (context.finalize && finished && !row.failed && row.inflight.size === 0 && row.pending === 0) {
                    const identity = current(row);
                    append(row, 'row-complete', identity);
                    const complete = { schema: 1, kind: 'native-backend-complete', runId: env.ST_TEST_RUN_ID,
                        instance: env.ST_TEST_INSTANCE_ID, nativeNonce: row.control.nativeNonce,
                        eventsSha256: hash(fs.readFileSync(path.join(row.directory, 'events.jsonl'))),
                        eventCount: row.sequence, ...identity };
                    const fd = fs.openSync(path.join(row.directory, 'complete.json'), 'wx', 0o600);
                    try {
                        fs.writeFileSync(fd, JSON.stringify(complete) + '\n');
                        fs.fsyncSync(fd);
                    } finally { fs.closeSync(fd); }
                    row.sealed = true;
                }
            } catch (_) { fail(row, requestId, 'CLOSE_OR_FINALIZATION_FAILURE'); }
        });
        return context;
    }

    function middleware(req, res, next) {
        let row;
        let context;
        try {
            const tagged = req.rawHeaders.some((value, index) => index % 2 === 0 && value.toLowerCase().startsWith('x-st-native-'));
            if (!tagged) {
                for (const active of rows.values()) {
                    if (!active.sealed) {
                        const uncorrelated = observe(req, res, active, {});
                        fail(active, uncorrelated.requestId, 'UNCORRELATED_REQUEST');
                    }
                }
                return next();
            }
            // Select only an exact nonce; duplicated correlation values are rejected below.
            const nonceIndex = req.rawHeaders.findIndex((value, index) => index % 2 === 0 && value.toLowerCase() === 'x-st-native-nonce');
            const nonce = nonceIndex === -1 ? undefined : req.rawHeaders[nonceIndex + 1];
            row = load(nonce);
            const received = headers(req);
            context = observe(req, res, row, received);
            if (row.sealed || row.failed || row.finalizing) throw new Error('Native row closed, finalizing or failed');
            if (received['x-st-test-run-id'] !== env.ST_TEST_RUN_ID ||
                !['seed', 'check-initial', 'cleanup'].includes(received['x-st-native-stage']) ||
                !/^\d+\.\d+$/.test(received['fdi-version'] || '')) throw new Error('Native request headers invalid');
            if (!routes.has(req.method + ' ' + req.path)) throw new Error('Native route not allowed');
            current(row);
            if (req.path !== '/test/native/challenge' && !row.challenged) throw new Error('Native challenge required');
            req.nativeEvidence = context;
            next();
        } catch (_) {
            if (row) fail(row, context?.requestId, 'INVALID_NATIVE_REQUEST');
            else for (const active of rows.values()) {
                if (!active.sealed) fail(active, undefined, 'UNATTRIBUTED_NATIVE_REQUEST');
            }
            res.status(409).json({ status: 'NATIVE_REQUEST_REFUSED' });
        }
    }

    function context(req) {
        if (!req.nativeEvidence) throw new Error('Native observed request required');
        const value = req.nativeEvidence;
        if (value.row.failed || value.row.sealed) throw new Error('Native row unavailable');
        current(value.row);
        return value;
    }

    function install(app) {
        app.post('/test/native/challenge', (req, res, next) => {
            try {
                const c = context(req);
                if (req.body.challenge !== c.row.control.challenge) throw new Error('Native challenge mismatch');
                const result = { schema: 1, kind: 'native-backend-challenge', runId: env.ST_TEST_RUN_ID,
                    instance: env.ST_TEST_INSTANCE_ID, nativeNonce: c.row.control.nativeNonce,
                    challenge: c.row.control.challenge, ...current(c.row) };
                c.record('challenge', result);
                c.row.challenged = true;
                res.json(result);
            } catch (error) { next(error); }
        });
        app.post('/test/native/finalize', (req, res, next) => {
            try {
                const c = context(req);
                if (c.received['x-st-native-finalize-key'] !== c.row.control.finalizeKey ||
                    c.received['x-st-native-stage'] !== 'cleanup' || c.row.inflight.size !== 1 || c.row.pending ||
                    !c.row.creationAttempted || !c.row.fixtures.size || [...c.row.fixtures.values()].some(value => !value.cleaned)) {
                    throw new Error('Native row not ready for owner finalization');
                }
                c.row.finalizing = true;
                c.finalize = true;
                res.json({ finalizing: true });
            } catch (error) { next(error); }
        });
    }

    async function provision(req, create) {
        if (!req.nativeEvidence) return create();
        const c = context(req);
        if (c.row.creationAttempted || req.body.requestId !== c.row.control.nativeNonce + '-owner') throw new Error('Native creation retry or request ID mismatch');
        c.row.creationAttempted = true;
        c.row.pending++;
        try {
            const result = await create();
            owners.bindNativeOwner(result.owner.id, c.row.control.nativeNonce);
            c.row.fixtures.set(result.owner.id, { cleaned: false });
            c.record('fixture-created', { fixtureId: result.owner.id, userId: result.session.getUserId(),
                provisioningHandle: result.session.getHandle(), creationRequestId: req.body.requestId });
            c.row.pending--;
            return result;
        } catch (error) { fail(c.row, c.requestId, 'CREATION_UNRESOLVED'); throw error; }
    }

    async function login(req, res, create) {
        if (!req.nativeEvidence) return create();
        const c = context(req);
        const id = c.received['x-st-native-fixture-id'];
        if (!c.row.fixtures.has(id)) throw new Error('Unknown native fixture');
        c.row.pending++;
        try {
            const session = await owners.nativeLogin(id, req.body.userId, c.row.control.nativeNonce, create);
            c.record('login-session', { fixtureId: id, userId: session.getUserId(), handle: session.getHandle() });
            res.setHeader('x-st-native-session-handle', session.getHandle());
            c.row.pending--;
            return session;
        } catch (error) { fail(c.row, c.requestId, 'LOGIN_UNRESOLVED'); throw error; }
    }

    async function sessionState(req) {
        const c = context(req);
        const { fixtureId, handle } = req.body;
        if (!c.row.fixtures.has(fixtureId)) throw new Error('Unknown native fixture');
        const session = await owners.nativeSessionState(fixtureId, handle, c.row.control.nativeNonce, value => Session.getSessionInformation(value));
        const result = { owned: true, active: session !== undefined };
        c.record('session-state', { fixtureId, handle, active: result.active });
        return result;
    }

    async function cleanup(req) {
        const c = context(req);
        const { fixtureId } = req.body;
        const bound = c.row.fixtures.get(fixtureId);
        if (!bound) throw new Error('Unknown native fixture');
        if (!bound.cleanupStarted) owners.assertNativeOwner(fixtureId, c.row.control.nativeNonce);
        bound.cleanupStarted = true;
        const cleaned = await owners.cleanupById(fixtureId);
        if (!cleaned) throw new Error('Native cleanup not acknowledged');
        bound.cleaned = true;
        c.record('owner-cleanup', { fixtureId, cleaned });
        return { cleaned };
    }

    return { middleware, install, provision, login, sessionState, cleanup };
};
