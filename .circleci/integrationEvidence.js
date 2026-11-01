const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execFileSync } = require('child_process');
const { validateUpgrade } = require('./upgradeEvidence');

const lanes = ['root-build', 'ordinary', 'retrofit', 'node-core', 'core-classes', 'mutation', 'proxy-qualification', 'core-baseline', 'published-upgrade'];
const baseline = ['root-build', 'ordinary', 'retrofit', 'core-baseline', 'published-upgrade'];
const exactCases = {
    'core-baseline': ['urlConnectionHeaderLoginRefreshProtectedAndSignout', 'urlConnectionCookieLoginRefreshProtectedAndSignout',
        'okHttpHeaderLoginProtectedAndSignout', 'okHttpCookieLoginProtectedAndSignout'].map(name => 'com.supertokens.session.CoreSdkCompatibilityTest.' + name),
    'core-classes': ['com.supertokens.session.CoreSessionProtocolTest.candidateKeepsUsableCoreLineagePromotionDeliveredFirst',
        'com.supertokens.session.CoreSessionProtocolTest.candidateKeepsUsableCoreLineageRefreshDeliveredFirst',
        'com.supertokens.session.CoreLostRefreshResponseTest.discardedRotationCanBeRecoveredWithPersistedParentBeforeChildUse'],
    mutation: ['payloadUpdateRetainsOwnerAndEmitsOnePayloadEvent', 'handlePayloadUpdateBecomesVisibleAfterRefresh',
        'logoutThenRejectedAccessRefreshClearsOnce', 'revokeAllThenRejectedAccessRefreshClearsOnce', 'claims403NeitherRefreshesNorClears',
        'nonPublicTenantLoginAndRefreshRetainTenantAndOwner'].map(name => 'com.example.example.BackendMutationCoverageTest.' + name),
    'proxy-qualification': ['refreshDeliveredBeforePromotion', 'promotionDeliveredBeforeRefresh'].map(name => 'com.supertokens.session.CoreProxyProtocolTest.' + name),
};
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const sha256 = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const positive = value => Number.isSafeInteger(value) && value > 0;
function requireTrue(value, message) { if (!value) throw new Error(message); }
function config(root) {
    const text = fs.readFileSync(path.join(root, '.circleci/integration-policy.json'));
    const policy = JSON.parse(text);
    const versions = JSON.parse(fs.readFileSync(path.join(root, 'frontendDriverInterfaceSupported.json'))).versions;
    requireTrue(Array.isArray(versions) && versions.length > 0 && new Set(versions).size === versions.length, 'Empty/invalid FDI matrix');
    requireTrue(policy.schema === 1 && JSON.stringify(Object.keys(policy.required).sort()) === JSON.stringify([...versions].sort()), 'Policy/FDI matrix mismatch');
    for (const names of Object.values(policy.required)) {
        requireTrue(Array.isArray(names) && names.length > 0 && new Set(names).size === names.length &&
            names.every(name => lanes.includes(name)) && baseline.every(name => names.includes(name)), 'Invalid required lanes');
    }
    requireTrue(policy.upgrade && JSON.stringify(Object.keys(policy.upgrade).sort()) === JSON.stringify([...versions].sort()), 'Upgrade/FDI policy mismatch');
    requireTrue(Object.values(policy.required).some(names => lanes.every(name => names.includes(name))), 'No mandatory full-capability R7 lane');
    return { policy, versions, policySha256: hash(text) };
}
function contextValid(context) {
    requireTrue(context && context.schema === 1 && /^[a-f0-9]{40}$/.test(context.sha || '') &&
        typeof context.workflow === 'string' && context.workflow.length > 0 && positive(context.attempt) &&
        /^[0-9]+(?:\.[0-9]+)*$/.test(context.fdi || '') && sha256(context.policySha256) &&
        sha256(context.sources) && /^[a-f0-9]{48}$/.test(context.nonce || '') &&
        context.backend === 'http://127.0.0.1:18081' && context.core === 'http://127.0.0.1:19001', 'Malformed integration context');
}
function validateReport(report, identities) {
    requireTrue(report && positive(report.tests) && report.passed === report.tests && report.failed === 0 && report.skipped === 0 &&
        Array.isArray(report.cases) && report.cases.length === report.tests && report.cases.every(item => item.result === 'SUCCESS') &&
        (!identities || (report.tests === identities.length &&
            JSON.stringify(report.cases.map(row => row.className + '.' + row.name).sort()) === JSON.stringify([...identities].sort()))), 'Unexecuted/failed/skipped or wrong test identities');
    for (const key of ['excluded', 'excludedTasks', 'excludes']) {
        requireTrue(report[key] === undefined || (Array.isArray(report[key]) && report[key].length === 0), 'Excluded test evidence');
    }
}
function validateEvidence(evidence, expected, required, upgradePlan) {
    contextValid(expected);
    requireTrue(evidence && evidence.schema === 1 && evidence.context &&
        Object.keys(expected).every(key => evidence.context[key] === expected[key]) &&
        Object.keys(evidence.context).length === Object.keys(expected).length, 'Evidence context/source/attempt/config mismatch');
    const identity = evidence.identity;
    requireTrue(identity && /^[a-f0-9]{48}$/.test(identity.runId || '') && /^[a-f0-9]{48}$/.test(identity.instance || '') &&
        sha256(identity.qualifiedIdentitySha256) && sha256(identity.dependenciesSha256) &&
        identity.backend === expected.backend && identity.core === expected.core, 'Missing/mismatched owned fixture identity');
    requireTrue(Array.isArray(required) && required.length > 0 && required.every(name => lanes.includes(name)), 'Missing required lane policy');
    requireTrue(baseline.every(name => required.includes(name)), 'Mandatory baseline/upgrade policy weakened');
    requireTrue(evidence.cleanup && evidence.cleanup.commandSucceeded === true && evidence.cleanup.coreAcknowledged === true &&
        evidence.cleanup.backendStopped === true && evidence.cleanup.dependenciesUnchanged === true &&
        evidence.cleanup.runId === identity.runId && sha256(evidence.cleanup.lifecycleSha256), 'Owned cleanup completion missing/failed');
    requireTrue(Array.isArray(evidence.lanes) && evidence.lanes.length === lanes.length, 'Missing per-lane evidence');
    const capabilities = evidence.capabilities;
    requireTrue(capabilities && capabilities.enabled === true && capabilities.compatible === true && capabilities.qualificationProxy === false, 'Unqualified fixture');
    for (const name of lanes) {
        const matches = evidence.lanes.filter(lane => lane.name === name);
        requireTrue(matches.length === 1, 'Missing/duplicate lane ' + name);
        const lane = matches[0];
        const available = baseline.includes(name) ||
            (capabilities.mappedGoogleSession === true && (name !== 'mutation' || capabilities.nonPublicTenantSession === true));
        if (lane.status === 'excluded') {
            requireTrue(!required.includes(name) && !available && typeof lane.reason === 'string' && lane.reason.length > 0,
                'Mandatory or available lane excluded: ' + name);
            continue;
        }
        requireTrue(available && lane.status === 'passed' && lane.execution && lane.execution.exit === 0 &&
            Array.isArray(lane.execution.argv) && lane.execution.argv.length > 0 && lane.execution.argv.every(arg => typeof arg === 'string') &&
            sha256(lane.execution.sourceSha256), 'Missing actual execution: ' + name);
        if (name === 'published-upgrade') {
            if (lane.upgrade && lane.upgrade.schema === 2) {
                const root = path.resolve(__dirname, '..');
                requireTrue(config(root).policy.upgradeAcceptance === require('./nativeUpgradeEvidence').acceptance,
                    'Composite acceptance is not enabled in hashed policy');
                require('./nativeUpgradeEvidence').validateComposite(lane.upgrade, expected, upgradePlan,
                    require('./publishedNativeUpgradeExport').validationOptions(root, expected, upgradePlan, evidence.candidateAarSha256));
            } else validateUpgrade(lane.upgrade, expected, upgradePlan);
        } else if (name === 'node-core') {
            requireTrue(lane.execution.argv.join(' ') === 'npm run test:core', 'Wrong Node protocol invocation');
        } else {
            requireTrue(Array.isArray(lane.reports) && lane.reports.length > 0, 'Missing actual test report: ' + name);
            if (exactCases[name]) requireTrue(lane.reports.length === 1, 'Duplicate exact-suite reports');
            for (const report of lane.reports) validateReport(report, exactCases[name]);
        }
        if (name === 'proxy-qualification') {
            const final = lane.finalState;
            requireTrue(final && final.state && final.owners && Array.isArray(final.owners.fixtures) && final.owners.fixtures.length === 0 &&
                final.owners.unresolvedCreations === 0 && ['active', 'upstreamActive', 'ownedFixtures', 'ownerReservations', 'unresolvedCreations'].every(key => final.state[key] === 0) &&
                final.state.gates && Object.values(final.state.gates).every(gate => gate.released === true), 'Proxy cleanup not proven');
        }
    }
    return evidence;
}

// Called only after the real matrix commands have finished. It preserves exclusions;
// the separate receipt writer applies the release policy after owned cleanup succeeds.
function produce({ root, env, capabilities, statuses, evidence, contextFile, startedReceipts }) {
    if (!contextFile) return;
    const context = JSON.parse(fs.readFileSync(contextFile));
    contextValid(context);
    const { sourceDigest } = require(path.join(root, 'testHelpers/testapp/artifactReceipt'));
    requireTrue(context.sha === execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim() &&
        context.sources === sourceDigest(root) && context.policySha256 === config(root).policySha256, 'Matrix source/config changed');
    const owner = JSON.parse(fs.readFileSync(path.join(env.INSTALL_PATH, 'owner.json')));
    requireTrue(owner.runId === env.ST_TEST_RUN_ID && owner.root === env.INSTALL_PATH, 'Owner identity mismatch');
    const qualified = JSON.parse(fs.readFileSync(path.join(env.INSTALL_PATH, 'qualified-instance.json')));
    requireTrue(JSON.stringify(qualified.capabilities) === JSON.stringify(capabilities), 'Capabilities changed');
    const receiptRoot = path.join(root, 'testHelpers/testapp/app/build/integration-receipts');
    const fresh = fs.readdirSync(receiptRoot).filter(name => !startedReceipts.includes(name)).map(name => {
        const directory = path.join(receiptRoot, name);
        const prepared = JSON.parse(fs.readFileSync(path.join(directory, 'preparation.json')));
        const completed = JSON.parse(fs.readFileSync(path.join(directory, 'completed.json')));
        requireTrue(prepared.nonce === name && completed.nonce === name && prepared.sources === context.sources, 'Stale/incomplete testapp report');
        return { mode: prepared.mode, report: JSON.parse(fs.readFileSync(path.join(directory, 'reports/testDebugUnitTest.json'))) };
    });
    function reports(directory) {
        return fs.readdirSync(directory).filter(name => fs.existsSync(path.join(directory, name, 'result.json')))
            .map(name => JSON.parse(fs.readFileSync(path.join(directory, name, 'result.json'))));
    }
    const results = lanes.map(name => {
        // Separate fresh owned upgrade invocations are attached by the adapter only
        // after all selected reports and cleanup receipts have succeeded.
        if (name === 'published-upgrade') return { name, status: 'missing', reason: 'FDI published-upgrade evidence not yet completed' };
        const terminal = statuses.filter(item => item.name === name && item.status !== 'running');
        requireTrue(terminal.length === 1, 'Missing/duplicate matrix terminal evidence: ' + name);
        const lane = { ...terminal[0] };
        if (lane.status !== 'passed') return lane;
        if (['ordinary', 'retrofit', 'mutation'].includes(name)) {
            const matches = fresh.filter(item => item.mode === name);
            requireTrue(matches.length === 1, 'Missing/duplicate fresh testapp execution');
            lane.reports = [matches[0].report];
        } else if (name !== 'node-core') lane.reports = reports(path.join(evidence, name === 'proxy-qualification' ? 'proxy-tests' : name));
        if (name === 'proxy-qualification') lane.finalState = JSON.parse(fs.readFileSync(path.join(evidence, 'proxy-final-state.json')));
        return lane;
    });
    const result = { schema: 1, context, capabilities,
        candidateAarSha256: hash(fs.readFileSync(path.join(root, 'app/build/outputs/aar/app-debug.aar'))),
        identity: { runId: env.ST_TEST_RUN_ID, instance: env.ST_TEST_INSTANCE_ID,
        backend: env.ST_TEST_BACKEND_URL, core: env.CORE_CONNECTION_URI,
        qualifiedIdentitySha256: hash(JSON.stringify(qualified.identity)),
        dependenciesSha256: fs.readFileSync(path.join(env.INSTALL_PATH, 'dependencies.sha256'), 'utf8').trim() }, lanes: results };
    fs.writeFileSync(path.join(evidence, 'release-evidence.json'), JSON.stringify(result, null, 2), { flag: 'wx' });
}

function cleanupProof(owned, runId, instance) {
    requireTrue(!fs.existsSync(path.join(owned, 'RETAINED.json')), 'Owned cleanup retained/failed');
    const owner = JSON.parse(fs.readFileSync(path.join(owned, 'owner.json')));
    requireTrue(owner.root === owned && owner.runId === runId, 'Cleanup owner mismatch');
    const log = fs.readFileSync(path.join(owned, 'process-tree.jsonl'));
    const completion = JSON.parse(fs.readFileSync(path.join(owned, 'lifecycle-completed.json')));
    requireTrue(completion.schema === 1 && completion.root === owned && completion.runId === runId &&
        /^[a-f0-9]{48}$/.test(completion.instance || '') && (!instance || completion.instance === instance) &&
        Array.isArray(completion.command) && completion.command.length > 0 &&
        completion.exitCode === 0 && completion.lifecycleSha256 === hash(log) &&
        completion.dependenciesSha256 === fs.readFileSync(path.join(owned, 'dependencies.sha256'), 'utf8').trim(),
        'Owned command/cleanup completion receipt mismatch');
    const actions = log.toString().trim().split('\n').map(line => JSON.parse(line).action);
    let previous = -1;
    for (const action of ['BEFORE_CORE_CLEANUP', 'CORE_CLEANUP_ACKNOWLEDGED', 'BACKEND_STOPPED']) {
        requireTrue(actions.filter(value => value === action).length === 1 && actions.indexOf(action) > previous, 'Incomplete/out-of-order owned cleanup');
        previous = actions.indexOf(action);
    }
    return { runId, commandSucceeded: true, coreAcknowledged: true, backendStopped: true,
        dependenciesUnchanged: true, lifecycleSha256: hash(log) };
}
module.exports = { config, contextValid, validateEvidence, produce, cleanupProof, hash, lanes, exactCases };
