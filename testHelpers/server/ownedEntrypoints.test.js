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
    const record = `require('fs').appendFileSync(${JSON.stringify(log)}, JSON.stringify({ name: require('path').basename(process.argv[1]), args: process.argv.slice(2), cwd: process.cwd(), core: process.env.CORE_CONNECTION_URI, url: process.env.CORE_PROTOCOL_URL }) + '\\n');`;
    for (const name of ['accountLinking.live.test.js', 'accountLinking.gate.js']) write(path.join(backend, name), record);
    write(path.join(backend, 'coreRuntimeIdentity.js'), 'exports.serverIdentity = () => true;');
    write(path.join(backend, 'accountLinkingQualification.js'), 'exports.identity = () => ({ pair: "actual-stub-instance" });');
    write(path.join(backend, 'node_modules/node-fetch/index.js'), `module.exports = async (url, options) => {
        require('assert').strictEqual(new URL(url).origin, 'http://127.0.0.1:18081');
        require('assert').strictEqual(options.headers['x-st-test-run-id'], 'owned-control');
        require('fs').appendFileSync(${JSON.stringify(log)}, JSON.stringify({ route: new URL(url).pathname }) + '\\n');
        return { ok: true, json: async () => ({ enabled: true, compatible: true, qualificationProxy: process.env.PROXY === '1',
            nodeVersion: '99.1.0', coreReachable: true, sessionJwt: false,
            coreCdiVersions: process.env.NO_CDI === '1' ? ['5.0'] : ['2.18'],
            mappedGoogleSession: true, nonPublicTenantSession: process.env.NO_TENANT !== '1' }) };
    };`);
    for (const file of [path.join(temporary, 'bin/npm'), path.join(root, 'gradlew')]) {
        write(file, `#!${process.execPath}\n${record}`);
        fs.chmodSync(file, 0o755);
    }
    fs.appendFileSync(path.join(root, 'gradlew'), `
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
    write(path.join(root, 'testHelpers/testapp/runTests.js'), record);
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
    let result = run('matrix');
    assert.strictEqual(result.status, 0, result.stderr);
    let events = calls();
    assert.deepStrictEqual(events.slice(0, 4).map(event => event.route || event.name),
        ['/beforeeach', '/startst', 'accountLinking.live.test.js', 'accountLinking.gate.js']);
    assert(events.filter(event => event.name).every(event => event.core === 'http://127.0.0.1:19001' && event.url === 'http://127.0.0.1:18081'));
    assert.deepStrictEqual(events.filter(event => event.name === 'runTests.js').map(event => event.args), [['mutation'], ['ordinary'], ['retrofit']]);
    assert(events.some(event => event.name === 'gradlew' && event.args.includes('com.supertokens.session.CoreProxyProtocolTest')));
    assert(events.some(event => event.name === 'npm' && event.args.join(' ') === 'run test:core'));
    const coreTests = events.filter(event => event.name === 'gradlew' && event.args.includes('-PcoreProtocolUrl=http://127.0.0.1:18081'));
    assert.strictEqual(coreTests.length, 2);
    assert(coreTests[0].args.includes('com.supertokens.session.CoreSdkCompatibilityTest'));
    assert.deepStrictEqual(coreTests.map(event => event.args.filter(arg => arg === '--tests').length), [1, 2]);
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
    for (const handoff of ['5', '{"LOGIN":"2"}', '{"LOGIN":"2","REFRESH":"ABSENT","SIGN_OUT":"5","PAYLOAD_UPDATE":"5"}']) {
        const invalid = [...historical]; invalid[3] = handoff;
        assert.notStrictEqual(run('upgrade', {}, invalid).status, 0);
        assert.strictEqual(calls().length, 0, 'Invalid plan must fail before backend setup or test dispatch');
    }
    const repository = path.resolve(__dirname, '../..');
    const ci = fs.readFileSync(path.join(repository, '.circleci/setupAndTestWithFreeCore.sh'), 'utf8');
    assert(ci.includes('supertokens-node.git#$2') && ci.includes('git checkout $coreTag'));
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
