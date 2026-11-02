const assert = require('assert');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawnSync } = require('child_process');
const { commands, diagnosticPaths } = require('./runTests');
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
    write(root, 'testHelpers/server/matrixReports.gradle', fs.readFileSync(path.join(__dirname, '../server/matrixReports.gradle')));
    for (const file of ['app/build.gradle', 'build.gradle', 'gradle/wrapper/gradle-wrapper.properties',
        'testHelpers/testapp/build.gradle', 'testHelpers/testapp/gradle/wrapper/gradle-wrapper.properties']) write(root, file, 'version=control');
    write(root, 'app/src/source.java', 'source');
    write(root, 'gradlew', `#!${process.execPath}
const fs = require('fs'), path = require('path');
fs.appendFileSync(${JSON.stringify(path.join(temporary, 'commands.jsonl'))}, JSON.stringify({ cwd: process.cwd(), args: process.argv.slice(2), agent: process.env.ST_DIAGNOSTIC_AGENT, output: process.env.ST_DIAGNOSTIC_OUTPUT, matrix: process.env.ST_MATRIX_REPORTS }) + '\\n');
for (const file of ['app/build/outputs/aar/app-debug.aar','app/build/upgradeHarness/upgrade-harness.jar','app/build/legacy054/classes.jar','app/build/legacy054/driver/Driver.class']) {
    fs.mkdirSync(path.dirname(file), { recursive:true }); fs.writeFileSync(file, 'fresh');
}
if (process.env.CHANGE_PROBE_PREP_FAIL) fs.appendFileSync('../probe/worker-init.gradle', 'changed');
if (process.env.FAIL_PREP) process.exit(7);
if (process.env.CHANGE_SOURCE) fs.appendFileSync('app/src/source.java', 'changed');
if (process.env.CHANGE_REPORT_SCRIPT) fs.appendFileSync('testHelpers/server/matrixReports.gradle', 'changed');
if (process.env.CHANGE_PROBE) fs.appendFileSync('../probe/worker-init.gradle', 'changed');
`);
    write(root, 'testHelpers/testapp/gradlew', `#!${process.execPath}
const fs = require('fs'), path = require('path');
require('./artifactReceipt').validate(path.resolve('../..'));
const invocation = JSON.parse(fs.readFileSync(path.join(process.env.ST_TEST_REPORT_DIR, '..', 'invocation.json')));
const script = process.argv[process.argv.indexOf('-I') + 1];
const scriptHash = require('crypto').createHash('sha256').update(fs.readFileSync(script)).digest('hex');
if (invocation.nonce !== process.env.ST_ARTIFACT_NONCE || (process.env.HOST_PROFILE && invocation.mode !== process.env.HOST_PROFILE) ||
    invocation.sources !== require('./artifactReceipt').sourceDigest(path.resolve('../..')) || invocation.progressScriptSha256 !== scriptHash) process.exit(11);
fs.appendFileSync(${JSON.stringify(path.join(temporary, 'commands.jsonl'))}, JSON.stringify({ cwd: process.cwd(), args: process.argv.slice(2), agent: process.env.ST_DIAGNOSTIC_AGENT, output: process.env.ST_DIAGNOSTIC_OUTPUT, matrix: process.env.ST_MATRIX_REPORTS }) + '\\n');
if (process.env.CHANGE_ARTIFACT) fs.appendFileSync('../../app/build/outputs/aar/app-debug.aar', 'changed');
if (process.env.CHANGE_PROBE_TEST) fs.appendFileSync('../../../probe/WorkerWatchdog.java', 'changed');
if (process.env.CHANGE_REPORT_TEST) fs.appendFileSync('../server/matrixReports.gradle', 'changed');
if (process.env.CHANGE_SOURCE_TEST) fs.appendFileSync('../../app/src/source.java', 'changed');
if (process.env.CHANGE_MANIFEST_TEST) fs.appendFileSync('../../../probe/manifest.json', ' ');
if (process.env.FAIL_TEST) process.exit(9);
const mode = process.env.HOST_PROFILE;
if (mode && mode.startsWith('diagnostic-')) {
    const target = mode === 'diagnostic-retrofit' ? 'okHttp_testThatMultipleAPICallsInParallelAndOnly1RefreshShouldBeCalled' : 'retrofit_testThatThingsShouldWorkIfAntiCsrfIsDisabled';
    fs.mkdirSync(process.env.ST_TEST_REPORT_DIR, { recursive: true });
    const cases = process.env.ZERO_TARGET ? [] : [{ className: process.env.WRONG_CLASS ? 'com.example.SuperTokensOkHttpTest' : 'com.example.SuperTokensRetrofitTest', name: target, result: process.env.SKIPPED_TARGET ? 'SKIPPED' : process.env.FAILED_TARGET ? 'FAILURE' : 'SUCCESS' }];
    if (process.env.DUPLICATE_TARGET) cases.push(cases[0]);
    fs.writeFileSync(path.join(process.env.ST_TEST_REPORT_DIR, 'testDebugUnitTest.json'), JSON.stringify({ tests: cases.length, skipped: process.env.SKIPPED_TARGET ? 1 : 0, failed: process.env.FAILED_TARGET ? 1 : 0, cases }));
}
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
    fs.chmodSync(owned, 0o700);
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
    assert(!calls[0].args.includes('-I'));
    assert.strictEqual(calls[1].args[calls[1].args.indexOf('-I') + 1],
        fs.realpathSync(path.join(root, 'testHelpers/server/matrixReports.gradle')));
    assert(calls.every(call => call.args.includes('--rerun-tasks') && !call.args.includes('-x') && !call.args.includes('--offline')));
    write(root, 'testHelpers/server/runOwnedTests.js', `require('fs').appendFileSync(${JSON.stringify(path.join(temporary, 'commands.jsonl'))}, JSON.stringify({ exposedAgent: process.env.ST_DIAGNOSTIC_AGENT || null, exposedOutput: process.env.ST_DIAGNOSTIC_OUTPUT || null, args: process.argv.slice(2) }) + '\\n');`);
    fs.writeFileSync(path.join(temporary, 'commands.jsonl'), '');
    result = execute({ CORE_PROTOCOL_TESTS: '1', ST_DIAGNOSTIC_AGENT: 'sentinel', ST_DIAGNOSTIC_OUTPUT: 'sentinel' });
    assert.strictEqual(result.status, 0, result.stderr);
    const normalSeen = fs.readFileSync(path.join(temporary, 'commands.jsonl'), 'utf8').trim().split('\n').map(JSON.parse);
    assert(normalSeen.every(call => !call.agent && !call.output && !call.exposedAgent && !call.exposedOutput));
    assert(normalSeen.some(call => call.args[0] === 'exposed'));
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
        assert.strictEqual(args.filter(arg => arg === '-I').length, 1);
        assert.strictEqual(args[args.indexOf('-I') + 1], fs.realpathSync(path.join(root, 'testHelpers/server/matrixReports.gradle')));
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
    result = execute({ CHANGE_REPORT_SCRIPT: '1' });
    assert.notStrictEqual(result.status, 0);
    assert.match(result.stderr, /Progress report script changed during preparation/);
    write(root, 'testHelpers/server/matrixReports.gradle', fs.readFileSync(path.join(__dirname, '../server/matrixReports.gradle')));
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
    fs.rmSync(path.join(root, '.integration-lock'), { recursive: true });
    diagnosticPaths('/private/space name/agent.jar', '/private/space name/output');
    for (const [agent, output] of [['/a=b/agent.jar', '/out'], ['/a\n/agent.jar', '/out'], ['/agent.jar', '/out\u007f']]) {
        assert.throws(() => diagnosticPaths(agent, output), /Ambiguous/);
    }
    const diag = commands('diagnostic-retrofit', ['focused']);
    assert(diag[1].includes('com.example.SuperTokensRetrofitTest.okHttp_testThatMultipleAPICallsInParallelAndOnly1RefreshShouldBeCalled'));
    assert(commands('diagnostic-ordinary', ['focused'])[1].includes('com.example.SuperTokensRetrofitTest.retrofit_testThatThingsShouldWorkIfAntiCsrfIsDisabled'));
    assert(!commands('diagnostic-ordinary', ['full'])[1].includes('--tests'));
    assert.throws(() => commands('diagnostic-ordinary', ['--tests=anything']), /exclusions/);
    const probeRoot = path.join(temporary, 'probe');
    fs.mkdirSync(probeRoot, { mode: 0o700 });
    const hashes = {};
    for (const name of ['WorkerWatchdog.java', 'worker-init.gradle', 'worker-agent.jar']) {
        const file = path.join(probeRoot, name);
        fs.writeFileSync(file, name, { mode: 0o600 });
        hashes[name] = require('crypto').createHash('sha256').update(name).digest('hex');
    }
    fs.writeFileSync(path.join(probeRoot, 'manifest.json'), JSON.stringify(hashes), { mode: 0o600 });
    const diagnosticRun = (mode, selection, more = {}) => {
        fs.rmSync(path.join(owned, 'diagnostic-worker'), { recursive: true, force: true });
        fs.writeFileSync(path.join(temporary, 'commands.jsonl'), '');
        return spawnSync(process.execPath, [path.join(root, 'testHelpers/testapp/runTests.js'), mode, selection],
            { env: { ...env, HOST_PROFILE: mode, ST_DIAGNOSTIC_AGENT: 'sentinel', ST_DIAGNOSTIC_OUTPUT: 'sentinel', ST_MATRIX_REPORTS: path.join(temporary, 'hostile'), ...more }, encoding: 'utf8' });
    };
    for (const mode of ['diagnostic-retrofit', 'diagnostic-ordinary']) {
        for (const selection of ['focused', 'full']) {
            result = diagnosticRun(mode, selection);
            assert.strictEqual(result.status, 0, result.stderr);
            const seen = fs.readFileSync(path.join(temporary, 'commands.jsonl'), 'utf8').trim().split('\n').map(JSON.parse);
            assert.strictEqual(seen.length, 2);
            assert(!seen[0].agent && !seen[0].output && !seen[0].matrix);
            assert.strictEqual(seen[1].agent, path.join(probeRoot, 'worker-agent.jar'));
            assert(seen[1].output.startsWith(path.join(probeRoot, 'captures') + path.sep));
            assert(!seen[1].matrix && !fs.existsSync(path.join(temporary, 'hostile')));
        }
    }
    const diagnosticRoot = path.join(root, 'testHelpers/testapp/app/build/diagnostic-receipts');
    const diagnosticRuns = fs.readdirSync(diagnosticRoot);
    assert.strictEqual(diagnosticRuns.length, 4);
    for (const item of diagnosticRuns) {
        const directory = path.join(diagnosticRoot, item);
        assert(!fs.existsSync(path.join(directory, 'completed.json')));
        assert.strictEqual(JSON.parse(fs.readFileSync(path.join(directory, 'diagnostic-finished.json'))).diagnosticOnly, true);
        assert.strictEqual(JSON.parse(fs.readFileSync(path.join(directory, 'invocation.json'))).diagnosticOnly, true);
    }
    for (const mode of ['diagnostic-ordinary', 'diagnostic-retrofit']) for (const selection of ['focused', 'full']) {
        const before = new Set(fs.readdirSync(diagnosticRoot));
        result = diagnosticRun(mode, selection, { FAIL_TEST: '1' });
        assert.strictEqual(result.status, 9);
        const fresh = fs.readdirSync(diagnosticRoot).filter(name => !before.has(name));
        assert.strictEqual(fresh.length, 1);
        assert(!fs.existsSync(path.join(diagnosticRoot, fresh[0], 'diagnostic-finished.json')));
    }
    const mutable = [
        ['CHANGE_PROBE_TEST', path.join(probeRoot, 'WorkerWatchdog.java'), 'probe'],
        ['CHANGE_MANIFEST_TEST', path.join(probeRoot, 'manifest.json'), 'probe'],
        ['CHANGE_REPORT_TEST', path.join(root, 'testHelpers/server/matrixReports.gradle'), 'report-script'],
        ['CHANGE_SOURCE_TEST', path.join(root, 'app/src/source.java'), 'sources'],
        ['CHANGE_ARTIFACT', null, 'artifacts'],
    ];
    {
        const before = new Set(fs.readdirSync(diagnosticRoot));
        result = diagnosticRun('diagnostic-ordinary', 'focused', { FAIL_PREP: '1', CHANGE_PROBE_PREP_FAIL: '1' });
        assert.strictEqual(result.status, 7);
        const fresh = fs.readdirSync(diagnosticRoot).find(name => !before.has(name));
        assert(JSON.parse(fs.readFileSync(path.join(diagnosticRoot, fresh, 'integrity-prepare.json'))).mismatches.includes('probe'));
        assert(!fs.existsSync(path.join(diagnosticRoot, fresh, 'diagnostic-finished.json')));
        fs.writeFileSync(path.join(probeRoot, 'worker-init.gradle'), 'worker-init.gradle');
    }
    for (const [fault, file, mismatch] of mutable) for (const failure of [true, false]) {
        const savedBytes = file && fs.readFileSync(file);
        const before = new Set(fs.readdirSync(diagnosticRoot));
        result = diagnosticRun('diagnostic-ordinary', 'focused', { [fault]: '1', ...(failure ? { FAIL_TEST: '1' } : {}) });
        if (failure) assert.strictEqual(result.status, 9, fault + ': ' + result.stderr);
        else assert.match(result.stderr, /Integrity mismatch after test/, fault);
        const fresh = fs.readdirSync(diagnosticRoot).find(name => !before.has(name));
        const directory = path.join(diagnosticRoot, fresh);
        assert(JSON.parse(fs.readFileSync(path.join(directory, 'integrity-test.json'))).mismatches.includes(mismatch));
        assert(!fs.existsSync(path.join(directory, 'diagnostic-finished.json')));
        if (file) fs.writeFileSync(file, savedBytes);
    }
    const fakeJavaHome = path.join(temporary, 'java-home');
    write(fakeJavaHome, 'bin/java', `#!${process.execPath}\nrequire('fs').writeFileSync(${JSON.stringify(path.join(temporary, 'java-env.json'))}, JSON.stringify({ agent: process.env.ST_DIAGNOSTIC_AGENT, output: process.env.ST_DIAGNOSTIC_OUTPUT, matrix: process.env.ST_MATRIX_REPORTS }));`);
    fs.chmodSync(path.join(fakeJavaHome, 'bin/java'), 0o755);
    result = diagnosticRun('diagnostic-ordinary', 'focused', { JAVA_HOME: fakeJavaHome });
    assert.strictEqual(result.status, 0, result.stderr);
    assert.deepStrictEqual(JSON.parse(fs.readFileSync(path.join(temporary, 'java-env.json'))), {});
    result = diagnosticRun('diagnostic-ordinary', 'focused', { CHANGE_PROBE: '1' });
    assert.notStrictEqual(result.status, 0);
    assert.match(result.stderr, /Integrity mismatch after prepare: probe/);
    assert(!fs.existsSync(path.join(root, '.integration-lock')));
    fs.writeFileSync(path.join(probeRoot, 'worker-init.gradle'), 'worker-init.gradle');
    result = diagnosticRun('diagnostic-ordinary', 'focused', { CHANGE_PROBE_TEST: '1' });
    assert.match(result.stderr, /Integrity mismatch after test: probe/);
    assert(!fs.existsSync(path.join(root, '.integration-lock')));
    fs.writeFileSync(path.join(probeRoot, 'WorkerWatchdog.java'), 'WorkerWatchdog.java');
    for (const fault of ['ZERO_TARGET', 'WRONG_CLASS', 'DUPLICATE_TARGET', 'SKIPPED_TARGET', 'FAILED_TARGET']) {
        result = diagnosticRun('diagnostic-ordinary', 'focused', { [fault]: '1' });
        assert.match(result.stderr, /Diagnostic focused result inconclusive/, fault);
    }
    fs.chmodSync(path.join(probeRoot, 'worker-init.gradle'), 0o644);
    assert.match(diagnosticRun('diagnostic-ordinary', 'focused').stderr, /Unsafe diagnostic path/);
    fs.chmodSync(path.join(probeRoot, 'worker-init.gradle'), 0o600);
    fs.writeFileSync(path.join(probeRoot, 'worker-agent.jar'), 'changed');
    assert.match(diagnosticRun('diagnostic-ordinary', 'focused').stderr, /Diagnostic probe digest mismatch/);
    fs.writeFileSync(path.join(probeRoot, 'worker-agent.jar'), 'worker-agent.jar');
    fs.unlinkSync(path.join(probeRoot, 'worker-agent.jar'));
    fs.symlinkSync(path.join(probeRoot, 'worker-init.gradle'), path.join(probeRoot, 'worker-agent.jar'));
    assert.match(diagnosticRun('diagnostic-ordinary', 'focused').stderr, /Unsafe diagnostic path/);
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
