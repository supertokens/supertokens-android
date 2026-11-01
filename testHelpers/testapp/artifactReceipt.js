const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const artifacts = ['app/build/outputs/aar/app-debug.aar', 'app/build/upgradeHarness/upgrade-harness.jar',
    'app/build/legacy054/classes.jar', 'app/build/legacy054/driver'];
const inputs = ['app', 'buildSrc', 'local.properties', 'build.gradle', 'settings.gradle',
    'gradle.properties', 'gradle', 'gradlew', 'testHelpers/testapp'];
function digest(root, entries, sources = false) {
    const hash = crypto.createHash('sha256');
    function visit(relative) {
        const file = path.join(root, relative);
        if (!fs.existsSync(file)) { hash.update(relative + '\0missing\0'); return; }
        if (fs.statSync(file).isDirectory()) {
            for (const name of fs.readdirSync(file).sort()) {
                if (sources && ['build', '.gradle', '.integration-lock'].includes(name)) continue;
                visit(path.join(relative, name));
            }
        } else {
            hash.update(relative.split(path.sep).join('/') + '\0');
            hash.update(fs.readFileSync(file));
        }
    }
    entries.forEach(visit);
    return hash.digest('hex');
}
function sourceDigest(root) { return digest(root, inputs, true); }
function artifactDigest(root) {
    for (const relative of artifacts) {
        const file = path.join(root, relative);
        if (!fs.existsSync(file) || (fs.statSync(file).isDirectory() && !fs.readdirSync(file).length)) {
            throw new Error('Missing prepared artifact: ' + relative);
        }
    }
    return digest(root, artifacts);
}
function artifactHashes(root) {
    return Object.fromEntries(artifacts.map(relative => [relative, fs.statSync(path.join(root, relative)).isDirectory()
        ? digest(root, [relative]) : crypto.createHash('sha256').update(fs.readFileSync(path.join(root, relative))).digest('hex')]));
}
function validate(root, env = process.env) {
    const fail = () => { throw new Error('Missing, stale or mismatched preparation receipt. Use node testHelpers/testapp/runTests.js ordinary|retrofit|mutation|upgrade from the repository root; do not invoke testapp Gradle directly.'); };
    if (!env.ST_ARTIFACT_RECEIPT || !env.ST_ARTIFACT_NONCE) return fail();
    let receipt, active;
    try {
        receipt = JSON.parse(fs.readFileSync(env.ST_ARTIFACT_RECEIPT, 'utf8'));
        active = fs.readFileSync(path.join(root, '.integration-lock', 'nonce'), 'utf8');
    } catch (_) { return fail(); }
    if (receipt.schema !== 1 || receipt.root !== fs.realpathSync(root) || receipt.nonce !== env.ST_ARTIFACT_NONCE ||
        active !== receipt.nonce || receipt.javaHome !== (env.JAVA_HOME || '') ||
        receipt.sources !== sourceDigest(root) || receipt.artifacts !== artifactDigest(root)) return fail();
    return receipt;
}
module.exports = { sourceDigest, artifactDigest, artifactHashes, validate, artifacts };
if (require.main === module) validate(path.resolve(__dirname, '../..'));
