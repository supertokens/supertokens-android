const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { validate, writeReceipt } = require('./integrationReceipt');
const { config, hash, lanes, validateEvidence, exactCases, cleanupProof } = require('./integrationEvidence');
const { selectors, flows, key, names } = require('./upgradeEvidence');
const { primaryCommand, preflight, selectionDirectory } = require('./publishedUpgradeAdapter');
const knownCookieFailures = require('./fixtures/published-cookie-failures.json');
const { normalize } = require('../testHelpers/testapp/upgradeHandoff');

// Synthetic expectations are test inputs, never deployed backend/version defaults.
const testPolicy = JSON.parse(fs.readFileSync(path.join(__dirname, 'integration-policy.json')));
for (const fdi of Object.keys(testPolicy.upgrade)) testPolicy.upgrade[fdi] = { nodeVersion: '99.1.0', coreVersion: '99.2.0',
    phaseVersions: Object.fromEntries(selectors.map(selector => [key(selector), {
        publishedLogin: selector.route === '/login-2.18' ? '2' : '5',
        publishedHandoff: { ...normalize('5', true), LOGIN: selector.route === '/login-2.18' ? '2' : '5' }, candidateRefresh: '5' }])) };
const policyText = Buffer.from(JSON.stringify(testPolicy));
const policy = { policy: JSON.parse(policyText), policySha256: hash(policyText) };
const r7 = ['node-core', 'core-classes', 'mutation', 'proxy-qualification'];
const removalCases = [false, true].map(urlConnection => ({
    className: 'com.example.example.MissingRefreshCookieLiveTest',
    name: 'missingRefreshCookieExplicitRemovalClearsSession[urlConnection=' + urlConnection + ']', result: 'SUCCESS'
}));
function upgradeFixture(context) {
    const plan = policy.policy.upgrade[context.fdi];
    return { ...context, nodeVersion: plan.nodeVersion, coreVersion: plan.coreVersion, publishedVersion: '0.5.4',
        coreSha256: '7'.repeat(64), nodeSdkSha256: '8'.repeat(64),
        publishedAarSha256: '2f3b4a94a036d21ced610f9c41707d7e1ccf7c9c0256e47e5686c1c76b8224b9', candidateAarSha256: '6'.repeat(64),
        selections: selectors.map((selector, index) => ({ ...selector, sessionJwt: selector.enableJWT,
            coreCdiVersions: ['2.18'],
            ...(selector.route === '/login-2.18' ? { legacy218Evidence: { status: 'passed', route: '/login-2.18',
                nodeVersion: plan.nodeVersion, fdiVersion: context.fdi, coreCdiVersions: ['2.18'],
                runId: String(index + 1).repeat(48), preparationNonce: String(index + 5).repeat(48),
                sources: context.sources, qualification: 'published-first-four-flows' } } : {}),
            backend: context.backend, core: context.core, nodeVersion: plan.nodeVersion, coreVersion: plan.coreVersion,
            coreSha256: '7'.repeat(64), nodeSdkSha256: '8'.repeat(64), qualifiedIdentitySha256: '9'.repeat(64),
            runId: String(index + 1).repeat(48), preparationNonce: String(index + 5).repeat(48),
            preparation: { nonce: String(index + 5).repeat(48), sources: context.sources, sha: context.sha, candidateAarSha256: '6'.repeat(64),
                publishedAarSha256: '2f3b4a94a036d21ced610f9c41707d7e1ccf7c9c0256e47e5686c1c76b8224b9' },
            freshPreparation: true, completed: true, scopedCleanup: true, ownedCleanup: true, cleanupSha256: 'a'.repeat(64),
            report: { tests: 4, passed: 4, failed: 0, skipped: 0, cases: names(selector).map(identity => {
                const split = identity.indexOf('.publishedSession');
                return { className: identity.slice(0, split), name: identity.slice(split + 1), result: 'SUCCESS' };
            }) }, rows: flows.map(flow => ({ flow, publishedFirst: true, scopedCleanup: true, candidateContinued: true,
                publishedLogin: plan.phaseVersions[key(selector)].publishedLogin,
                publishedHandoff: plan.phaseVersions[key(selector)].publishedHandoff[flow], candidateRefresh: '5' })) })) };
}
function fixture(fdi = '4.1') {
    const context = { schema: 1, fdi, sha: 'a'.repeat(40), workflow: 'current-workflow', attempt: 42,
        backend: 'http://127.0.0.1:18081', core: 'http://127.0.0.1:19001',
        policySha256: policy.policySha256, sources: 'b'.repeat(64), nonce: 'c'.repeat(48) };
    const evidence = { schema: 1, context: { ...context }, capabilities: { enabled: true, compatible: true,
        qualificationProxy: false, mappedGoogleSession: true, nonPublicTenantSession: true },
        identity: { runId: 'd'.repeat(48), instance: 'e'.repeat(48), backend: context.backend, core: context.core,
            qualifiedIdentitySha256: 'f'.repeat(64), dependenciesSha256: '1'.repeat(64) },
        cleanup: { runId: 'd'.repeat(48), commandSucceeded: true, coreAcknowledged: true, backendStopped: true,
            dependenciesUnchanged: true, lifecycleSha256: '0'.repeat(64) },
        lanes: lanes.map(name => {
            const identities = exactCases[name];
            const extra = ['ordinary', 'retrofit'].includes(name) ? removalCases : [];
            const count = (identities ? identities.length : 1) + extra.length;
            return { name, status: 'passed', execution: { exit: 0,
                argv: name === 'node-core' ? ['npm', 'run', 'test:core'] : ['fake-test-command', name], sourceSha256: '2'.repeat(64) },
                reports: [{ tests: count, passed: count, skipped: 0, failed: 0,
                    cases: Array.from({ length: count }, (_, index) => {
                        if (extra.length && index >= count - extra.length) return { ...extra[index - (count - extra.length)] };
                         const full = identities ? identities[index] : 'example.Test.case' + index;
                        return { className: full.slice(0, full.lastIndexOf('.')), name: full.slice(full.lastIndexOf('.') + 1), result: 'SUCCESS' };
                    }) }],
                ...(name === 'ordinary' ? { exposedReport: { tests: 3, passed: 3, failed: 0, skipped: 0,
                    cases: exactCases.exposed.map(full => ({ className: full.slice(0, full.lastIndexOf('.')),
                        name: full.slice(full.lastIndexOf('.') + 1), result: 'SUCCESS' })) } } : {}),
                ...(name === 'published-upgrade' ? { upgrade: upgradeFixture(context) } : {}),
                ...(name === 'proxy-qualification' ? { finalState: { owners: { fixtures: [], unresolvedCreations: 0 },
                    state: { active: 0, upstreamActive: 0, ownedFixtures: 0, ownerReservations: 0, unresolvedCreations: 0,
                        gates: { before: { released: true } } } } } : {}) };
        }) };
    return { context, evidence };
}
function receipt(f) { return { fdi: f.context.fdi, status: 'requirements-satisfied', context: f.context, evidence: f.evidence }; }
test('all required executions and reports pass', () => {
    const f = fixture(); validate([receipt(f)], ['4.1'], f.context.sha, f.context.workflow, policy);
});
test('testapp profiles permit only their named dedicated-lane exclusions', () => {
    const f = fixture();
    for (const name of ['ordinary', 'retrofit']) {
        f.evidence.lanes.find(lane => lane.name === name).reports[0].excludes = [
            '**/PublishedUpgradeLiveTest*', '**/BackendMutationCoverageTest*', '**/ExposedCookieLiveTest*'];
    }
    f.evidence.lanes.find(lane => lane.name === 'mutation').reports[0].excludes = ['**/PublishedUpgradeLiveTest*'];
    validate([receipt(f)], ['4.1'], f.context.sha, f.context.workflow, policy);
    for (const name of ['ordinary', 'retrofit', 'mutation', 'core-baseline']) {
        const report = f.evidence.lanes.find(lane => lane.name === name).reports[0];
        const original = report.excludes;
        report.excludes = [...(original || []), '**/OtherTest*'];
        assert.throws(() => validate([receipt(f)], ['4.1'], f.context.sha, f.context.workflow, policy), /Excluded/);
        report.excludes = original;
    }
});
for (const [scenario, mutate] of [
    ['missing', report => null],
    ['wrong identity', report => { report.cases[0].name = 'unrelated'; return report; }],
    ['duplicate identity', report => { report.cases[0] = report.cases[1]; return report; }],
    ['skipped', report => { report.skipped = 1; return report; }],
    ['excluded', report => { report.excludes = ['**/ExposedCookieLiveTest*']; return report; }],
]) test('exposed-cookie ' + scenario + ' evidence rejects receipt', () => {
    const f = fixture();
    const lane = f.evidence.lanes.find(item => item.name === 'ordinary');
    lane.exposedReport = mutate(lane.exposedReport);
    assert.throws(() => validate([receipt(f)], ['4.1'], f.context.sha, f.context.workflow, policy));
});
for (const profile of ['ordinary', 'retrofit']) {
    for (const transport of [false, true]) {
        for (const mutation of ['missing', 'duplicate', 'skipped', 'failed', 'wrong class', 'old class']) {
            test(profile + ' rejects ' + mutation + ' required removal case urlConnection=' + transport, () => {
                const f = fixture();
                const report = f.evidence.lanes.find(lane => lane.name === profile).reports[0];
                const name = 'missingRefreshCookieExplicitRemovalClearsSession[urlConnection=' + transport + ']';
                const index = report.cases.findIndex(row => row.className === 'com.example.example.MissingRefreshCookieLiveTest' && row.name === name);
                assert.notEqual(index, -1);
                if (mutation === 'missing') { report.cases.splice(index, 1); report.tests--; report.passed--; }
                if (mutation === 'duplicate') { report.cases.push({ ...report.cases[index] }); report.tests++; report.passed++; }
                if (mutation === 'skipped') { report.cases[index].result = 'SKIPPED'; report.skipped++; report.passed--; }
                if (mutation === 'failed') { report.cases[index].result = 'FAILURE'; report.failed++; report.passed--; }
                if (mutation === 'wrong class') report.cases[index].className = 'other.MissingRefreshCookieLiveTest';
                if (mutation === 'old class') report.cases[index].className = 'com.example.example.RefreshWithoutClearLiveTest';
                assert.throws(() => validate([receipt(f)], ['4.1'], f.context.sha, f.context.workflow, policy));
            });
        }
    }
}
for (const profile of ['ordinary', 'retrofit']) {
    for (const scenario of ['absent', 'empty', 'duplicate valid', 'split cases', 'actual old identities']) {
        test(profile + ' rejects ' + scenario + ' live removal reports', () => {
            const f = fixture();
            const lane = f.evidence.lanes.find(item => item.name === profile);
            if (scenario === 'absent') delete lane.reports;
            if (scenario === 'empty') lane.reports = [];
            if (scenario === 'duplicate valid') lane.reports.push(JSON.parse(JSON.stringify(lane.reports[0])));
            if (scenario === 'split cases') {
                const first = lane.reports[0];
                const second = JSON.parse(JSON.stringify(first));
                first.cases = first.cases.filter(row => !row.name.endsWith('[urlConnection=true]'));
                second.cases = second.cases.filter(row => !row.name.endsWith('[urlConnection=false]'));
                for (const report of [first, second]) report.tests = report.passed = report.cases.length;
                lane.reports.push(second);
            }
            if (scenario === 'actual old identities') {
                for (const row of lane.reports[0].cases) {
                    if (row.className === 'com.example.example.MissingRefreshCookieLiveTest') {
                        row.className = 'com.example.example.RefreshWithoutClearLiveTest';
                        row.name = row.name.replace('missingRefreshCookieExplicitRemovalClearsSession', 'missingRefreshCookieDoesNotClearFrontToken');
                    }
                }
            }
            assert.throws(() => validate([receipt(f)], ['4.1'], f.context.sha, f.context.workflow, policy));
        });
    }
}
test('empty FDI matrix cannot pass vacuously', () => assert.throws(() => validate([], [], 'a'.repeat(40), 'workflow', policy)));
for (const [name, mutate] of [
    ['missing lane', f => { f.evidence.lanes.pop(); }],
    ['duplicate lane', f => { f.evidence.lanes[0] = f.evidence.lanes[1]; }],
    ['excluded mandatory R7', f => { f.evidence.lanes[3].status = 'excluded'; }],
    ['skipped lane', f => { f.evidence.lanes[0].status = 'skipped'; }],
    ['missing execution', f => { delete f.evidence.lanes[0].execution; }],
    ['failed execution', f => { f.evidence.lanes[0].execution.exit = 1; }],
    ['missing report', f => { delete f.evidence.lanes[0].reports; }],
    ['zero test report', f => { f.evidence.lanes[0].reports[0].tests = 0; }],
    ['skipped test', f => { f.evidence.lanes[0].reports[0].skipped = 1; }],
    ['excluded test task', f => { f.evidence.lanes[0].reports[0].excludedTasks = [':test']; }],
    ['failed case', f => { f.evidence.lanes[0].reports[0].cases[0].result = 'FAILURE'; }],
    ['missing identity', f => { delete f.evidence.identity; }],
    ['wrong backend identity', f => { f.evidence.identity.backend = 'http://127.0.0.1:8080'; }],
    ['stale SHA', f => { f.evidence.context.sha = 'b'.repeat(40); }],
    ['wrong workflow', f => { f.evidence.context.workflow = 'previous'; }],
    ['wrong attempt', f => { f.evidence.context.attempt = 41; }],
    ['missing attempt', f => { delete f.evidence.context.attempt; }],
    ['wrong nonce', f => { f.evidence.context.nonce = 'f'.repeat(48); }],
    ['changed source digest', f => { f.evidence.context.sources = 'a'.repeat(64); }],
    ['changed config', f => { f.evidence.context.policySha256 = 'a'.repeat(64); }],
    ['proxy leak', f => { f.evidence.lanes[6].finalState.state.active = 1; }],
    ['missing baseline', f => { f.evidence.lanes = f.evidence.lanes.filter(lane => lane.name !== 'core-baseline'); }],
    ['wrong baseline identities with correct counts', f => { f.evidence.lanes[7].reports[0].cases[0].name = 'differentMethod'; }],
    ['excluded baseline', f => { f.evidence.lanes[7].status = 'excluded'; }],
    ['missing upgrade', f => { delete f.evidence.lanes[8].upgrade; }],
    ['excluded upgrade', f => { f.evidence.lanes[8].status = 'excluded'; }],
    ['stale upgrade source', f => { f.evidence.lanes[8].upgrade.sources = 'a'.repeat(64); }],
    ['stale upgrade attempt', f => { f.evidence.lanes[8].upgrade.attempt = 41; }],
    ['wrong upgrade FDI', f => { f.evidence.lanes[8].upgrade.fdi = '1.16'; }],
    ['wrong upgrade Node', f => { f.evidence.lanes[8].upgrade.nodeVersion = '1.0.0'; }],
    ['wrong upgrade Core', f => { f.evidence.lanes[8].upgrade.coreVersion = '1.0.0'; }],
    ['wrong upgrade phase', f => { f.evidence.lanes[8].upgrade.selections[0].rows[0].candidateRefresh = '4'; }],
    ['upgrade not published-first', f => { f.evidence.lanes[8].upgrade.selections[0].rows[0].publishedFirst = false; }],
    ['upgrade wrong enableJWT', f => { f.evidence.lanes[8].upgrade.selections[2].enableJWT = false; }],
    ['upgrade cleanup failure', f => { f.evidence.lanes[8].upgrade.selections[0].ownedCleanup = false; }],
    ['upgrade scoped cleanup failure', f => { f.evidence.lanes[8].upgrade.selections[0].rows[0].scopedCleanup = false; }],
    ['missing legacy route', f => { f.evidence.lanes[8].upgrade.selections.pop(); }],
    ['missing legacy route qualification', f => { delete f.evidence.lanes[8].upgrade.selections[3].legacy218Evidence; }],
    ['swapped historical handoffs', f => {
        const rows = f.evidence.lanes[8].upgrade.selections[3].rows;
        rows.find(row => row.flow === 'LOGIN').publishedHandoff = '5';
        rows.find(row => row.flow === 'REFRESH').publishedHandoff = '2';
    }],
    ['stale legacy route nonce', f => { f.evidence.lanes[8].upgrade.selections[3].legacy218Evidence.preparationNonce = 'a'.repeat(48); }],
    ['stale legacy route run', f => { f.evidence.lanes[8].upgrade.selections[3].legacy218Evidence.runId = 'a'.repeat(48); }],
    ['stale legacy route sources', f => { f.evidence.lanes[8].upgrade.selections[3].legacy218Evidence.sources = 'a'.repeat(64); }],
    ['stale upgrade preparation', f => { f.evidence.lanes[8].upgrade.selections[0].preparation.nonce = 'a'.repeat(48); }],
    ['wrong candidate artifact', f => { f.evidence.lanes[8].upgrade.selections[0].preparation.candidateAarSha256 = 'b'.repeat(64); }],
    ['missing owned cleanup', f => { delete f.evidence.cleanup; }],
    ['failed owned cleanup', f => { f.evidence.cleanup.coreAcknowledged = false; }],
]) test(name + ' rejects receipt', () => {
    const f = fixture(); mutate(f);
    assert.throws(() => validate([receipt(f)], ['4.1'], f.context.sha, f.context.workflow, policy));
});
test('unsupported optional older-pair R7 remains excluded, never represented as passed', () => {
    const f = fixture('1.16');
    f.evidence.capabilities.mappedGoogleSession = false;
    f.evidence.capabilities.nonPublicTenantSession = false;
    for (const lane of f.evidence.lanes.filter(lane => r7.includes(lane.name))) {
        lane.status = 'excluded'; lane.reason = 'Exact pair has no mapped-owner fixture';
        delete lane.execution; delete lane.reports;
    }
    validate([receipt(f)], ['1.16'], f.context.sha, f.context.workflow, policy);
    assert.equal(receipt(f).evidence.lanes.filter(lane => lane.status === 'passed').length, 5);
    f.context.fdi = '4.1'; f.evidence.context.fdi = '4.1';
    assert.throws(() => validate([receipt(f)], ['4.1'], f.context.sha, f.context.workflow, policy));
});
test('available optional capability cannot be silently excluded', () => {
    const f = fixture('1.16'); f.evidence.lanes[3].status = 'excluded'; f.evidence.lanes[3].reason = 'not run';
    assert.throws(() => validateEvidence(f.evidence, f.context, policy.policy.required['1.16'], policy.policy.upgrade['1.16']));
});
test('empty configured FDI matrix rejected before execution', () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'empty-fdi-'));
    try {
        fs.mkdirSync(path.join(directory, '.circleci'));
        fs.writeFileSync(path.join(directory, '.circleci/integration-policy.json'), policyText);
        fs.writeFileSync(path.join(directory, 'frontendDriverInterfaceSupported.json'), '{"versions":[]}');
        assert.throws(() => config(directory), /Empty/);
    } finally { fs.rmSync(directory, { recursive: true }); }
});

test('unresolved version plans block every FDI; synthetic success fixtures are not defaults', () => {
    const deployed = JSON.parse(fs.readFileSync(path.join(__dirname, 'integration-policy.json')));
    for (const fdi of Object.keys(deployed.upgrade)) {
        const f = fixture(fdi);
        assert.throws(() => validateEvidence(f.evidence, f.context, deployed.required[fdi], null), /unresolved/);
    }
});
test('baseline test identities are required even on older FDI with unavailable R7', () => {
    const f = fixture('1.16'); f.evidence.capabilities.mappedGoogleSession = false;
    for (const lane of f.evidence.lanes.filter(lane => r7.includes(lane.name))) { lane.status = 'excluded'; lane.reason = 'Unavailable'; }
    f.evidence.lanes[7].reports[0].cases[0].name = 'unrelatedPassingTest';
    assert.throws(() => validate([receipt(f)], ['1.16'], f.context.sha, f.context.workflow, policy), /identities/);
});
test('older FDI without mapped-owner capability still requires exposed-cookie evidence', () => {
    const f = fixture('1.16'); f.evidence.capabilities.mappedGoogleSession = false;
    for (const lane of f.evidence.lanes.filter(lane => r7.includes(lane.name))) { lane.status = 'excluded'; lane.reason = 'Unavailable'; }
    validate([receipt(f)], ['1.16'], f.context.sha, f.context.workflow, policy);
    delete f.evidence.lanes.find(lane => lane.name === 'ordinary').exposedReport;
    assert.throws(() => validate([receipt(f)], ['1.16'], f.context.sha, f.context.workflow, policy), /Unexecuted/);
});
test('baseline and upgrade cannot be removed by a weaker policy', () => {
    const f = fixture();
    assert.throws(() => validateEvidence(f.evidence, f.context, ['root-build', 'ordinary', 'retrofit'], policy.policy.upgrade['4.1']), /weakened/);
});
test('adapter preserves primary CLI and wires all four selectors without directory collisions', () => {
    const f = fixture();
    const { select } = require('../testHelpers/server/ownedExposure');
    assert.deepEqual(primaryCommand('/fixture', f.context, policy.policy.upgrade['4.1'], selectors[1], select),
        [process.execPath, '/fixture/testHelpers/server/runOwnedTests.js', 'upgrade', '/fixture', 'COOKIE', '4.1', '5', JSON.stringify(normalize('5', true)), '5']);
    assert.deepEqual(primaryCommand('/fixture', f.context, policy.policy.upgrade['4.1'], selectors[3], select),
        [process.execPath, '/fixture/testHelpers/server/runOwnedTests.js', 'upgrade', '/fixture', 'HEADER', '4.1', '2',
            JSON.stringify(normalize(policy.policy.upgrade['4.1'].phaseVersions['HEADER:/login-2.18'].publishedHandoff)), '5', '/login-2.18']);
    assert.equal(preflight(path.resolve(__dirname, '..'), f.context, policy.policy.upgrade['4.1']).length, 4);
    assert.equal(new Set(selectors.map(selectionDirectory)).size, 4);
});
test('missing approved owned upgrade integration cannot dispatch', () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'missing-owned-upgrade-'));
    try { assert.throws(() => preflight(directory, fixture().context, policy.policy.upgrade['4.1']), /integration is pending/); }
    finally { fs.rmSync(directory, { recursive: true }); }
});
test('per-flow policy rejects scalars, missing/extra keys and SIGN_OUT swaps before dispatch', () => {
    const f = fixture();
    for (const handoff of ['5', { LOGIN: '2' }, { ...normalize('5', true), EXTRA: '5' },
        { LOGIN: '2', REFRESH: 'ABSENT', SIGN_OUT: '5', PAYLOAD_UPDATE: '5' }]) {
        const plan = JSON.parse(JSON.stringify(policy.policy.upgrade['4.1']));
        plan.phaseVersions['HEADER:/login-2.18'].publishedHandoff = handoff;
        assert.throws(() => preflight(path.resolve(__dirname, '..'), f.context, plan), /unresolved/);
        assert.throws(() => validateEvidence(f.evidence, f.context, policy.policy.required['4.1'], plan), /unresolved/);
    }
});
test('cleanup proof rejects missing, incomplete, reordered and retained lifecycle', () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'owned-cleanup-proof-'));
    try {
        const runId = 'd'.repeat(48);
        fs.writeFileSync(path.join(directory, 'owner.json'), JSON.stringify({ root: directory, runId }));
        assert.throws(() => cleanupProof(directory, runId));
        for (const actions of [['BEFORE_CORE_CLEANUP', 'BACKEND_STOPPED'], ['BACKEND_STOPPED', 'CORE_CLEANUP_ACKNOWLEDGED', 'BEFORE_CORE_CLEANUP']]) {
            fs.writeFileSync(path.join(directory, 'process-tree.jsonl'), actions.map(action => JSON.stringify({ action })).join('\n'));
            assert.throws(() => cleanupProof(directory, runId));
        }
        fs.writeFileSync(path.join(directory, 'RETAINED.json'), '{}');
        assert.throws(() => cleanupProof(directory, runId), /retained/);
    } finally { fs.rmSync(directory, { recursive: true }); }
});

for (const excluded of [true, false]) test('real evidence producer preserves ' + (excluded ? 'exclusions' : 'executed reports'), () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'matrix-evidence-'));
    try {
        const f = fixture();
        const owned = path.join(directory, 'owned'), evidence = path.join(owned, 'matrix');
        const receiptRoot = path.join(directory, 'testHelpers/testapp/app/build/integration-receipts');
        function json(file, value) { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, JSON.stringify(value)); }
        fs.mkdirSync(evidence, { recursive: true });
        json(path.join(directory, '.circleci/integration-policy.json'), policy.policy);
        // Preserve policy bytes: the configuration digest intentionally binds exact bytes.
        fs.writeFileSync(path.join(directory, '.circleci/integration-policy.json'), policyText);
        json(path.join(directory, 'frontendDriverInterfaceSupported.json'), { versions: Object.keys(policy.policy.required) });
        json(path.join(directory, 'context.json'), f.context);
        json(path.join(owned, 'owner.json'), { runId: f.evidence.identity.runId, root: owned });
        fs.writeFileSync(path.join(owned, 'dependencies.sha256'), f.evidence.identity.dependenciesSha256);
        if (excluded) {
            f.evidence.capabilities.mappedGoogleSession = false;
            for (const lane of f.evidence.lanes.filter(lane => r7.includes(lane.name))) { lane.status = 'excluded'; lane.reason = 'Unsupported pair'; }
        }
        json(path.join(owned, 'qualified-instance.json'), { identity: { testRuntime: 'fake-owned-core' }, capabilities: f.evidence.capabilities });
        const exposure = { runId: f.evidence.identity.runId, instance: f.evidence.identity.instance,
            identity: { testRuntime: 'fake-owned-core' }, profile: 'exposed',
            selected: { mode: 'EXPOSED_COOKIE', exposed: true }, flags: { sessionJwt: true },
            capabilities: { compatible: true, qualificationProxy: false } };
        json(path.join(owned, 'exposure-config.json'), exposure);
        const exposedDir = path.join(receiptRoot, 'fresh-exposed');
        json(path.join(exposedDir, 'preparation.json'), { nonce: 'fresh-exposed', mode: 'exposed', sources: f.context.sources,
            runId: exposure.runId, instance: exposure.instance, exposureConfigSha256: hash(fs.readFileSync(path.join(owned, 'exposure-config.json'))) });
        json(path.join(exposedDir, 'completed.json'), { nonce: 'fresh-exposed' });
        json(path.join(exposedDir, 'reports/testDebugUnitTest.json'), f.evidence.lanes.find(lane => lane.name === 'ordinary').exposedReport);
        for (const lane of f.evidence.lanes) {
            if (lane.status !== 'passed' || ['node-core', 'published-upgrade'].includes(lane.name)) continue;
            if (['ordinary', 'retrofit', 'mutation'].includes(lane.name)) {
                const nonce = 'fresh-' + lane.name;
                const here = path.join(receiptRoot, nonce);
                json(path.join(here, 'preparation.json'), { nonce, mode: lane.name, sources: f.context.sources });
                json(path.join(here, 'completed.json'), { nonce });
                json(path.join(here, 'reports/testDebugUnitTest.json'), lane.reports[0]);
            } else json(path.join(evidence, lane.name === 'proxy-qualification' ? 'proxy-tests' : lane.name, 'testDebugUnitTest/result.json'), lane.reports[0]);
            if (lane.name === 'proxy-qualification') json(path.join(evidence, 'proxy-final-state.json'), lane.finalState);
        }
        fs.writeFileSync(path.join(directory, 'testHelpers/testapp/artifactReceipt.js'), 'exports.sourceDigest = () => "b".repeat(64);');
        fs.mkdirSync(path.join(directory, 'app/build/outputs/aar'), { recursive: true });
        fs.writeFileSync(path.join(directory, 'app/build/outputs/aar/app-debug.aar'), 'synthetic baseline AAR bytes');
        // Only Git revision discovery is mocked; the producer reads real temporary report files.
        const moduleObject = { exports: {} };
        const load = name => name === 'child_process' ? { execFileSync: () => f.context.sha } : require(name);
        new Function('require', 'module', fs.readFileSync(path.join(__dirname, 'integrationEvidence.js'), 'utf8'))(load, moduleObject);
        const produce = () => moduleObject.exports.produce({ root: directory, evidence, contextFile: path.join(directory, 'context.json'), startedReceipts: [],
            capabilities: f.evidence.capabilities, statuses: f.evidence.lanes,
            env: { INSTALL_PATH: owned, ST_TEST_RUN_ID: f.evidence.identity.runId, ST_TEST_INSTANCE_ID: f.evidence.identity.instance,
                ST_TEST_BACKEND_URL: f.context.backend, CORE_CONNECTION_URI: f.context.core } });
        const preparationFile = path.join(exposedDir, 'preparation.json');
        const preparation = JSON.parse(fs.readFileSync(preparationFile));
        for (const change of [{ instance: '0'.repeat(48) }, { runId: '0'.repeat(48) },
            { exposureConfigSha256: '0'.repeat(64) }, { sources: '0'.repeat(64) }]) {
            json(preparationFile, { ...preparation, ...change });
            assert.throws(produce);
        }
        json(preparationFile, preparation);
        produce();
        const actual = JSON.parse(fs.readFileSync(path.join(evidence, 'release-evidence.json')));
        assert.equal(actual.candidateAarSha256, hash('synthetic baseline AAR bytes'));
        assert.throws(() => writeReceipt(f.context, actual, policy, path.join(directory, 'receipts')), /cleanup/);
        fs.writeFileSync(path.join(owned, 'process-tree.jsonl'), ['BEFORE_CORE_CLEANUP', 'CORE_CLEANUP_ACKNOWLEDGED', 'BACKEND_STOPPED']
            .map(action => JSON.stringify({ action })).join('\n'));
        fs.writeFileSync(path.join(owned, 'lifecycle-completed.json'), JSON.stringify({ schema: 1, root: owned,
            runId: actual.identity.runId, instance: actual.identity.instance, command: ['fake-matrix'], exitCode: 0,
            dependenciesSha256: actual.identity.dependenciesSha256,
            lifecycleSha256: hash(fs.readFileSync(path.join(owned, 'process-tree.jsonl'))) }));
        actual.cleanup = cleanupProof(owned, actual.identity.runId);
        actual.lanes[8] = f.evidence.lanes[8]; // Synthetic completed adapter output, not produced by the current blocked adapter.
        const write = () => writeReceipt(f.context, actual, policy, path.join(directory, 'receipts'));
        if (excluded) assert.throws(write, /excluded/);
        else assert.doesNotThrow(write);
    } finally { fs.rmSync(directory, { recursive: true }); }
});

for (const scenario of ['success', 'r7-excluded', 'missing-baseline', 'excluded-baseline', 'missing-upgrade', 'excluded-upgrade', 'stale-upgrade', 'cleanup-failed', 'upgrade-cleanup-failed', 'known-COOKIE', 'known-EXPOSED_COOKIE', 'disabled-composite']) test(`composition: zero-exit matrix ${scenario} -> publisher`, () => {
    const excluded = scenario !== 'success';
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'composed-release-'));
    try {
        const scripts = path.join(directory, '.circleci'), bin = path.join(directory, 'bin'), receipts = path.join(directory, 'receipts');
        fs.mkdirSync(scripts); fs.mkdirSync(bin); fs.mkdirSync(receipts); fs.mkdirSync(path.join(directory, 'app'));
        fs.mkdirSync(path.join(directory, 'testHelpers/testapp'), { recursive: true });
        fs.copyFileSync(path.join(__dirname, '../testHelpers/testapp/upgradeHandoff.js'), path.join(directory, 'testHelpers/testapp/upgradeHandoff.js'));
        fs.copyFileSync(path.join(__dirname, '../testHelpers/testapp/exposureAcceptance.js'), path.join(directory, 'testHelpers/testapp/exposureAcceptance.js'));
        for (const name of ['markAsSuccess.sh', 'integrationReceipt.js', 'integrationEvidence.js', 'integration-policy.json', 'requiredCompatibility.js', 'upgradeEvidence.js', 'nativeUpgradeEvidence.js']) {
            fs.copyFileSync(path.join(__dirname, name), path.join(scripts, name));
        }
        const versions = Object.keys(policy.policy.required);
        fs.writeFileSync(path.join(scripts, 'integration-policy.json'), policyText);
        fs.writeFileSync(path.join(directory, 'frontendDriverInterfaceSupported.json'), JSON.stringify({ versions }));
        fs.writeFileSync(path.join(directory, 'app/build.gradle'), 'publishVersionID = "0.6.0"\n');
        for (const fdi of versions) {
            const f = fixture(fdi);
            if (excluded && fdi === '4.1') {
                if (scenario === 'r7-excluded') {
                    f.evidence.capabilities.mappedGoogleSession = false;
                    for (const lane of f.evidence.lanes.filter(lane => r7.includes(lane.name))) { lane.status = 'excluded'; lane.reason = 'Unsupported pair'; }
                } else if (scenario === 'missing-baseline') f.evidence.lanes.splice(7, 1);
                else if (scenario === 'excluded-baseline') f.evidence.lanes[7].status = 'excluded';
                else if (scenario === 'missing-upgrade') delete f.evidence.lanes[8].upgrade;
                else if (scenario === 'excluded-upgrade') f.evidence.lanes[8].status = 'excluded';
                else if (scenario === 'stale-upgrade') f.evidence.lanes[8].upgrade.attempt = 41;
                else if (scenario === 'cleanup-failed') f.evidence.cleanup.backendStopped = false;
                else if (scenario === 'upgrade-cleanup-failed') f.evidence.lanes[8].upgrade.selections[0].ownedCleanup = false;
                else if (scenario === 'disabled-composite') f.evidence.lanes[8].upgrade = { schema: 2 };
                else if (scenario.startsWith('known-')) {
                    const mode = scenario.slice('known-'.length);
                    f.evidence.lanes[8].upgrade.selections.find(value => value.mode === mode).report = knownCookieFailures[mode].report;
                }
            }
            // Model the problematic zero-exit producer, then invoke the real receipt validator/writer.
            const evidenceFile = path.join(directory, 'matrix.json');
            const result = spawnSync(process.execPath, ['-e', 'require("fs").writeFileSync(process.argv[1],process.argv[2])', evidenceFile, JSON.stringify(f.evidence)]);
            assert.equal(result.status, 0);
            if (excluded && fdi === '4.1') {
                assert.throws(() => writeReceipt(f.context, JSON.parse(fs.readFileSync(evidenceFile)), policy, receipts),
                    scenario === 'disabled-composite' ? /Composite acceptance is not enabled/ : undefined);
                assert.equal(fs.existsSync(path.join(receipts, '4.1.json')), false);
                if (scenario === 'disabled-composite') fs.writeFileSync(path.join(receipts, '4.1.json'), JSON.stringify(receipt(f)));
            } else writeReceipt(f.context, JSON.parse(fs.readFileSync(evidenceFile)), policy, receipts);
        }
        fs.writeFileSync(path.join(bin, 'git'), '#!/bin/bash\nprintf "%s\\n" "$CIRCLE_SHA1"\n', { mode: 0o700 });
        fs.writeFileSync(path.join(bin, 'jq'), '#!/bin/bash\ncat >/dev/null\necho 0\n', { mode: 0o700 });
        fs.writeFileSync(path.join(bin, 'curl'), '#!/bin/bash\nprintf "%s\\n" "$*" >> "$PUBLISH_MARKER"\nprintf 200\n', { mode: 0o700 });
        // Redirect only the fixed workspace mount in this isolated test process.
        // GitHub verification is a separate mocked boundary; its tests exercise actual schema/ref/SHA checks.
        fs.writeFileSync(path.join(bin, 'node'), '#!/bin/bash\nif [ "$1" = integrationReceipt.js ]; then exec "$REAL_NODE" "$1" verify "$TEST_RECEIPTS"; fi\nif [ "$1" = requiredCompatibility.js ]; then exit 0; fi\nexit 99\n', { mode: 0o700 });
        const marker = path.join(directory, 'patch-called');
        const result = spawnSync('bash', [path.join(scripts, 'markAsSuccess.sh')], { cwd: directory, timeout: 5000,
            env: { ...process.env, PATH: bin + ':' + process.env.PATH, REAL_NODE: process.execPath, TEST_RECEIPTS: receipts,
                CIRCLE_SHA1: 'a'.repeat(40), CIRCLE_WORKFLOW_ID: 'current-workflow', SUPERTOKENS_API_KEY: 'fake', PUBLISH_MARKER: marker } });
        assert.equal(result.status, excluded ? 1 : 0, result.stderr.toString());
        assert.equal(fs.existsSync(marker), !excluded);
        if (!excluded) assert.match(fs.readFileSync(marker, 'utf8'), /-X PATCH/);
    } finally { fs.rmSync(directory, { recursive: true }); }
});
