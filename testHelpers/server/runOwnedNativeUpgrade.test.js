const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { nativeInput, runChild, runNativeRows } = require('./runOwnedNativeUpgrade');
const root = path.resolve(__dirname, '../..');

test('concrete dispatch waits for REFRESH before SIGN_OUT and stops on failure', async () => {
    let release;
    const pending = new Promise(resolve => release = resolve), called = [];
    const work = runNativeRows(['REFRESH', 'SIGN_OUT'], async flow => { called.push(flow); if (flow === 'REFRESH') await pending; });
    await Promise.resolve(); assert.deepEqual(called, ['REFRESH']); release(); await work;
    assert.deepEqual(called, ['REFRESH', 'SIGN_OUT']);
    called.length = 0;
    await assert.rejects(runNativeRows(['REFRESH', 'SIGN_OUT'], async flow => { called.push(flow); throw new Error('failed'); }), /failed/);
    assert.deepEqual(called, ['REFRESH']);
    await assert.rejects(runNativeRows(['LOGIN'], async () => {}), /Unapproved/);
});

test('native input selects exact per-flow handoff including ABSENT; never scalar/default', () => {
    const binding = { mode: 'COOKIE', route: '/login', context: { fdi: '1.16' } };
    const plan = { review: { reference: 'synthetic-only' }, phaseVersions: { 'COOKIE:/login': {
        publishedLogin: '2', publishedHandoff: { LOGIN: '2', REFRESH: '5', PAYLOAD_UPDATE: '2', SIGN_OUT: 'ABSENT' }, candidateRefresh: '5' } } };
    const executor = { nativeTools: { apksigner: { path: '/host/signer' } } };
    assert.equal(nativeInput(binding, plan, 'REFRESH', 'p', 'n', 'device', executor, 'lease').plan.phases.publishedHandoff, '5');
    assert.equal(nativeInput(binding, plan, 'SIGN_OUT', 'p', 'n', 'device', executor, 'lease').plan.phases.publishedHandoff, 'ABSENT');
    assert.throws(() => nativeInput(binding, null, 'REFRESH', 'p', 'n', 'device'));
});

for (const interruption of ['timeout', 'SIGTERM', 'SIGINT']) test('blocked startup drains before readiness: ' + interruption, async t => {
    const { EventEmitter } = require('events');
    const directory = fs.mkdtempSync(path.join(root, 'supervisor-host-'));
    t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
    const signals = new EventEmitter(), events = [];
    let ready, timeout;
    class Group extends require('./ownedCommandGroup').OwnedCommandGroup {
        constructor(runId, evidence) {
            super(runId, evidence);
            this.owner.start = () => new Promise(resolve => { ready = () => resolve({ send: () => events.push('launched') }); });
        }
        cancelStart() { super.cancelStart(); events.push('cancel'); }
        async drain() { events.push('drain'); }
        verifyGroup() { events.push('late-group-validation'); }
    }
    const work = runChild(['host-double'], root, { ST_TEST_RUN_ID: 'c'.repeat(48) }, path.join(directory, 'log'), 1,
        { Group, signals, setTimeout: callback => { timeout = callback; return 1; }, clearTimeout: () => {} });
    if (interruption === 'timeout') timeout(); else signals.emit(interruption);
    await assert.rejects(work, /timed out|interrupted/);
    assert.deepEqual(events, ['cancel', 'drain']);
    ready(); await Promise.resolve(); await Promise.resolve();
    assert.deepEqual(events, ['cancel', 'drain']);
    assert.equal(signals.listenerCount('SIGTERM') + signals.listenerCount('SIGINT'), 0);
});

test('concrete child supervision retains actual nonzero exit and archives real drain', async t => {
    const directory = fs.mkdtempSync(path.join(root, 'supervisor-host-'));
    t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
    const log = path.join(directory, 'process-tree.jsonl');
    const exit = await runChild([process.execPath, '-e', 'process.exit(7)'], root,
        { ...process.env, ST_TEST_RUN_ID: 'a'.repeat(48) }, log, 10000);
    assert.equal(exit, 7);
    const events = fs.readFileSync(log, 'utf8').trim().split('\n').map(JSON.parse);
    assert.equal(events.filter(event => event.action === 'DRAINED').length, 1);
});

test('concrete child timeout rejects and drains host worker', async t => {
    const directory = fs.mkdtempSync(path.join(root, 'supervisor-host-'));
    t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
    const log = path.join(directory, 'process-tree.jsonl');
    await assert.rejects(runChild([process.execPath, '-e', 'setInterval(() => {}, 1000)'], root,
        { ...process.env, ST_TEST_RUN_ID: 'b'.repeat(48) }, log, 700), /timed out/);
    assert.ok(fs.readFileSync(log, 'utf8').includes('DRAINED'));
});
