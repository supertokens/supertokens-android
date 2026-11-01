const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawnSync } = require('child_process');
const { isDeepStrictEqual: equal } = require('util');
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
function check(value, message) { if (!value) throw new Error(message); }
function discover(root, config) {
    const build = config.nativeBuild;
    check(build && typeof build.path === 'string' && path.isAbsolute(build.path) &&
        /^[a-f0-9]{64}$/.test(build.sha256) && fs.realpathSync(build.path) === build.path &&
        fs.lstatSync(build.path).isFile() && hash(fs.readFileSync(build.path)) === build.sha256,
    'Missing/changed trusted native build pin');
    const leaseRoot = config.deviceLeaseRoot;
    check(typeof leaseRoot === 'string' && path.isAbsolute(leaseRoot) && fs.realpathSync(leaseRoot) === leaseRoot &&
        fs.statSync(leaseRoot).uid === process.getuid() && (fs.statSync(leaseRoot).mode & 0o077) === 0 &&
        typeof config.serial === 'string' && config.serial.length > 0, 'Canonical private device lease root/serial required');
    const script = 'import json,sys,shutil; from pathlib import Path; import native_device; c=json.load(sys.stdin); ' +
        't={"apksigner":native_device.signer_identity(c.get("apksigner","apksigner"))}; ' +
        '\nfor n in ["adb","aapt"]:\n p=Path(shutil.which(c.get(n,n))).resolve(); t[n]={"path":str(p),"sha256":native_device.sha(p)}' +
        '\nprint(json.dumps(t))';
    const result = spawnSync('python3', ['-c', script], { cwd: path.join(root, 'testHelpers/upgradeapp/tools'),
        input: JSON.stringify(config), encoding: 'utf8', timeout: 15000, env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' } });
    check(!result.error && result.status === 0, 'Native discovery failed: ' + (result.error || result.stderr));
    return { nativeSerial: config.serial, nativeLeaseRoot: leaseRoot, nativeTools: JSON.parse(result.stdout),
        nativeBuild: { path: build.path, sha256: build.sha256 } };
}
function freeze(root, owned, context, configFile) {
    const configPath = fs.realpathSync(configFile), bytes = fs.readFileSync(configPath), config = JSON.parse(bytes);
    const executor = discover(root, config);
    fs.writeFileSync(path.join(owned, 'native-executor.json'), JSON.stringify({ schema: 1, context, configPath,
        configSha256: hash(bytes), config, executor }), { flag: 'wx', mode: 0o600 });
    return executor;
}
function reconstruct(root, owned, context) {
    const record = JSON.parse(fs.readFileSync(path.join(owned, 'native-executor.json')));
    check(record.schema === 1 && equal(record.context, context) && fs.realpathSync(record.configPath) === record.configPath &&
        hash(fs.readFileSync(record.configPath)) === record.configSha256 &&
        equal(record.config, JSON.parse(fs.readFileSync(record.configPath))) && equal(record.executor, discover(root, record.config)),
    'Trusted native discovery/configuration drift');
    return { ...record.executor, nativePreflight: path.join(owned, 'native-preflight'), nativeLeaseClose: path.join(owned, 'native-lease-close') };
}
function assertHeld(expected) {
    const archive = expected.nativePreflight;
    check(fs.realpathSync(archive) === archive, 'Preflight archive path escaped');
    const receipt = JSON.parse(fs.readFileSync(path.join(archive, 'native-preflight.json')));
    check(receipt.kind === 'native-device-preflight' && receipt.binding.runId === expected.binding.runId &&
        receipt.binding.instance === expected.binding.instance && receipt.binding.preparationNonce === expected.preparationNonce &&
        equal(receipt.binding.toolchain.apksigner, expected.nativeTools.apksigner), 'Wrong pre-JVM native preflight');
    check(['context', 'mode', 'route', 'candidateAarSha256', 'publishedAarSha256', 'qualifiedIdentitySha256',
        'dependenciesSha256', 'nodeSdkSha256', 'coreSha256', 'coreManifestSha256', 'planSha256', 'executionSourcesSha256']
        .every(field => equal(receipt.binding[field], expected.binding[field])) &&
        Number.isSafeInteger(receipt.startedMs) && Number.isSafeInteger(receipt.endedMs) &&
        expected.startedMs <= receipt.startedMs && receipt.startedMs <= receipt.endedMs && receipt.endedMs <= Date.now() &&
        equal(fs.readdirSync(archive).sort(), [...Object.keys(receipt.files), 'native-preflight.json'].sort()),
    'Preflight binding/interval/manifest mismatch');
    for (const [name, digest] of Object.entries(receipt.files)) {
        check(/^[a-zA-Z0-9_.-]+$/.test(name) && !['.', '..'].includes(name) &&
            !fs.lstatSync(path.join(archive, name)).isSymbolicLink() && hash(fs.readFileSync(path.join(archive, name))) === digest,
        'Native preflight archive changed');
    }
    const journal = fs.readFileSync(path.join(archive, 'device-lease.jsonl'), 'utf8').trim().split('\n').map(JSON.parse);
    const leasePath = path.join(expected.nativeLeaseRoot, hash(expected.nativeSerial));
    check(equal(fs.readdirSync(leasePath).sort(), ['owner.json']), 'Device borrower still active before dispatch');
    check(journal.length === 1 && journal[0].action === 'acquired' && journal[0].path === leasePath &&
        fs.realpathSync(leasePath) === leasePath && fs.statSync(leasePath).ino === journal[0].inode &&
        !fs.lstatSync(path.join(leasePath, 'owner.json')).isSymbolicLink() &&
        hash(fs.readFileSync(path.join(leasePath, 'owner.json'))) === journal[0].ownerSha256 &&
        equal(fs.readFileSync(path.join(leasePath, 'owner.json')), fs.readFileSync(path.join(archive, 'device-lease-owner.json'))),
    'Selector device lease missing/replaced before JVM/native dispatch');
    return receipt;
}
function verifyClosed(expected) {
    if (expected.selector.mode === 'HEADER') return;
    const result = spawnSync('python3', [path.join(__dirname, 'nativeLease.py')], { input: JSON.stringify(expected),
        encoding: 'utf8', timeout: 60000, maxBuffer: 1024 * 1024, env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' } });
    check(!result.error && result.status === 0, 'Native lease cleanup not proven: ' + (result.error || result.stderr));
}
module.exports = { discover, freeze, reconstruct, assertHeld, verifyClosed };
