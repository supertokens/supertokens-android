const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');
const executor = require('./nativeExecutor');
const { toolsFixture } = require('./nativeHostFixtures');
const { nativeCommand, nativeInput, withDevicePreflight } = require('./runOwnedNativeUpgrade');

function fixture(t) {
    const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'native-executor-host-')));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const tools = toolsFixture(root), owned = path.join(root, 'owned');
    fs.mkdirSync(owned, { mode: 0o700 });
    const context = { backend: tools.config.backend }, binding = { context, mode: 'COOKIE', route: '/login',
        runId: 'a'.repeat(48), instance: 'b'.repeat(48), candidateAarSha256: 'c'.repeat(64) };
    executor.freeze(root, owned, context, tools.configFile);
    const expected = { ...executor.reconstruct(root, owned, context), binding, selector: binding,
        preparationNonce: 'd'.repeat(48), plan: { review: { reference: 'host-only' }, phaseVersions: {
            'COOKIE:/login': { publishedLogin: '2', publishedHandoff: { REFRESH: '5', SIGN_OUT: 'ABSENT' }, candidateRefresh: '5' } } } };
    return { root, owned, context, expected, ...tools };
}

test('discovery pins actual signer script/JAR and exporter independently rediscovers tools', t => {
    const f = fixture(t), actual = executor.reconstruct(f.root, f.owned, f.context);
    assert.equal(actual.nativeTools.adb.path, f.config.adb);
    assert.equal(actual.nativeTools.apksigner.path, f.config.apksigner);
    fs.appendFileSync(path.join(path.dirname(f.config.apksigner), 'lib/apksigner.jar'), 'changed');
    assert.throws(() => executor.reconstruct(f.root, f.owned, f.context), /drift/);
});

test('exporter retains executor build authority and rejects changed receipt bytes', t => {
    const f = fixture(t);
    assert.deepEqual(executor.reconstruct(f.root, f.owned, f.context).nativeBuild, f.config.nativeBuild);
    fs.appendFileSync(f.config.nativeBuild.path, 'changed');
    assert.throws(() => executor.reconstruct(f.root, f.owned, f.context), /trusted native build pin/);
});

test('discovery requires an independently supplied build pin; it never manufactures a digest', t => {
    const f = fixture(t);
    delete f.config.nativeBuild;
    assert.throws(() => executor.discover(f.root, f.config), /trusted native build pin/);
    f.config.nativeBuild = { path: f.expected.nativeBuild.path, sha256: '0'.repeat(64) };
    assert.throws(() => executor.discover(f.root, f.config), /trusted native build pin/);
});

test('exporter rejects trusted configuration replacement even with a valid new byte pin', t => {
    const f = fixture(t);
    const replacement = path.join(f.root, 'replacement-build'); fs.writeFileSync(replacement, 'unrelated');
    f.config.nativeBuild = { path: replacement, sha256: require('crypto').createHash('sha256').update('unrelated').digest('hex') };
    fs.writeFileSync(f.configFile, JSON.stringify(f.config));
    assert.throws(() => executor.reconstruct(f.root, f.owned, f.context), /configuration drift/);
});

test('native input and lifecycle argv carry discovered tools and one shared session', t => {
    const f = fixture(t), e = f.expected;
    const input = nativeInput(e.binding, e.plan, 'SIGN_OUT', e.preparationNonce, 'row', f.config.backend, e, 'lease');
    assert.equal(input.leaseNonce, 'lease');
    assert.deepEqual(input.toolchain.apksigner, e.nativeTools.apksigner);
    assert.equal(input.plan.phases.publishedHandoff, 'ABSENT');
    for (const lifecycle of ['preflight', 'row', 'close']) {
        const argv = nativeCommand(f.root, e.binding, 'input', 'output', f.config, e, lifecycle);
        for (const [flag, value] of [['--device-lease-root', e.nativeLeaseRoot], ['--apksigner', e.nativeTools.apksigner.path],
            ['--aapt', e.nativeTools.aapt.path], ['--native-build-receipt', e.nativeBuild.path],
            ['--native-build-sha256', e.nativeBuild.sha256]]) assert.equal(argv[argv.indexOf(flag) + 1], value);
        assert.equal(argv.includes('--preflight-only'), lifecycle === 'preflight');
        assert.equal(argv.includes('--release-lease-only'), lifecycle === 'close');
        if (lifecycle !== 'preflight') assert.equal(argv[argv.indexOf('--device-lease-session') + 1], e.nativePreflight);
    }
});

for (const conflict of ['lease', 'routing']) test('actual DeviceLease ' + conflict + ' conflict prevents JVM dispatch', async t => {
    const f = fixture(t), calls = [];
    const script = `import json,sys\nfrom pathlib import Path\nimport native_device\nc=json.load(sys.stdin)\nb=c['binding']\no=Path(c['output']); o.mkdir(mode=0o700)\nl=native_device.DeviceLease(c['root'],c['serial'],b,o)\n` +
        (conflict === 'lease' ? `l.path.mkdir(mode=0o700)\nl.acquire()\n` :
            `l.acquire()\ndef adb(args,name):\n return 'host tcp:18081 tcp:9999\\n'\nl.configure_route(adb)\n`);
    await assert.rejects(withDevicePreflight(f, () => { calls.push('jvm'); }, async argv => {
        calls.push('preflight');
        const binding = JSON.parse(fs.readFileSync(argv[argv.indexOf('--matrix-config') + 1]));
        const result = spawnSync('python3', ['-c', script], { cwd: path.join(f.root, 'testHelpers/upgradeapp/tools'),
            input: JSON.stringify({ binding, root: f.expected.nativeLeaseRoot, serial: f.expected.nativeSerial,
                output: f.expected.nativePreflight }), encoding: 'utf8', env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' } });
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, conflict === 'lease' ? /FileExistsError|lease/ : /routing|route|reverse/i);
        return result.status;
    }), /before JVM dispatch/);
    assert.deepEqual(calls, ['preflight']);
});

test('pre-dispatch ownership guard rejects an occupied borrower slot and altered preflight binding', t => {
    const f = fixture(t), e = f.expected;
    e.startedMs = 0;
    const input = nativeInput(e.binding, e.plan, 'REFRESH', e.preparationNonce, 'e'.repeat(48), f.config.backend, e, 'e'.repeat(48));
    const script = 'import json,sys,time; from pathlib import Path; import native_device; c=json.load(sys.stdin); ' +
        'o=Path(c["output"]); o.mkdir(mode=0o700); l=native_device.DeviceLease(c["root"],c["serial"],c["binding"],o); l.acquire(); ' +
        'r=dict(kind="native-device-preflight",binding=c["binding"],startedMs=0,endedMs=0,files={p.name:native_device.sha(p) for p in o.iterdir()}); ' +
        '(o/"native-preflight.json").write_text(json.dumps(r)); print(l.path)';
    const result = spawnSync('python3', ['-c', script], { cwd: path.join(f.root, 'testHelpers/upgradeapp/tools'), encoding: 'utf8',
        input: JSON.stringify({ binding: input, output: e.nativePreflight, root: e.nativeLeaseRoot, serial: e.nativeSerial }),
        env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' } });
    assert.equal(result.status, 0, result.stderr);
    assert.doesNotThrow(() => executor.assertHeld(e));
    const borrower = path.join(result.stdout.trim(), 'borrower.json');
    fs.writeFileSync(borrower, 'host competing borrower');
    assert.throws(() => executor.assertHeld(e), /borrower still active/);
    fs.unlinkSync(borrower);
    const summary = path.join(e.nativePreflight, 'native-preflight.json'), receipt = JSON.parse(fs.readFileSync(summary));
    receipt.binding.candidateAarSha256 = '0'.repeat(64); fs.writeFileSync(summary, JSON.stringify(receipt));
    assert.throws(() => executor.assertHeld(e), /binding\/interval\/manifest/);
});
