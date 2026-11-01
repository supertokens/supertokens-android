const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { collectSelection } = require('./publishedUpgradeExport');
const { selectors, flows, key, names } = require('./upgradeEvidence');
const { hash } = require('./integrationEvidence');
const failures = require('./fixtures/published-cookie-failures.json');
const { normalize } = require('../testHelpers/testapp/upgradeHandoff');

function fixture(mode = 'COOKIE', route = '/login') {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'upgrade-export-'));
    const root = path.join(directory, 'repo'), owned = path.join(directory, 'owned');
    const nonce = 'b'.repeat(48), runId = 'c'.repeat(48), instance = 'd'.repeat(48);
    const context = { schema: 1, sha: 'e'.repeat(40), workflow: 'fake-workflow', attempt: 12, fdi: '4.1',
        backend: 'http://127.0.0.1:18081', core: 'http://127.0.0.1:19001', sources: 'f'.repeat(64),
        policySha256: 'a'.repeat(64), nonce: 'e'.repeat(48) };
    const selector = selectors.find(value => value.mode === mode && value.route === route);
    const plan = { nodeVersion: '99.1.0', coreVersion: '99.2.0', phaseVersions: Object.fromEntries(selectors.map(value =>
        [key(value), { publishedLogin: value.route === '/login-2.18' ? '2' : '5',
            publishedHandoff: { ...normalize('5', true), LOGIN: value.route === '/login-2.18' ? '2' : '5' }, candidateRefresh: '5' }])) };
    const versions = plan.phaseVersions[key(selector)];
    const reportDirectory = path.join(root, 'testHelpers/testapp/app/build/integration-receipts', nonce);
    const properties = ['-Pupgrade.baseUrl=' + context.backend, '-Pupgrade.fdiVersion=' + context.fdi,
        '-Pupgrade.expectedPublishedLoginVersion=' + versions.publishedLogin,
        '-Pupgrade.expectedPublishedHandoffVersions=' + JSON.stringify(normalize(versions.publishedHandoff)), '-Pupgrade.expectedCandidateRefreshVersion=5',
        ...(route === '/login-2.18' ? ['-Pupgrade.loginRoute=' + route] : [])];
    function json(file, value) { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, JSON.stringify(value)); }
    function mutate(file, change) { const value = JSON.parse(fs.readFileSync(file)); change(value); json(file, value); }
    json(path.join(owned, 'upgrade-completed.json'), { schema: 1, nonce, runId, instance, directory: reportDirectory,
        mode, route, sources: context.sources, backend: context.backend });
    json(path.join(owned, 'exposure-config.json'), { runId, instance, profile: 'upgrade',
        selected: { mode, route, exposed: selector.enableJWT, properties }, flags: { sessionJwt: selector.enableJWT },
        capabilities: { compatible: true, coreReachable: true, qualificationProxy: false, nodeVersion: plan.nodeVersion, coreCdiVersions: ['2.18'] },
        identity: { nodeSdkVersion: plan.nodeVersion, coreVersion: plan.coreVersion, coreSha256: '1'.repeat(64), sdkImplementationSha256: '2'.repeat(64) } });
    json(path.join(reportDirectory, 'preparation.json'), { root, nonce, mode: 'upgrade', sources: context.sources,
        commands: [['--rerun-tasks', '--no-build-cache'], ['--rerun-tasks', '--no-build-cache', ...properties]],
        artifactHashes: { 'app/build/outputs/aar/app-debug.aar': '3'.repeat(64) } });
    json(path.join(reportDirectory, 'completed.json'), { nonce });
    const cases = names(selector).map(full => { const split = full.indexOf('.publishedSession');
        return { className: full.slice(0, split), name: full.slice(split + 1), result: 'SUCCESS' }; });
    json(path.join(reportDirectory, 'reports/upgradeLiveTest.json'), { tests: 4, passed: 4, failed: 0, skipped: 0, cases,
        rerun: true, excludedTasks: [], excludes: [] });
    for (const flow of flows) json(path.join(reportDirectory, 'reports/upgrade-observations', flow + '.json'), {
        schema: 1, mode, flow, route, fdi: context.fdi, backend: context.backend, nodeVersion: plan.nodeVersion,
        coreCdiVersions: ['2.18'], sessionJwt: selector.enableJWT, runId, preparationNonce: nonce,
        publishedFirst: true, candidateContinued: true, scopedCleanup: true,
        observed: { schemaVersion: 2, publishedLogin: { status: 'PRESENT', tokenVersion: versions.publishedLogin },
            publishedHandoff: flow === 'SIGN_OUT' ? { status: 'ABSENT', tokenVersion: null } : { status: 'PRESENT', tokenVersion: versions.publishedHandoff[flow] },
            candidateRefresh: { status: 'PRESENT', tokenVersion: '5' } },
    });
    json(path.join(owned, 'owner.json'), { root: owned, runId });
    const lifecycle = ['BEFORE_CORE_CLEANUP', 'CORE_CLEANUP_ACKNOWLEDGED', 'BACKEND_STOPPED'].map(action => JSON.stringify({ action })).join('\n');
    fs.writeFileSync(path.join(owned, 'process-tree.jsonl'), lifecycle);
    fs.writeFileSync(path.join(owned, 'dependencies.sha256'), '4'.repeat(64));
    json(path.join(owned, 'lifecycle-completed.json'), { schema: 1, root: owned, runId, instance, command: ['fake-owned-upgrade'], exitCode: 0,
        dependenciesSha256: '4'.repeat(64), lifecycleSha256: hash(lifecycle) });
    fs.mkdirSync(path.join(root, 'app/src/test/compatibility'), { recursive: true });
    fs.writeFileSync(path.join(root, 'app/src/test/compatibility/legacy054.sha256'), '2f3b4a94a036d21ced610f9c41707d7e1ccf7c9c0256e47e5686c1c76b8224b9');
    return { root, owned, context, plan, selector, reportDirectory, json, mutate,
        collect: () => collectSelection(root, owned, context, plan, selector),
        close: () => fs.rmSync(directory, { recursive: true }) };
}

test('exports actual observed primary phases only after scoped and owned cleanup', () => {
    const f = fixture();
    try {
        const result = f.collect();
        assert.equal(result.rows.length, 4); assert.equal(result.rows.find(row => row.flow === 'SIGN_OUT').publishedHandoff, 'ABSENT');
        assert.equal(result.preparation.candidateAarSha256, '3'.repeat(64));
        assert.equal(result.ownedCleanup, true);
    } finally { f.close(); }
});
test('historical qualification comes from exact four cleaned-up flows and fresh ownership', () => {
    const f = fixture('HEADER', '/login-2.18');
    try {
        const result = f.collect();
        assert.equal(result.legacy218Evidence.qualification, 'published-first-four-flows');
        assert.equal(result.legacy218Evidence.runId, result.runId);
        assert.equal(result.legacy218Evidence.preparationNonce, result.preparationNonce);
        assert.equal(result.legacy218Evidence.sources, f.context.sources);
        assert.equal(result.rows.length, 4);
        assert.deepEqual(result.rows.map(row => row.publishedHandoff), ['2', '5', 'ABSENT', '5']);
    } finally { f.close(); }
});
test('old primary scalar CLI receipts normalize against exact per-flow policy', () => {
    const f = fixture();
    try {
        f.mutate(path.join(f.owned, 'exposure-config.json'), value => { value.selected.properties[3] = '-Pupgrade.expectedPublishedHandoffVersion=5'; });
        f.mutate(path.join(f.reportDirectory, 'preparation.json'), value => { value.commands[1][5] = '-Pupgrade.expectedPublishedHandoffVersion=5'; });
        assert.equal(f.collect().rows.find(row => row.flow === 'SIGN_OUT').publishedHandoff, 'ABSENT');
    } finally { f.close(); }
});
for (const [label, change] of [
    ['swapped handoff observations', f => {
        f.mutate(path.join(f.reportDirectory, 'reports/upgrade-observations/LOGIN.json'), row => { row.observed.publishedHandoff.tokenVersion = '5'; });
        f.mutate(path.join(f.reportDirectory, 'reports/upgrade-observations/REFRESH.json'), row => { row.observed.publishedHandoff.tokenVersion = '2'; });
    }],
    ['missing handoff plan key', f => { delete f.plan.phaseVersions[key(f.selector)].publishedHandoff.REFRESH; }],
    ['primary completion', f => f.mutate(path.join(f.owned, 'upgrade-completed.json'), row => { row.route = '/login'; })],
    ['primary configuration', f => f.mutate(path.join(f.owned, 'exposure-config.json'), row => { row.selected.route = '/login'; })],
    ['primary report', f => f.mutate(path.join(f.reportDirectory, 'reports/upgradeLiveTest.json'), row => {
        row.cases.forEach(value => { value.name = value.name.replace('/login-2.18', '/login'); });
    })],
    ['primary observation', f => f.mutate(path.join(f.reportDirectory, 'reports/upgrade-observations/LOGIN.json'), row => { row.route = '/login'; })],
    ['missing CDI', f => f.mutate(path.join(f.owned, 'exposure-config.json'), row => { row.capabilities.coreCdiVersions = ['5.0']; })],
    ['missing route property', f => f.mutate(path.join(f.reportDirectory, 'preparation.json'), row => { row.commands[1].pop(); })],
    ['cleanup failure', f => fs.unlinkSync(path.join(f.owned, 'lifecycle-completed.json'))],
    ['stale nonce', f => f.mutate(path.join(f.reportDirectory, 'reports/upgrade-observations/LOGIN.json'), row => { row.preparationNonce = 'a'.repeat(48); })],
]) test('historical export rejects ' + label, () => {
    const f = fixture('HEADER', '/login-2.18');
    try { change(f); assert.throws(f.collect); } finally { f.close(); }
});
for (const [label, change] of [
    ['missing actual phase', f => f.mutate(path.join(f.reportDirectory, 'reports/upgrade-observations/REFRESH.json'), row => { delete row.observed; })],
    ['wrong actual phase', f => f.mutate(path.join(f.reportDirectory, 'reports/upgrade-observations/REFRESH.json'), row => { row.observed.candidateRefresh.tokenVersion = '4'; })],
    ['wrong attempt nonce', f => f.mutate(path.join(f.reportDirectory, 'reports/upgrade-observations/REFRESH.json'), row => { row.preparationNonce = 'a'.repeat(48); })],
    ['missing scoped cleanup', f => f.mutate(path.join(f.reportDirectory, 'reports/upgrade-observations/LOGIN.json'), row => { row.scopedCleanup = false; })],
    ['missing owned cleanup completion', f => fs.unlinkSync(path.join(f.owned, 'lifecycle-completed.json'))],
    ['failed owned command', f => f.mutate(path.join(f.owned, 'lifecycle-completed.json'), value => { value.exitCode = 1; })],
    ['wrong owned instance', f => f.mutate(path.join(f.owned, 'lifecycle-completed.json'), value => { value.instance = 'a'.repeat(48); })],
    ['retained backend', f => f.json(path.join(f.owned, 'RETAINED.json'), {})],
    ['changed Core version', f => f.mutate(path.join(f.owned, 'exposure-config.json'), value => { value.identity.coreVersion = '1.0.0'; })],
    ['changed exposure config', f => f.mutate(path.join(f.owned, 'exposure-config.json'), value => { value.flags.sessionJwt = true; })],
    ['skipped report', f => f.mutate(path.join(f.reportDirectory, 'reports/upgradeLiveTest.json'), value => { value.skipped = 1; })],
]) test('export rejects ' + label, () => {
    const f = fixture(); try { change(f); assert.throws(f.collect); } finally { f.close(); }
});
for (const mode of ['COOKIE', 'EXPOSED_COOKIE']) test('actual aa1ca82d ' + mode + ' failed rows cannot be exported', () => {
    const f = fixture(mode);
    try {
        f.json(path.join(f.reportDirectory, 'reports/upgradeLiveTest.json'), failures[mode].report);
        assert.throws(f.collect, /failures\/skips/);
        assert.equal(failures[mode].report.failed, 2);
    } finally { f.close(); }
});
