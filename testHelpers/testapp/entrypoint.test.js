const assert = require('assert');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawnSync } = require('child_process');
const { commands } = require('./runTests');
const receipt = require('./artifactReceipt');
const { accept, methods } = require('./mutationAcceptance');
const { environment } = require('../server/ordinaryEnvironment');

const temporary = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'entrypoint-controls-')));
function write(root, file, text) {
    const target = path.join(root, file);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, text);
}
try {
    const root = path.join(temporary, 'repo');
    fs.mkdirSync(root);
    for (const file of ['runTests.js', 'artifactReceipt.js', 'mutationAcceptance.js', 'exposureAcceptance.js']) {
        write(root, 'testHelpers/testapp/' + file, fs.readFileSync(path.join(__dirname, file)));
    }
    for (const file of ['app/build.gradle', 'build.gradle', 'gradle/wrapper/gradle-wrapper.properties',
        'testHelpers/testapp/build.gradle', 'testHelpers/testapp/gradle/wrapper/gradle-wrapper.properties']) write(root, file, 'version=control');
    write(root, 'app/src/source.java', 'source');
    write(root, 'gradlew', `#!${process.execPath}
const fs = require('fs'), path = require('path');
fs.appendFileSync(${JSON.stringify(path.join(temporary, 'commands.jsonl'))}, JSON.stringify({ cwd: process.cwd(), args: process.argv.slice(2) }) + '\\n');
for (const file of ['app/build/outputs/aar/app-debug.aar','app/build/upgradeHarness/upgrade-harness.jar','app/build/legacy054/classes.jar','app/build/legacy054/driver/Driver.class']) {
    fs.mkdirSync(path.dirname(file), { recursive:true }); fs.writeFileSync(file, 'fresh');
}
if (process.env.FAIL_PREP) process.exit(7);
if (process.env.CHANGE_SOURCE) fs.appendFileSync('app/src/source.java', 'changed');
`);
    write(root, 'testHelpers/testapp/gradlew', `#!${process.execPath}
const fs = require('fs'), path = require('path');
require('./artifactReceipt').validate(path.resolve('../..'));
fs.appendFileSync(${JSON.stringify(path.join(temporary, 'commands.jsonl'))}, JSON.stringify({ cwd: process.cwd(), args: process.argv.slice(2) }) + '\\n');
if (process.env.CHANGE_ARTIFACT) fs.appendFileSync('../../app/build/outputs/aar/app-debug.aar', 'changed');
if (process.env.FAIL_TEST) process.exit(9);
const mode = process.env.HOST_PROFILE;
if (['mutation', 'exposed', 'upgrade'].includes(mode)) {
    const klass = mode === 'mutation' ? 'BackendMutationCoverageTest' : mode === 'exposed' ? 'ExposedCookieLiveTest' : 'PublishedUpgradeLiveTest';
    const names = mode === 'mutation' ? require('./mutationAcceptance').methods : mode === 'exposed' ?
        ['OKHTTP', 'RETROFIT', 'URLCONNECTION'].map(t => 'exposedCookieLifecycle[' + t + ']') :
        ['LOGIN', 'REFRESH', 'SIGN_OUT', 'PAYLOAD_UPDATE'].map(f => 'publishedSessionContinuesAgainstConfiguredFdiMember[HEADER-' + f + '-/login-2.18]');
    const cases = names.map(name => ({ className: 'com.example.example.' + klass, name, result: 'SUCCESS' }));
    const report = { tests: cases.length, passed: cases.length, failed: 0, skipped: process.env.BAD_REPORT ? 1 : 0, cases };
    fs.mkdirSync(process.env.ST_TEST_REPORT_DIR, { recursive: true });
    fs.writeFileSync(path.join(process.env.ST_TEST_REPORT_DIR, mode === 'upgrade' ? 'upgradeLiveTest.json' : 'testDebugUnitTest.json'), JSON.stringify(report));
}
`);
    for (const file of ['gradlew', 'testHelpers/testapp/gradlew']) fs.chmodSync(path.join(root, file), 0o755);
    const env = { ...process.env };
    const owned = path.join(temporary, 'owned');
    write(owned, 'owner.json', JSON.stringify({ root: owned, runId: 'control' }));
    Object.assign(env, { INSTALL_PATH: owned, ST_TEST_RUN_ID: 'control', NODE_PORT: '18081', ST_TEST_BACKEND_URL: 'http://127.0.0.1:18081' });
    for (const key of ['ST_ARTIFACT_NONCE', 'ST_ARTIFACT_RECEIPT']) delete env[key];
    const execute = extra => spawnSync(process.execPath, [path.join(root, 'testHelpers/testapp/runTests.js'), 'retrofit'],
        { env: { ...env, ...extra }, encoding: 'utf8' });
    let result = execute({});
    assert.strictEqual(result.status, 0, result.stderr);
    assert.match(execute({ ST_TEST_RUN_ID: '' }).stderr, /Run inside runOrdinary/);
    assert.match(execute({ NODE_PORT: '8080', ST_TEST_BACKEND_URL: 'http://127.0.0.1:8080' }).stderr, /Run inside runOrdinary/);
    const calls = fs.readFileSync(path.join(temporary, 'commands.jsonl'), 'utf8').trim().split('\n').map(JSON.parse);
    assert.strictEqual(calls.length, 2);
    assert.strictEqual(calls[0].cwd, root);
    assert.strictEqual(calls[1].cwd, path.join(root, 'testHelpers/testapp'));
    assert(calls[0].args.includes(':app:preparePublishedUpgradeArtifacts'));
    assert(calls[1].args.includes('-PretrofitCompatibility=true'));
    assert(calls.every(call => call.args.includes('--rerun-tasks') && !call.args.includes('-x') && !call.args.includes('--offline')));
    write(owned, 'exposure-config.json', '{}');
    for (const mode of ['ordinary', 'mutation', 'exposed', 'upgrade']) {
        const extra = mode === 'upgrade' ? ['-Pupgrade.loginRoute=/login-2.18'] : [];
        const profileEnv = { ...env, HOST_PROFILE: mode, CORE_PROTOCOL_TESTS: '1', ST_TEST_INSTANCE_ID: 'host-instance',
            ST_UPGRADE_MODE: mode === 'upgrade' ? 'HEADER' : 'EXPOSED_COOKIE', ST_UPGRADE_ROUTE: '/login-2.18' };
        if (mode !== 'upgrade') delete profileEnv.ST_UPGRADE_ROUTE;
        const run = more => spawnSync(process.execPath, [path.join(root, 'testHelpers/testapp/runTests.js'), mode, ...extra],
            { env: { ...profileEnv, ...more }, encoding: 'utf8' });
        fs.writeFileSync(path.join(temporary, 'commands.jsonl'), '');
        result = run({});
        assert.strictEqual(result.status, 0, mode + ': ' + result.stderr);
        const dispatched = fs.readFileSync(path.join(temporary, 'commands.jsonl'), 'utf8').trim().split('\n').map(JSON.parse);
        assert.strictEqual(dispatched.length, 2);
        const args = dispatched[1].args;
        assert(args.includes(mode === 'upgrade' ? ':app:upgradeLiveTest' : ':app:testDebugUnitTest'));
        assert.strictEqual(args.includes('-PstandardIntegrationProfile=true'), mode === 'ordinary');
        if (mode === 'mutation' || mode === 'exposed') assert(args.includes('--tests'));
        if (mode === 'upgrade') assert(args.includes('-Pupgrade.loginRoute=/login-2.18'));
        if (mode === 'upgrade') fs.unlinkSync(path.join(owned, 'upgrade-completed.json'));
        fs.writeFileSync(path.join(temporary, 'commands.jsonl'), '');
        result = run({ FAIL_PREP: '1' });
        assert.strictEqual(result.status, 7, mode + ' preparation failure');
        assert.strictEqual(fs.readFileSync(path.join(temporary, 'commands.jsonl'), 'utf8').trim().split('\n').length, 1);
        result = run({ FAIL_TEST: '1' });
        assert.strictEqual(result.status, 9, mode + ' test failure');
        if (mode !== 'ordinary') {
            result = run({ BAD_REPORT: '1' });
            assert.notStrictEqual(result.status, 0, mode + ' skipped report');
            if (mode === 'upgrade') assert(!fs.existsSync(path.join(owned, 'upgrade-completed.json')));
        }
    }
    for (const fault of ['FAIL_PREP', 'FAIL_TEST', 'CHANGE_SOURCE', 'CHANGE_ARTIFACT']) {
        result = execute({ [fault]: '1' });
        assert.notStrictEqual(result.status, 0, fault);
        assert(!fs.existsSync(path.join(root, '.integration-lock')));
    }
    fs.mkdirSync(path.join(root, '.integration-lock'));
    assert.match(execute({}).stderr, /lane busy/);
    fs.rmSync(path.join(root, '.integration-lock'), { recursive: true });
    assert.throws(() => receipt.validate(root, {}), /Use node/);
    const saved = path.join(temporary, 'receipt.json');
    const nonce = 'host-control';
    write(root, '.integration-lock/nonce', nonce);
    const data = { schema: 1, root, nonce, javaHome: '', sources: receipt.sourceDigest(root), artifacts: receipt.artifactDigest(root) };
    fs.writeFileSync(saved, JSON.stringify(data));
    const validationEnv = { ST_ARTIFACT_RECEIPT: saved, ST_ARTIFACT_NONCE: nonce };
    receipt.validate(root, validationEnv);
    for (const change of [{ nonce: 'old' }, { root: temporary }, { sources: 'old' }, { artifacts: 'old' }, { javaHome: '/wrong/java' }]) {
        fs.writeFileSync(saved, JSON.stringify({ ...data, ...change }));
        assert.throws(() => receipt.validate(root, validationEnv), /stale or mismatched/);
    }
    assert.throws(() => commands('ordinary', ['-x', 'test']), /exclusions/);
    assert.throws(() => commands('upgrade', ['--tests=anything']), /exclusions/);
    assert.throws(() => commands('unknown', []), /Choose/);
    assert(commands('upgrade', ['-Pupgrade.legacy218Evidence=/receipt'])[1].includes('-Pupgrade.legacy218Evidence=/receipt'));
    for (const mode of ['ordinary', 'retrofit']) assert(commands(mode, [])[1].includes('-PstandardIntegrationProfile=true'));
    for (const mode of ['upgrade', 'exposed']) assert(!commands(mode, [])[1].includes('-PstandardIntegrationProfile=true'));
    const previousProtocol = process.env.CORE_PROTOCOL_TESTS;
    const previousRun = process.env.ST_TEST_RUN_ID;
    try {
        process.env.CORE_PROTOCOL_TESTS = '0';
        assert.throws(() => commands('mutation', [], 'http://127.0.0.1:18081'), /explicit/);
        process.env.CORE_PROTOCOL_TESTS = '1';
        process.env.ST_TEST_RUN_ID = 'host-control';
        const mutation = commands('mutation', [], 'http://127.0.0.1:18081')[1];
        assert(mutation.includes('-PretrofitCompatibility=true'));
        assert(mutation.includes('-Pm6BackendUrl=http://127.0.0.1:18081'));
        assert.strictEqual(mutation[mutation.indexOf('--tests') + 1], 'com.example.example.BackendMutationCoverageTest');
    } finally {
        if (previousProtocol === undefined) delete process.env.CORE_PROTOCOL_TESTS;
        else process.env.CORE_PROTOCOL_TESTS = previousProtocol;
        if (previousRun === undefined) delete process.env.ST_TEST_RUN_ID;
        else process.env.ST_TEST_RUN_ID = previousRun;
    }
    const allowed = environment({ CORE_PROTOCOL_TESTS: '1', HTTP_PROXY: 'bad', CORE_PROTOCOL_URL: 'http://foreign',
        ACCOUNT_LINKING_QUALIFICATION_FILE: '/foreign', NODE_OPTIONS: '--require=bad' }, root, 18081, 19001, 'run', 'instance');
    assert.strictEqual(allowed.CORE_PROTOCOL_TESTS, '1');
    assert.strictEqual(allowed.CORE_PROTOCOL_URL, 'http://127.0.0.1:18081');
    assert.strictEqual(allowed.ACCOUNT_LINKING_QUALIFICATION_FILE, path.join(root, 'account-linking-qualification.json'));
    assert(!allowed.NODE_OPTIONS && !allowed.HTTP_PROXY);
    assert.strictEqual(environment({}, root, 18081, 19001, 'run', 'instance').CORE_PROTOCOL_TESTS, '0');
    assert.throws(() => environment({ CORE_PROTOCOL_TESTS: 'true' }, root, 18081, 19001, 'run', 'instance'), /explicitly/);
    const report = { tests: 6, passed: 6, failed: 0, skipped: 0,
        cases: methods.map(name => ({ name, className: 'com.example.example.BackendMutationCoverageTest', result: 'SUCCESS' })) };
    accept(report);
    for (const change of [{ tests: 0 }, { skipped: 1 }, { failed: 1 }, { passed: 5 }, { tests: 127 },
        { cases: report.cases.slice(1) }, { cases: report.cases.map(test => ({ ...test, name: methods[0] })) }]) {
        assert.throws(() => accept({ ...report, ...change }), /exact six/);
    }
    console.log('PASS: two-process handoff, failure propagation, lock, stale source/artifact/version/nonce rejection, command restrictions, opt-in allowlist, exact-six acceptance (host stubs; no Gradle/Core)');
} finally { fs.rmSync(temporary, { recursive: true, force: true }); }
