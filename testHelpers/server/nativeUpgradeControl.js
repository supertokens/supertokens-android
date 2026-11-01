const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { isDeepStrictEqual: equal } = require('util');
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const nonce = () => crypto.randomBytes(24).toString('hex');
function check(value, message) { if (!value) throw new Error(message); }
function privateFile(file) {
    const stat = fs.lstatSync(file);
    check(stat.isFile() && !stat.isSymbolicLink() && (stat.mode & 0o077) === 0 &&
        (typeof process.getuid !== 'function' || stat.uid === process.getuid()), 'Non-private backend evidence');
    return fs.readFileSync(file);
}
function rowDirectory(owned, nativeNonce) {
    check(/^[a-f0-9]{48}$/.test(nativeNonce || '') && fs.realpathSync(owned) === owned, 'Invalid owned native directory');
    const directory = path.join(owned, 'native-evidence', nativeNonce);
    check(fs.realpathSync(directory) === directory && (fs.statSync(directory).mode & 0o077) === 0, 'Native evidence directory escaped/not private');
    return directory;
}
function createControl(owned, binding, nativeNonce) {
    const owner = JSON.parse(privateFile(path.join(owned, 'owner.json')));
    check(owner.root === owned && owner.runId === binding.runId && /^[a-f0-9]{48}$/.test(nativeNonce), 'Wrong native owner');
    const parent = path.join(owned, 'native-evidence');
    if (!fs.existsSync(parent)) fs.mkdirSync(parent, { mode: 0o700 });
    check(fs.realpathSync(parent) === parent && (fs.statSync(parent).mode & 0o077) === 0, 'Native evidence parent escaped/not private');
    fs.mkdirSync(path.join(parent, nativeNonce), { mode: 0o700 });
    const control = { schema: 1, kind: 'native-backend-control', runId: binding.runId, instance: binding.instance,
        nativeNonce, challenge: nonce(), finalizeKey: nonce() };
    fs.writeFileSync(path.join(rowDirectory(owned, nativeNonce), 'control.json'), JSON.stringify(control), { flag: 'wx', mode: 0o600 });
    return control;
}
function verifiedFiles(owned, binding, nativeNonce) {
    const directory = rowDirectory(owned, nativeNonce);
    const events = privateFile(path.join(directory, 'events.jsonl'));
    const bytes = privateFile(path.join(directory, 'complete.json')), completed = JSON.parse(bytes);
    const rows = events.toString().trim().split('\n').map(line => JSON.parse(line));
    check(completed.schema === 1 && completed.kind === 'native-backend-complete' && completed.runId === binding.runId &&
        completed.instance === binding.instance && completed.nativeNonce === nativeNonce &&
        completed.eventsSha256 === hash(events) && completed.eventCount === rows.length &&
        completed.qualifiedIdentitySha256 === binding.qualifiedIdentitySha256 &&
        completed.dependenciesSha256 === binding.dependenciesSha256 && completed.coreManifestSha256 === binding.coreManifestSha256 &&
        rows.length > 0 && rows[rows.length - 1].type === 'row-complete' && rows.every((row, index) =>
            row.sequence === index + 1 && row.runId === binding.runId && row.instance === binding.instance && row.nativeNonce === nativeNonce),
    'Backend finalization/hash/identity mismatch');
    return { events, completed: bytes };
}
async function finalize(owned, binding, nativeNonce, archive, fetch) {
    const control = JSON.parse(privateFile(path.join(rowDirectory(owned, nativeNonce), 'control.json')));
    check(control.runId === binding.runId && control.instance === binding.instance && control.nativeNonce === nativeNonce, 'Finalize control owner mismatch');
    const response = await fetch(binding.context.backend + '/test/native/finalize', { method: 'POST', timeout: 30000,
        headers: { 'content-type': 'application/json', 'x-st-test-run-id': binding.runId, 'x-st-native-nonce': nativeNonce,
            'x-st-native-stage': 'cleanup', 'fdi-version': binding.context.fdi, 'x-st-native-finalize-key': control.finalizeKey }, body: '{}' });
    check(response.ok && equal(await response.json(), { finalizing: true }), 'Backend rejected native finalization');
    const complete = path.join(rowDirectory(owned, nativeNonce), 'complete.json');
    const deadline = Date.now() + 30000;
    while (!fs.existsSync(complete) && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 20));
    const files = verifiedFiles(owned, binding, nativeNonce);
    fs.mkdirSync(path.dirname(archive), { recursive: true, mode: 0o700 });
    fs.mkdirSync(archive, { mode: 0o700 });
    check(fs.realpathSync(archive) === archive, 'Native backend archive path escaped');
    for (const [name, bytes] of [['events.jsonl', files.events], ['complete.json', files.completed]]) {
        fs.writeFileSync(path.join(archive, name), bytes, { flag: 'wx', mode: 0o600 });
    }
}
function verifyBackendArchive(owned, binding, nativeNonce, archive) {
    check(fs.realpathSync(archive) === archive, 'Native backend archive path escaped');
    const files = verifiedFiles(owned, binding, nativeNonce);
    check(equal(privateFile(path.join(archive, 'events.jsonl')), files.events) &&
        equal(privateFile(path.join(archive, 'complete.json')), files.completed), 'Backend evidence changed after native writer drain');
}
module.exports = { createControl, finalize, verifyBackendArchive };
