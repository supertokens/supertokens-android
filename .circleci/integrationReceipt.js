const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execFileSync } = require('child_process');
const { config, contextValid, validateEvidence, cleanupProof } = require('./integrationEvidence');

function validate(receipts, versions, sha, workflow, policy) {
    if (!Array.isArray(versions) || !versions.length || new Set(versions).size !== versions.length ||
        !policy || !Array.isArray(receipts) || receipts.length !== versions.length) throw new Error('Invalid FDI matrix/receipt set');
    for (const version of versions) {
        const matches = receipts.filter(receipt => receipt.fdi === version);
        if (matches.length !== 1) throw new Error('Missing/duplicate integration receipt for FDI ' + version);
        const receipt = matches[0];
        const context = receipt.context;
        contextValid(context);
        if (receipt.status !== 'requirements-satisfied' || context.fdi !== version || context.sha !== sha || context.workflow !== workflow ||
            context.policySha256 !== policy.policySha256) throw new Error('Stale/mismatched integration receipt');
        validateEvidence(receipt.evidence, context, policy.policy.required[version], policy.policy.upgrade[version]);
    }
}

function writeReceipt(context, evidence, policy, directory) {
    if (context.policySha256 !== policy.policySha256) throw new Error('Integration config changed');
    validateEvidence(evidence, context, policy.policy.required[context.fdi], policy.policy.upgrade[context.fdi]);
    fs.mkdirSync(directory, { recursive: true });
    fs.writeFileSync(path.join(directory, context.fdi + '.json'), JSON.stringify({ fdi: context.fdi,
        status: 'requirements-satisfied', context, evidence }, null, 2), { flag: 'wx' });
}

function main() {
    const [mode, directory, fdi] = process.argv.slice(2);
    const root = path.resolve(__dirname, '..');
    const sha = process.env.CIRCLE_SHA1;
    const workflow = process.env.CIRCLE_WORKFLOW_ID;
    if (!/^[a-f0-9]{40}$/.test(sha || '') || !workflow || execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim() !== sha) {
        throw new Error('Integration pipeline identity mismatch');
    }
    const policy = config(root);
    const evidenceRoot = path.join(root, 'owned-integration-evidence');
    const contextFile = path.join(evidenceRoot, 'release-context.json');
    if (mode === 'begin') {
        if (!policy.versions.includes(fdi)) throw new Error('Unknown FDI');
        const context = { schema: 1, fdi, sha, workflow, attempt: Number(process.env.CIRCLE_BUILD_NUM),
            backend: 'http://127.0.0.1:18081', core: 'http://127.0.0.1:19001',
            policySha256: policy.policySha256, sources: require('../testHelpers/testapp/artifactReceipt').sourceDigest(root),
            nonce: crypto.randomBytes(24).toString('hex') };
        contextValid(context);
        fs.mkdirSync(evidenceRoot, { recursive: true });
        fs.writeFileSync(contextFile, JSON.stringify(context), { flag: 'wx' });
    } else if (mode === 'write' || mode === 'finalize') {
        const context = JSON.parse(fs.readFileSync(contextFile));
        contextValid(context);
        if (context.fdi !== fdi || context.sha !== sha || context.workflow !== workflow || context.attempt !== Number(process.env.CIRCLE_BUILD_NUM)) {
            throw new Error('Integration attempt mismatch');
        }
        const files = fs.readdirSync(evidenceRoot).map(name => path.join(evidenceRoot, name, 'matrix/release-evidence.json')).filter(file => fs.existsSync(file));
        if (files.length !== 1) throw new Error('Missing/ambiguous matrix evidence');
        const evidence = JSON.parse(fs.readFileSync(files[0]));
        if (mode === 'finalize') {
            if (evidence.context.nonce !== context.nonce || evidence.context.attempt !== context.attempt) throw new Error('Stale cleanup context');
            evidence.cleanup = cleanupProof(path.dirname(path.dirname(files[0])), evidence.identity.runId, evidence.identity.instance);
            fs.writeFileSync(files[0], JSON.stringify(evidence, null, 2));
        } else writeReceipt(context, evidence, policy, directory);
    } else if (mode === 'verify') {
        validate(fs.readdirSync(directory).map(file => JSON.parse(fs.readFileSync(path.join(directory, file)))), policy.versions, sha, workflow, policy);
    } else throw new Error('Expected begin, finalize, write or verify');
}
module.exports = { validate, writeReceipt };
if (require.main === module) {
    try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
