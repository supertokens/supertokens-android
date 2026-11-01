const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { parse, record } = require('./upgradeTerminal');
const stage = path.resolve(__dirname, '../..');
const recorded = fs.readFileSync(path.join(__dirname, 'fixtures/recorded-exposed-cookie.xml'), 'utf8');
const flows = ['LOGIN', 'REFRESH', 'SIGN_OUT', 'PAYLOAD_UPDATE'];
const nonce = 'a'.repeat(48);
const pin = '2f3b4a94a036d21ced610f9c41707d7e1ccf7c9c0256e47e5686c1c76b8224b9';
function summary() {
    return { tests: 4, passed: 2, failed: 2, skipped: 0, rerun: true, excludes: [], excludedTasks: [],
        cases: flows.map(flow => ({ className: 'com.example.example.PublishedUpgradeLiveTest',
            name: 'publishedSessionContinuesAgainstConfiguredFdiMember[EXPOSED_COOKIE-' + flow + '-/login]',
            result: ['REFRESH', 'SIGN_OUT'].includes(flow) ? 'FAILURE' : 'SUCCESS' })) };
}
// Only execution observations are synthetic. Exception stacks and suite outcomes are recorded unchanged.
function withObservedExecution() {
    return recorded.replace(']]></system-out>', flows.map(flow => 'UPGRADE_EXECUTION ' + JSON.stringify({ nonce,
        mode: 'EXPOSED_COOKIE', flow, phase: ['REFRESH', 'SIGN_OUT'].includes(flow) ? 'published' : 'candidate',
        candidateEntered: !['REFRESH', 'SIGN_OUT'].includes(flow) })).join('\n') + '\n]]></system-out>');
}
const parseFixture = xml => parse(xml, summary(), 'EXPOSED_COOKIE', '/login', nonce);

test('recorded Java11 failure XML alone cannot invent candidate entry observations', () => {
    assert.throws(() => parseFixture(recorded), /Missing\/duplicate actual execution/);
});
test('recorded real stacks plus explicit synthetic execution events classify exact two failures', () => {
    const result = parseFixture(withObservedExecution());
    assert.equal(result.report.failed, 2);
    assert.equal(result.report.passed, 2);
    assert.deepEqual(result.failures.map(row => row.rootType), ['java.lang.IllegalArgumentException', 'java.lang.IllegalArgumentException']);
    assert.ok(result.failures.every(row => row.candidateInvoked === false && row.raw.includes('Legacy054Driver.')));
});
for (const [name, mutate, reason] of [
    ['candidate entered', xml => xml.replaceAll('"candidateEntered":false', '"candidateEntered":true'), /Candidate\/setup/],
    ['candidate phase', xml => xml.replaceAll('"phase":"published"', '"phase":"candidate"'), /Candidate\/setup/],
    ['published stack absent', xml => xml.replaceAll('Legacy054Driver.refresh', 'CandidateDriver.refresh'), /published driver/],
    ['message in unrelated failure', xml => xml.replaceAll('java.lang.IllegalArgumentException', 'java.lang.IllegalStateException'), /Unexpected failure/],
    ['suppressed cleanup failure', xml => xml.replace('</failure>', '\nSuppressed: java.lang.AssertionError: cleanup failed\n</failure>'), /suppressed/],
    ['duplicate case', xml => xml.replace('EXPOSED_COOKIE-LOGIN-/login', 'EXPOSED_COOKIE-REFRESH-/login'), /Incomplete\/duplicate/],
    ['wrong totals', xml => xml.replace('failures="2"', 'failures="1"'), /totals/],
    ['interrupted XML', xml => xml.slice(0, -100), /XML validation failed/],
    ['wrong nonce', xml => xml.replaceAll(nonce, 'b'.repeat(48)), /Stale/],
    ['unproven fixture cleanup', xml => xml.replace('cleaned=true', 'cleaned=false'), /fixture cleanup/],
    ['DTD', xml => xml.replace('<testsuite', '<!DOCTYPE x><testsuite'), /declarations/],
]) test('reject ' + name, () => assert.throws(() => parseFixture(mutate(withObservedExecution())), reason));

function directory(t, xml = withObservedExecution()) {
    const directory = fs.mkdtempSync(path.join(stage, 'terminal-host-'));
    t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
    fs.mkdirSync(path.join(directory, 'reports/xml/upgradeLiveTest'), { recursive: true });
    fs.writeFileSync(path.join(directory, 'reports/xml/upgradeLiveTest/TEST-com.example.example.PublishedUpgradeLiveTest.xml'), xml);
    fs.writeFileSync(path.join(directory, 'reports/upgradeLiveTest.json'), JSON.stringify(summary()));
    return directory;
}
function input(directory) {
    return { directory, binding: { publishedAarSha256: pin }, nonce, selector: { mode: 'EXPOSED_COOKIE', route: '/login' },
        argv: ['gradlew', ':app:upgradeLiveTest'], startedMs: 1, endedMs: 2,
        result: { status: 1, signal: null }, javaVersion: 'openjdk version "11.0.32"', task: 'test',
        log: "Execution failed for task ':app:upgradeLiveTest'.\n> There were failing tests.\n" };
}
test('real receipt writer archives failed terminal and raw XML without success receipts', t => {
    const dir = directory(t), result = record(input(dir));
    const terminal = JSON.parse(fs.readFileSync(path.join(dir, result.terminalExecution.path)));
    assert.equal(terminal.exit, 1); assert.equal(terminal.outcome, 'test-failure');
    assert.equal(fs.readFileSync(path.join(dir, terminal.xml.path), 'utf8'), withObservedExecution());
    assert.equal(fs.existsSync(path.join(dir, 'completed.json')), false);
    assert.equal(fs.existsSync(path.join(dir, 'upgrade-completed.json')), false);
    assert.throws(() => record(input(dir)), /EEXIST/);
});
test('actual receipt writer feeds the composite classifier without rewriting recorded failures', t => {
    const dir = directory(t), value = input(dir);
    value.argv.push('--rerun-tasks', '--no-build-cache');
    const result = record(value);
    const expected = { selector: { mode: 'EXPOSED_COOKIE', route: '/login', enableJWT: true }, binding: value.binding,
        preparationNonce: nonce, jvmArgv: value.argv, startedMs: 1, endedMs: 3, jvmWorkersDrained: true };
    const classified = require('../../.circleci/nativeUpgradeEvidence').classifyJvm(result, expected, dir);
    assert.deepEqual(classified.nativeFlows, ['REFRESH', 'SIGN_OUT']);
    assert.equal(classified.terminal.exit, 1);
    assert.deepEqual(classified.report.cases.map(row => row.result), ['SUCCESS', 'FAILURE', 'FAILURE', 'SUCCESS']);
});
for (const [name, mutate] of [
    ['abort', value => value.result = { status: null, signal: 'SIGTERM' }],
    ['build error', value => value.log = "Execution failed for task ':app:compileDebugJava'.\n"],
    ['mixed build and test errors', value => value.log += "Execution failed for task ':app:otherTask'.\n"],
    ['spawn error', value => value.result = { status: null, error: new Error('ENOENT') }],
    ['reported failure with successful process', value => value.result.status = 0],
    ['artifact preparation failure', value => value.task = 'prepare'],
]) test('archive but never classify ' + name, t => {
    const dir = directory(t), value = input(dir); mutate(value);
    const result = record(value), terminal = JSON.parse(fs.readFileSync(path.join(dir, result.terminalExecution.path)));
    assert.equal(terminal.outcome, 'unclassified'); assert.ok(terminal.validationError);
    assert.equal(terminal.exit, value.result.status);
});

test('concrete exporter re-parses original XML and authenticates observations and terminal pointer', t => {
    const root = directory(t), selector = { mode: 'EXPOSED_COOKIE', route: '/login', enableJWT: true };
    const { selectors, key } = require('../../.circleci/upgradeEvidence');
    const { bindingFor, collectExpected, observations } = require('../../.circleci/publishedNativeUpgradeExport');
    const { sourceDigest } = require('./artifactReceipt');
    const { commands } = require('./runTests');
    const { write, hash } = require('./upgradeTerminal');
    const owned = path.join(root, 'owned-integration-evidence/upgrade-EXPOSED_COOKIE/ordinary-owned-host');
    const receipt = path.join(root, 'testHelpers/testapp/app/build/integration-receipts', nonce);
    fs.mkdirSync(owned, { recursive: true }); fs.mkdirSync(receipt, { recursive: true });
    fs.cpSync(path.join(root, 'reports'), path.join(receipt, 'reports'), { recursive: true });
    const { configFile } = require('../server/nativeHostFixtures').toolsFixture(root);
    const context = { schema: 1, sha: 'a'.repeat(40), workflow: 'host', attempt: 1, fdi: '1.16',
        nonce: 'b'.repeat(48), policySha256: 'c'.repeat(64), backend: 'http://127.0.0.1:18081', core: 'http://127.0.0.1:19001',
        sources: sourceDigest(root) };
    const plan = { nodeVersion: '16.7.0', coreVersion: '9.3.0', review: { reference: 'synthetic-only' },
        phaseVersions: Object.fromEntries(selectors.map(value => [key(value), { publishedLogin: '5',
            publishedHandoff: { LOGIN: '5', REFRESH: '5', SIGN_OUT: 'ABSENT', PAYLOAD_UPDATE: '5' }, candidateRefresh: '5' }])) };
    const selected = require('../server/ownedExposure').select('upgrade', ['EXPOSED_COOKIE', context.fdi, '5',
        JSON.stringify(plan.phaseVersions[key(selector)].publishedHandoff), '5'], context.backend);
    const runId = '1'.repeat(48), instance = '2'.repeat(48), candidate = '3'.repeat(64);
    write(owned, 'owner.json', { root: owned, runId });
    fs.writeFileSync(path.join(owned, 'dependencies.sha256'), '4'.repeat(64));
    write(owned, 'exposure-config.json', { profile: 'upgrade', runId, instance, selected, flags: { sessionJwt: true },
        capabilities: { compatible: true, coreReachable: true, qualificationProxy: false, nodeVersion: plan.nodeVersion, coreCdiVersions: ['2.18'] },
        identity: { nodeSdkVersion: plan.nodeVersion, coreVersion: plan.coreVersion, coreSha256: '5'.repeat(64),
            sdkImplementationSha256: '6'.repeat(64), effectiveClasspathSha256: '7'.repeat(64) } });
    const binding = bindingFor(root, owned, context, plan, selector, candidate);
    const configuredPath = path.join(owned, 'exposure-config.json');
    const originalConfig = fs.readFileSync(configuredPath);
    const scalarConfig = JSON.parse(originalConfig);
    scalarConfig.selected = require('../server/ownedExposure').select('upgrade', ['EXPOSED_COOKIE', context.fdi, '5', '5', '5'], context.backend);
    fs.writeFileSync(configuredPath, JSON.stringify(scalarConfig));
    assert.deepEqual(bindingFor(root, owned, context, plan, selector, candidate), binding);
    fs.writeFileSync(configuredPath, originalConfig);
    const exactCommands = commands('upgrade', selected.properties, context.backend);
    write(receipt, 'preparation.json', { root, nonce, mode: 'upgrade', sources: context.sources, commands: exactCommands,
        artifactHashes: { 'app/build/outputs/aar/app-debug.aar': candidate } });
    const value = input(receipt);
    value.binding = binding; value.argv = [path.join(root, 'testHelpers/testapp/gradlew'), ...exactCommands[1]];
    const jvm = record(value);
    write(owned, 'upgrade-terminal.json', { schema: 1, kind: 'jvm-terminal-pointer', nonce, directory: receipt, runId, instance, ...jvm });
    const drainLog = JSON.stringify({ action: 'DRAINED' });
    fs.writeFileSync(path.join(owned, 'jvm-process-tree.jsonl'), drainLog);
    write(owned, 'jvm-drained.json', { nonce, runId, exit: 1, logSha256: hash(drainLog) });
    write(owned, 'composite-window.json', { startedMs: 1, endedMs: 3 });
    const observationRoot = path.join(receipt, 'reports/upgrade-observations');
    fs.mkdirSync(observationRoot);
    for (const flow of ['LOGIN', 'PAYLOAD_UPDATE']) write(observationRoot, flow + '.json', {
        flow, mode: selector.mode, route: selector.route, fdi: context.fdi, backend: context.backend,
        runId, preparationNonce: nonce, nodeVersion: plan.nodeVersion, sessionJwt: true,
        publishedFirst: true, candidateContinued: true, scopedCleanup: true,
        observed: { schemaVersion: 2, publishedLogin: { status: 'PRESENT', tokenVersion: '5' },
            publishedHandoff: { status: 'PRESENT', tokenVersion: '5' }, candidateRefresh: { status: 'PRESENT', tokenVersion: '5' } } });
    jvm.successfulObservations = observations(receipt, binding, nonce, summary());
    const selection = { ...selector, owned, binding, preparationNonce: nonce, jvm, android: [] };
    require('../server/nativeExecutor').freeze(root, owned, context, configFile);
    const collect = () => collectExpected(root, selection, selector, context, plan, candidate);
    assert.throws(collect, /Native lease cleanup not proven/);
    jvm.successfulObservations[0].observed.publishedLogin.tokenVersion = '2';
    assert.throws(collect, /observations differ/);
    jvm.successfulObservations = observations(receipt, binding, nonce, summary());
    const detailFile = path.join(receipt, jvm.failureDetails.path);
    const details = JSON.parse(fs.readFileSync(detailFile)); details[0].candidateInvoked = true;
    fs.writeFileSync(detailFile, JSON.stringify(details));
    assert.throws(collect, /hash mismatch/);
});
