const fs = require('fs');
const path = require('path');
const assert = require('assert');
const { spawnSync } = require('child_process');

function support(capabilities) {
    if (capabilities.enabled !== true || capabilities.compatible !== true || capabilities.qualificationProxy !== false) {
        throw new Error('Matrix requires a reachable compatible real nonproxy fixture');
    }
    return { mapped: capabilities.mappedGoogleSession === true,
        mutation: capabilities.mappedGoogleSession === true && capabilities.nonPublicTenantSession === true };
}
function accept(report, names) {
    assert.strictEqual(report.tests, names.length);
    assert.strictEqual(report.passed, names.length);
    assert.strictEqual(report.skipped, 0);
    assert.strictEqual(report.failed, 0);
    assert.deepStrictEqual(report.cases.map(test => test.className + '.' + test.name).sort(), names.slice().sort());
}
module.exports = async function matrix({ root, backend, env, capabilities, contextFile }) {
    const available = support(capabilities);
    const evidence = path.join(env.INSTALL_PATH, 'matrix');
    fs.mkdirSync(evidence);
    const statuses = [];
    const receiptRoot = path.join(root, 'testHelpers/testapp/app/build/integration-receipts');
    const startedReceipts = fs.existsSync(receiptRoot) ? fs.readdirSync(receiptRoot) : [];
    const releaseEvidence = require('../../.circleci/integrationEvidence');
    const sourceSha256 = releaseEvidence.hash(fs.readFileSync(__filename));
    function record(name, status, details = {}) {
        statuses.push({ name, status, ...details });
        fs.writeFileSync(path.join(evidence, 'steps.json'), JSON.stringify(statuses, null, 2));
    }
    function run(name, command, args, cwd = root, additional = {}) {
        record(name, 'running', { command, args });
        const result = spawnSync(command, args, { cwd, env: { ...env, ...additional }, stdio: 'inherit' });
        if (result.error || result.status !== 0) {
            record(name, 'failed', { exit: result.status, signal: result.signal });
            throw result.error || new Error(name + ' failed: ' + (result.signal || result.status));
        }
        record(name, 'passed', { execution: { exit: result.status, argv: [command, ...args], sourceSha256 } });
    }
    function gradle(name, args) {
        const reports = path.join(evidence, name);
        run(name, path.join(root, 'gradlew'), ['--no-daemon', '--no-build-cache', '--rerun-tasks',
            '-I', path.join(__dirname, 'matrixReports.gradle'), ...args], root, { ST_MATRIX_REPORTS: reports });
        return reports;
    }
    function excluded(name, reason) {
        record(name, 'excluded', { reason, capabilities });
        console.log('EXCLUDED ' + name + ': ' + reason + '; not a pass');
    }
    if (available.mapped) run('node-core', 'npm', ['run', 'test:core'], backend);
    else excluded('node-core', 'Exact pair lacks mapped-owner fixture APIs');
    gradle('root-build', ['clean', 'build']);
    run('reporting-contract', process.execPath, ['--test', path.join(root, 'testHelpers/server/matrixReports.contract.test.js')]);
    const baselineReports = gradle('core-baseline', ['-PcoreProtocolUrl=' + env.ST_TEST_BACKEND_URL, ':app:testDebugUnitTest',
        '--tests', 'com.supertokens.session.CoreSdkCompatibilityTest']);
    accept(JSON.parse(fs.readFileSync(path.join(baselineReports, 'testDebugUnitTest/result.json'), 'utf8')),
        releaseEvidence.exactCases['core-baseline']);
    if (available.mapped) {
        const reports = gradle('core-classes', ['-PcoreProtocolUrl=' + env.ST_TEST_BACKEND_URL, ':app:testDebugUnitTest',
            '--tests', 'com.supertokens.session.CoreSessionProtocolTest',
            '--tests', 'com.supertokens.session.CoreLostRefreshResponseTest']);
        const result = JSON.parse(fs.readFileSync(path.join(reports, 'testDebugUnitTest/result.json'), 'utf8'));
        accept(result, releaseEvidence.exactCases['core-classes']);
    } else excluded('core-classes', 'Exact pair lacks mapped-owner fixture APIs');
    if (available.mutation) run('mutation', process.execPath, [path.join(root, 'testHelpers/testapp/runTests.js'), 'mutation']);
    else excluded('mutation', 'Exact pair lacks mapped-owner or non-public-tenant fixture APIs');
    if (available.mapped) {
        await require('./ownedProxyQualification').qualify({ backend, env, evidence, runTests: async base => {
            const reports = gradle('proxy-tests', ['-PcoreProtocolUrl=' + base, ':app:testDebugUnitTest',
                '--tests', 'com.supertokens.session.CoreProxyProtocolTest']);
            accept(JSON.parse(fs.readFileSync(path.join(reports, 'testDebugUnitTest/result.json'), 'utf8')), [
                'com.supertokens.session.CoreProxyProtocolTest.refreshDeliveredBeforePromotion',
                'com.supertokens.session.CoreProxyProtocolTest.promotionDeliveredBeforeRefresh'
            ]);
        } });
        record('proxy-qualification', 'passed', { executed: 2, skipped: 0, leaks: 0,
            execution: { exit: 0, argv: ['ownedProxyQualification.qualify'],
                sourceSha256: releaseEvidence.hash(fs.readFileSync(path.join(__dirname, 'ownedProxyQualification.js'))) } });
    } else excluded('proxy-qualification', 'Exact pair lacks mapped-owner fixture APIs');
    for (const profile of ['ordinary', 'retrofit']) {
        run(profile, process.execPath, [path.join(root, 'testHelpers/testapp/runTests.js'), profile]);
    }
    record('matrix', 'completed');
    releaseEvidence.produce({ root, env, capabilities, statuses, evidence, contextFile, startedReceipts });
};
module.exports.support = support;
module.exports.accept = accept;
