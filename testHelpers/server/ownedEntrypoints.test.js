const fs = require('fs');
const path = require('path');
const os = require('os');
const assert = require('assert');
const { spawnSync } = require('child_process');
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'owned-entrypoint-controls-'));
function write(file, text) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, text);
}
try {
    const root = path.join(temporary, 'repo');
    const owned = path.join(temporary, 'owned');
    const backend = path.join(owned, 'backend');
    const log = path.join(temporary, 'calls.jsonl');
    const record = `require('fs').appendFileSync(${JSON.stringify(log)}, JSON.stringify({ name: require('path').basename(process.argv[1]), args: process.argv.slice(2), cwd: process.cwd(), core: process.env.CORE_CONNECTION_URI, url: process.env.CORE_PROTOCOL_URL, agent: process.env.ST_DIAGNOSTIC_AGENT, output: process.env.ST_DIAGNOSTIC_OUTPUT }) + '\\n');`;
    for (const name of ['accountLinking.live.test.js', 'accountLinking.gate.js']) write(path.join(backend, name), record);
    write(path.join(backend, 'coreRuntimeIdentity.js'), 'exports.serverIdentity = () => true;');
    write(path.join(backend, 'accountLinkingQualification.js'), 'exports.identity = () => ({ pair: "actual-stub-instance" });');
    write(path.join(backend, 'node_modules/supertokens-node/lib/build/version.js'),
        'exports.version = process.env.NODE_SDK_VERSION === "<missing>" ? undefined : process.env.NODE_SDK_VERSION === "<number>" ? 14 : (process.env.NODE_SDK_VERSION ?? "99.1.0");');
    write(path.join(backend, 'node_modules/node-fetch/index.js'), `let jwt = false;
    module.exports = async (url, options) => {
        require('assert').strictEqual(new URL(url).origin, 'http://127.0.0.1:18081');
        require('assert').strictEqual(options.headers['x-st-test-run-id'], 'owned-control');
        require('fs').appendFileSync(${JSON.stringify(log)}, JSON.stringify({ route: new URL(url).pathname }) + '\\n');
        if (new URL(url).pathname === '/startst') jwt = JSON.parse(options.body).enableJWT === true;
        return { ok: true, json: async () => ({ enabled: true, compatible: process.env.INCOMPATIBLE !== '1', qualificationProxy: process.env.PROXY === '1',
            ownedExposedCleanup: process.env.CLEANUP_CAPABILITY === 'missing' ? undefined : process.env.CLEANUP_CAPABILITY !== 'false',
            nodeVersion: process.env.OBSERVED_NODE_VERSION === '<missing>' ? undefined : (process.env.OBSERVED_NODE_VERSION ?? process.env.NODE_SDK_VERSION ?? '99.1.0'), coreReachable: true, sessionJwt: jwt,
            coreCdiVersions: process.env.NO_CDI === '1' ? ['5.0'] : ['2.18'],
            mappedGoogleSession: process.env.NO_MAPPED !== '1', nonPublicTenantSession: process.env.NO_TENANT !== '1' }) };
    };`);
    for (const file of [path.join(temporary, 'bin/npm'), path.join(root, 'gradlew')]) {
        write(file, `#!${process.execPath}\n${record}`);
        fs.chmodSync(file, 0o755);
    }
    fs.appendFileSync(path.join(root, 'gradlew'), `
if (process.env.FAIL_ROOT_BUILD && process.argv.includes('build')) process.exit(7);
if (process.env.ST_MATRIX_REPORTS) {
    const directory = require('path').join(process.env.ST_MATRIX_REPORTS, 'testDebugUnitTest');
    require('fs').mkdirSync(directory, { recursive: true });
    const exact = require(${JSON.stringify(path.resolve(__dirname, '../../.circleci/integrationEvidence'))}).exactCases;
    const suite = process.argv.includes('com.supertokens.session.CoreProxyProtocolTest') ? 'proxy-qualification' :
        process.argv.includes('com.supertokens.session.CoreSdkCompatibilityTest') ? 'core-baseline' : 'core-classes';
    const cases = exact[suite].map(full => ({ className: full.slice(0, full.lastIndexOf('.')),
        name: full.slice(full.lastIndexOf('.') + 1), result: 'SUCCESS' }));
    require('fs').writeFileSync(require('path').join(directory, 'result.json'), JSON.stringify({ tests: cases.length,
        passed: cases.length, failed: 0, skipped: 0, cases }));
}
`);
    write(path.join(root, 'testHelpers/testapp/runTests.js'), record + '\nif (process.env.FAIL_PROFILE === process.argv[2]) process.exit(9);');
    write(path.join(root, 'testHelpers/server/matrixReports.contract.test.js'), record + '\nif (process.env.FAIL_REPORT_CONTRACT) process.exit(9);');
    function run(mode, extra = {}, options = []) {
        fs.writeFileSync(log, '');
        fs.rmSync(path.join(owned, 'matrix'), { recursive: true, force: true });
        const proxyModule = path.join(__dirname, 'ownedProxyQualification.js');
        const harness = `require.cache[${JSON.stringify(proxyModule)}] = { exports: {
            qualify: async ({ runTests }) => runTests('http://127.0.0.1:18082')
        } }; process.argv = [process.execPath, ${JSON.stringify(path.join(__dirname, 'runOwnedTests.js'))}, ${JSON.stringify(mode)}, ${JSON.stringify(root)}, ...${JSON.stringify(options)}];
        require(${JSON.stringify(path.join(__dirname, 'runOwnedTests.js'))}).main()
            .catch(error => { console.error(error); process.exitCode = 1; });`;
        return spawnSync(process.execPath, ['-e', harness], {
            encoding: 'utf8', env: { ...process.env, PATH: path.join(temporary, 'bin') + path.delimiter + process.env.PATH,
                INSTALL_PATH: owned, ST_TEST_RUN_ID: 'owned-control', CORE_PROTOCOL_TESTS: '1',
                ST_TEST_BACKEND_URL: 'http://127.0.0.1:18081', CORE_PROTOCOL_URL: 'http://127.0.0.1:18081',
                CORE_CONNECTION_URI: 'http://127.0.0.1:19001', ...extra }
        });
    }
    const calls = () => fs.readFileSync(log, 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
    let result = run('matrix', { ST_DIAGNOSTIC_AGENT: 'sentinel', ST_DIAGNOSTIC_OUTPUT: 'sentinel' });
    assert.strictEqual(result.status, 0, result.stderr);
    let events = calls();
    assert.deepStrictEqual(events.slice(0, 4).map(event => event.route || event.name),
        ['/beforeeach', '/startst', 'accountLinking.live.test.js', 'accountLinking.gate.js']);
    assert(events.filter(event => event.name).every(event => !event.agent && !event.output));
    assert(events.filter(event => event.name).every(event => event.core === 'http://127.0.0.1:19001' && event.url === 'http://127.0.0.1:18081'));
    assert.deepStrictEqual(events.filter(event => event.name === 'runTests.js').map(event => event.args), [['mutation'], ['ordinary'], ['retrofit']]);
    assert(events.some(event => event.name === 'gradlew' && event.args.includes('com.supertokens.session.CoreProxyProtocolTest')));
    assert(events.some(event => event.name === 'npm' && event.args.join(' ') === 'run test:core'));
    const rootBuildIndex = events.findIndex(event => event.name === 'gradlew' && event.args.includes('build'));
    const contractIndex = events.findIndex(event => event.name === 'matrixReports.contract.test.js');
    const firstProfileIndex = events.findIndex(event => event.name === 'runTests.js');
    assert(rootBuildIndex >= 0 && contractIndex > rootBuildIndex && contractIndex < firstProfileIndex);
    const contractSteps = JSON.parse(fs.readFileSync(path.join(owned, 'matrix/steps.json'))).filter(step => step.name === 'reporting-contract');
    assert.deepStrictEqual(contractSteps.map(step => step.status), ['running', 'passed']);
    for (const mode of ['diagnostic-ordinary', 'diagnostic-retrofit']) {
        for (const selection of ['focused', 'full']) {
            assert.strictEqual(run(mode, { ST_DIAGNOSTIC_AGENT: 'sentinel', ST_DIAGNOSTIC_OUTPUT: 'sentinel' }, [selection]).status, 0);
            assert(calls().filter(event => event.name).every(event => !event.agent && !event.output));
            assert.deepStrictEqual(calls().filter(event => event.name === 'runTests.js').map(event => event.args), [[mode, selection]]);
        }
        assert.notStrictEqual(run(mode, {}, ['--tests=anything']).status, 0);
        assert(!calls().some(event => event.name === 'runTests.js'));
    }
    assert.strictEqual(run('ordinary', { ST_DIAGNOSTIC_AGENT: 'sentinel', ST_DIAGNOSTIC_OUTPUT: 'sentinel' }).status, 0);
    assert(calls().filter(event => event.name).every(event => !event.agent && !event.output));
    const coreTests = events.filter(event => event.name === 'gradlew' && event.args.includes('-PcoreProtocolUrl=http://127.0.0.1:18081'));
    assert.strictEqual(coreTests.length, 2);
    assert(coreTests[0].args.includes('com.supertokens.session.CoreSdkCompatibilityTest'));
    assert.deepStrictEqual(coreTests.map(event => event.args.filter(arg => arg === '--tests').length), [1, 2]);
    assert.notStrictEqual(run('matrix', { FAIL_ROOT_BUILD: '1' }).status, 0);
    assert(!calls().some(event => event.name === 'matrixReports.contract.test.js'));
    assert.notStrictEqual(run('matrix', { FAIL_REPORT_CONTRACT: '1' }).status, 0);
    assert(!calls().some(event => event.name === 'runTests.js'));
    const failedContractSteps = JSON.parse(fs.readFileSync(path.join(owned, 'matrix/steps.json'))).filter(step => step.name === 'reporting-contract');
    assert.deepStrictEqual(failedContractSteps.map(step => step.status), ['running', 'failed']);
    for (const [failed, expected] of [
        ['mutation', ['mutation']],
        ['ordinary', ['mutation', 'ordinary']],
        ['retrofit', ['mutation', 'ordinary', 'retrofit']],
    ]) {
        assert.notStrictEqual(run('matrix', { FAIL_PROFILE: failed }).status, 0, failed);
        assert.deepStrictEqual(calls().filter(event => event.name === 'runTests.js').map(event => event.args[0]), expected);
    }
    result = run('mutation');
    assert.strictEqual(result.status, 0, result.stderr);
    assert.deepStrictEqual(calls().filter(event => event.name === 'runTests.js').map(event => event.args), [['mutation']]);
    for (const fault of [{ NO_TENANT: '1' }, { PROXY: '1' }, { CORE_PROTOCOL_TESTS: '0' }]) {
        assert.notStrictEqual(run('mutation', fault).status, 0);
        assert(!calls().some(event => event.name === 'runTests.js'));
    }
    const historical = ['HEADER', '4.1', '2', JSON.stringify({ LOGIN: '2', REFRESH: '5', SIGN_OUT: 'ABSENT', PAYLOAD_UPDATE: '5' }), '5', '/login-2.18'];
    result = run('upgrade', {}, historical);
    assert.strictEqual(result.status, 0, result.stderr);
    const dispatch = calls().find(event => event.name === 'runTests.js');
    assert(dispatch.args.includes('-Pupgrade.loginRoute=/login-2.18'));
    const config = JSON.parse(fs.readFileSync(path.join(owned, 'exposure-config.json')));
    assert.strictEqual(config.selected.route, '/login-2.18');
    assert.strictEqual(config.flags.sessionJwt, false);
    for (const fault of [{ NO_CDI: '1' }, { PROXY: '1' }, { CORE_PROTOCOL_TESTS: '0' }]) {
        assert.notStrictEqual(run('upgrade', fault, historical).status, 0);
        assert(!calls().some(event => event.name === 'runTests.js'));
    }
    // This capability is synthetic; the real backend does not yet implement the lease contract.
    for (const capability of ['missing', 'false']) {
        for (const [mode, options] of [['exposed', []], ['upgrade', ['EXPOSED_COOKIE', '4.1', '5', '5', '5']]]) {
            result = run(mode, { CLEANUP_CAPABILITY: capability }, options);
            assert.notStrictEqual(result.status, 0);
            assert.match(result.stderr, /cleanup\/fixture lease contract not implemented/);
            assert.deepStrictEqual(calls().map(event => event.route), ['/test/capabilities']);
        }
    }
    result = run('exposed', { NO_MAPPED: '1', NO_TENANT: '1' });
    assert.strictEqual(result.status, 0, result.stderr);
    assert.deepStrictEqual(calls().filter(event => event.name === 'runTests.js').map(event => event.args), [['exposed']]);
    assert.strictEqual(JSON.parse(fs.readFileSync(path.join(owned, 'exposure-config.json'))).capabilities.mappedGoogleSession, false);
    for (const fault of [{ PROXY: '1' }, { CORE_PROTOCOL_TESTS: '0' }, { INCOMPATIBLE: '1' }]) {
        assert.notStrictEqual(run('exposed', { NO_MAPPED: '1', ...fault }).status, 0);
        assert(!calls().some(event => event.name === 'runTests.js'));
    }
    for (const version of ['13.99.99', '0.0.0', '<missing>', '<number>', '', 'not-a-version', '14', '14.0', '14.x.0',
        '14.0.0junk', ' 14.0.0', '014.0.0', '14.0.0-01', '14.0.0+', '999999999999999999999.0.0']) {
        for (const [mode, options] of [['exposed', []], ['upgrade', ['EXPOSED_COOKIE', '4.1', '5', '5', '5']]]) {
            result = run(mode, { NODE_SDK_VERSION: version }, options);
            assert.notStrictEqual(result.status, 0, mode + ': ' + version);
            assert.match(result.stderr, /valid Node SDK version/);
            assert.deepStrictEqual(calls(), [], 'Invalid version must fail before fixture requests or test dispatch');
        }
    }
    for (const version of ['14.0.0', '14.0.1', '15.0.0', '99.1.0', '14.0.0-rc.1', '15.1.0-beta.2+build.01']) {
        result = run('exposed', { NODE_SDK_VERSION: version });
        assert.strictEqual(result.status, 0, result.stderr);
        assert.deepStrictEqual(calls().filter(event => event.name === 'runTests.js').map(event => event.args), [['exposed']]);
    }
    result = run('upgrade', { NODE_SDK_VERSION: '14.0.0' }, ['EXPOSED_COOKIE', '4.1', '5', '5', '5']);
    assert.strictEqual(result.status, 0, result.stderr);
    assert.strictEqual(calls().filter(event => event.name === 'runTests.js')[0].args[0], 'upgrade');
    for (const observed of ['15.0.0', 'not-a-version', '<missing>', '']) {
        result = run('exposed', { NODE_SDK_VERSION: '14.0.0', OBSERVED_NODE_VERSION: observed });
        assert.notStrictEqual(result.status, 0);
        assert.deepStrictEqual(calls().map(event => event.route), ['/test/capabilities']);
    }
    assert.notStrictEqual(run('upgrade', { NO_MAPPED: '1' }, historical).status, 0);
    assert(!calls().some(event => event.name === 'runTests.js'));
    for (const handoff of ['5', '{"LOGIN":"2"}', '{"LOGIN":"2","REFRESH":"ABSENT","SIGN_OUT":"5","PAYLOAD_UPDATE":"5"}']) {
        const invalid = [...historical]; invalid[3] = handoff;
        assert.notStrictEqual(run('upgrade', {}, invalid).status, 0);
        assert.strictEqual(calls().length, 0, 'Invalid plan must fail before backend setup or test dispatch');
    }
    const repository = path.resolve(__dirname, '../..');
    const ci = fs.readFileSync(path.join(repository, '.circleci/setupAndTestWithFreeCore.sh'), 'utf8');
    const ciCaller = fs.readFileSync(path.join(repository, '.circleci/doTests.sh'), 'utf8');
    assert(ciCaller.includes('driverSha=$(git rev-parse --verify HEAD^{commit})'));
    assert(ciCaller.includes('refs/tags/$driverTag^{commit}') && ciCaller.includes('"$driverTag" "$driverSha"'));
    assert(ci.includes('supertokens-node.git#$3') && ci.includes('git checkout $coreTag'));
    assert(ci.includes('"$core_source" "$PWD/package-lock.json" "$5" "$6" "$2" "$4" "$3"'));
    assert(ci.includes('runOrdinary.js') && ci.includes('runOwnedTests.js" matrix'));
    assert(!/node \. &|8080|9000|ciCleanup.sh|kill |gradlew --stop/.test(ci));
    const runner = fs.readFileSync(path.join(__dirname, 'runOrdinary.js'), 'utf8');
    assert(runner.indexOf('await test.drain()') < runner.indexOf('CORE_CLEANUP_ACKNOWLEDGED'));
    assert(runner.indexOf('CORE_CLEANUP_ACKNOWLEDGED') < runner.indexOf('await backend.stop(runId)'));
    const gradle = fs.readFileSync(path.join(repository, 'testHelpers/testapp/app/build.gradle'), 'utf8');
    assert(!gradle.includes('type: GradleBuild'));
    assert(gradle.includes('validatePreparedArtifacts()') && gradle.includes('result.testCount != 6'));
    assert(gradle.includes("'expectedPublishedHandoffVersions', 'loginRoute'"));
    console.log('PASS: owned matrix order/URLs/pair gate, nonproxy mutation preflight, CI caller wiring and unchanged drain-before-cleanup path (host stubs; no listener/Core/Gradle)');
} finally { fs.rmSync(temporary, { recursive: true, force: true }); }
