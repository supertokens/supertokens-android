const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const crypto = require('node:crypto');

const secret = 'SECRET_TOKEN_and_private_path';
const source = fs.readFileSync(path.join(__dirname, 'accountLinkingQualification.js'), 'utf8');
const liveSource = fs.readFileSync(path.join(__dirname, 'accountLinking.live.test.js'), 'utf8');

function harness(failure, logs = []) {
    const env = { INSTALL_PATH: '/fixture/core' };
    if (failure === 'install_path') delete env.INSTALL_PATH;
    const fail = stage => { if (failure === stage) throw new Error(secret); };
    const fakeFs = {
        readFileSync(file) {
            if (file.endsWith('version.yaml')) {
                fail('version_file');
                return `${failure === 'core_version' ? '' : 'core_version: 8.0.7\n'}${failure === 'plugin_version' ? '' : 'plugin_interface_version: 5.1.0\n'}`;
            }
            if (file.endsWith('core-8.0.7.jar')) fail('core_artifact');
            else if (file.endsWith('plugin-interface-5.1.0.jar')) fail('plugin_artifact');
            else fail('qualifier_digest');
            return Buffer.from('fixture');
        }
    };
    const imports = {
        fs: fakeFs, path, crypto, assert: require('node:assert'),
        './coreRuntimeIdentity': {
            serverIdentity() { fail('backend_identity'); return { sdkImplementationSha256: 'sdk', expressRuntimeId: 'backend' }; },
            runtimeIdentity() { fail('core_runtime'); return 'core-runtime'; },
            effectiveArtifacts() { fail('effective_artifacts'); return { digest: 'classpath-digest' }; }
        },
        './accountLinkingSdk': { inspect() { fail('sdk_inventory'); return { inventory: '[]' }; } }
    };
    const module = { exports: {} };
    const context = { module, exports: module.exports, require(name) {
        if (name === 'supertokens-node/lib/build/version') { fail('sdk_version'); return { version: '16.7.6' }; }
        if (!(name in imports)) throw new Error('Unexpected import');
        return imports[name];
    }, __dirname: '/fixture/backend', process: { env }, Buffer,
    console: { log: (...args) => logs.push(args), error: (...args) => logs.push(args), warn: (...args) => logs.push(args) } };
    vm.runInNewContext(source, context, { filename: 'accountLinkingQualification.js' });
    return module.exports;
}

for (const stage of ['install_path', 'version_file', 'core_version', 'plugin_version', 'backend_identity', 'sdk_version', 'core_artifact', 'plugin_artifact', 'core_runtime', 'effective_artifacts', 'sdk_inventory', 'qualifier_digest']) {
    test(`unavailable ${stage} reports only its constant stage and remains fail-closed`, () => {
        const reports = [];
        assert.equal(harness(stage).identity(value => reports.push(value)), undefined);
        assert.deepEqual(reports, [stage]);
        assert.equal(JSON.stringify(reports).includes(secret), false);
    });
}

test('successful identity is unchanged and never reports', () => {
    const qualification = harness();
    const reports = [];
    const withoutReporter = qualification.identity();
    const withReporter = qualification.identity(value => reports.push(value));
    assert.deepEqual(withReporter, withoutReporter);
    const fileHash = crypto.createHash('sha256').update('fixture').digest('hex');
    const qualifierHash = crypto.createHash('sha256').update([
        'qualifyAccountLinking.js', 'accountLinkingPreflight.js', 'accountLinking.live.test.js',
        'accountLinkingQualification.js', 'coreRuntimeIdentity.js', 'accountLinkingSdk.js'
    ].map(name => `${name}:${fileHash}`).join('\n')).digest('hex');
    assert.deepEqual(JSON.parse(JSON.stringify(withReporter)), {
        sdkImplementationSha256: 'sdk', expressRuntimeId: 'backend', nodeSdkVersion: '16.7.6',
        coreVersion: '8.0.7', coreSha256: fileHash, pluginInterfaceVersion: '5.1.0',
        pluginInterfaceSha256: fileHash, coreRuntimeId: 'core-runtime',
        effectiveClasspathSha256: 'classpath-digest', sdkApiInventory: '[]', qualifierSha256: qualifierHash
    });
    assert.deepEqual(reports, []);
});

test('existing callers remain silent and reporter exceptions cannot change failure', () => {
    const logs = [];
    const qualification = harness('backend_identity', logs);
    assert.equal(qualification.identity(), undefined);
    assert.equal(qualification.identity(() => { throw new Error(secret); }), undefined);
    assert.deepEqual(logs, []);
});

test('live qualification reports unavailable stage and exits before preflight', async () => {
    let preflightCalls = 0;
    const errors = [];
    const fakeProcess = { env: { CORE_CONNECTION_URI: 'http://owned' }, exitCode: 0 };
    const imports = {
        assert: require('node:assert'), 'supertokens-node': {}, 'supertokens-node/recipe/session': {}, './qualifyAccountLinking': {}, 'node-fetch': {},
        './accountLinkingQualification': { identity(report) { report('core_runtime'); return undefined; } },
        './accountLinkingSdk': {}, './accountLinkingPreflight': () => { preflightCalls++; },
        'supertokens-node/lib/build/version': { cdiSupported: [] }
    };
    vm.runInNewContext(liveSource, { require(name) { if (!(name in imports)) throw new Error('Unexpected import'); return imports[name]; },
        process: fakeProcess, console: { error: message => errors.push(message) } });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(preflightCalls, 0);
    assert.equal(fakeProcess.exitCode, 1);
    assert.deepEqual(errors, ['Pair identity unavailable stage: core_runtime', 'Exact pair identity unavailable for this Core installation and backend instance']);
});
