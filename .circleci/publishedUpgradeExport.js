const fs = require('fs');
const path = require('path');
const { accept } = require('../testHelpers/testapp/exposureAcceptance');
const { cleanupProof, hash, contextValid } = require('./integrationEvidence');
const { selectors, flows, key, validatePlan } = require('./upgradeEvidence');
const { normalize } = require('../testHelpers/testapp/upgradeHandoff');

function requireTrue(value, message) { if (!value) throw new Error(message); }
const read = file => JSON.parse(fs.readFileSync(file));
function phase(value) {
    requireTrue(value && ((value.status === 'PRESENT' && typeof value.tokenVersion === 'string' && /^[2-5]$/.test(value.tokenVersion)) ||
        (value.status === 'ABSENT' && value.tokenVersion === null)), 'Missing actual token phase observation');
    return value.status === 'ABSENT' ? 'ABSENT' : value.tokenVersion;
}

function collectSelection(root, owned, context, plan, selector) {
    contextValid(context);
    validatePlan(plan);
    requireTrue(selectors.some(value => key(value) === key(selector) && value.enableJWT === selector.enableJWT), 'Invalid upgrade selector');
    const pointer = read(path.join(owned, 'upgrade-completed.json'));
    requireTrue(pointer.schema === 1 && /^[a-f0-9]{48}$/.test(pointer.nonce || '') &&
        pointer.mode === selector.mode && pointer.route === selector.route && pointer.sources === context.sources && pointer.backend === context.backend, 'Stale/mismatched upgrade completion');
    const directory = path.join(root, 'testHelpers/testapp/app/build/integration-receipts', pointer.nonce);
    requireTrue(pointer.directory === directory && fs.realpathSync(directory) === directory, 'Upgrade report outside its fresh receipt directory');
    const report = read(path.join(directory, 'reports/upgradeLiveTest.json'));
    // The four known published Java11 failures stop here, even if all other files exist.
    accept('upgrade', selector.mode, report, selector.route);
    requireTrue(report.rerun === true && Array.isArray(report.excludedTasks) && report.excludedTasks.length === 0 &&
        Array.isArray(report.excludes) && report.excludes.length === 0, 'Upgrade tasks were not freshly executed');
    const preparation = read(path.join(directory, 'preparation.json'));
    const completed = read(path.join(directory, 'completed.json'));
    requireTrue(preparation.nonce === pointer.nonce && completed.nonce === pointer.nonce &&
        preparation.mode === 'upgrade' && preparation.sources === context.sources && preparation.root === root &&
        Array.isArray(preparation.commands) && preparation.commands.every(command => command.includes('--rerun-tasks') && command.includes('--no-build-cache')),
        'Upgrade preparation/completion mismatch');
    const configured = read(path.join(owned, 'exposure-config.json'));
    requireTrue(configured.runId === pointer.runId && configured.instance === pointer.instance && configured.profile === 'upgrade' &&
        configured.selected.mode === selector.mode && configured.selected.route === selector.route && configured.selected.exposed === selector.enableJWT &&
        configured.flags.sessionJwt === selector.enableJWT && configured.capabilities.qualificationProxy === false &&
        configured.capabilities.compatible === true && configured.capabilities.coreReachable === true &&
        configured.capabilities.nodeVersion === plan.nodeVersion && configured.identity.nodeSdkVersion === plan.nodeVersion &&
        configured.identity.coreVersion === plan.coreVersion, 'Upgrade actual SDK/Core/configuration mismatch');
    const handoffs = normalize(plan.phaseVersions[key(selector)].publishedHandoff);
    const scalarProperty = '-Pupgrade.expectedPublishedHandoffVersion=' + handoffs.LOGIN;
    const legacyScalar = selector.route === '/login' && configured.selected.properties.includes(scalarProperty) &&
        flows.every(flow => flow === 'SIGN_OUT' || handoffs[flow] === handoffs.LOGIN);
    const properties = ['-Pupgrade.baseUrl=' + context.backend, '-Pupgrade.fdiVersion=' + context.fdi,
        '-Pupgrade.expectedPublishedLoginVersion=' + plan.phaseVersions[key(selector)].publishedLogin,
        legacyScalar ? scalarProperty : '-Pupgrade.expectedPublishedHandoffVersions=' + JSON.stringify(handoffs),
        '-Pupgrade.expectedCandidateRefreshVersion=' + plan.phaseVersions[key(selector)].candidateRefresh,
        ...(selector.route !== '/login' ? ['-Pupgrade.loginRoute=' + selector.route] : [])];
    requireTrue(JSON.stringify(configured.selected.properties) === JSON.stringify(properties) &&
        properties.every(property => preparation.commands[1].includes(property)), 'Upgrade selectors/version configuration changed');
    const observations = path.join(directory, 'reports/upgrade-observations');
    requireTrue(JSON.stringify(fs.readdirSync(observations).sort()) === JSON.stringify(flows.map(flow => flow + '.json').sort()), 'Missing/extra phase observations');
    const rows = flows.map(flow => {
        const row = read(path.join(observations, flow + '.json'));
        requireTrue(row.schema === 1 && row.flow === flow && row.mode === selector.mode && row.route === selector.route &&
            row.fdi === context.fdi && row.backend === context.backend && row.nodeVersion === plan.nodeVersion &&
            row.runId === pointer.runId && row.preparationNonce === pointer.nonce && row.sessionJwt === selector.enableJWT &&
            JSON.stringify(row.coreCdiVersions) === JSON.stringify(configured.capabilities.coreCdiVersions) &&
            row.publishedFirst === true && row.candidateContinued === true && row.scopedCleanup === true && row.observed.schemaVersion === 2,
            'Stale/wrong or cleanup-incomplete phase observations');
        const actual = { flow, publishedFirst: row.publishedFirst, candidateContinued: row.candidateContinued, scopedCleanup: row.scopedCleanup,
            publishedLogin: phase(row.observed.publishedLogin), publishedHandoff: phase(row.observed.publishedHandoff),
            candidateRefresh: phase(row.observed.candidateRefresh) };
        const expected = plan.phaseVersions[key(selector)];
        requireTrue(actual.publishedLogin === expected.publishedLogin && actual.publishedHandoff === expected.publishedHandoff[flow] &&
            actual.candidateRefresh === expected.candidateRefresh, 'Observed upgrade phases do not match the reviewed plan');
        return actual;
    });
    const cleanup = cleanupProof(owned, pointer.runId, pointer.instance);
    let legacy218Evidence;
    if (selector.route === '/login-2.18') {
        requireTrue(Array.isArray(configured.capabilities.coreCdiVersions) && configured.capabilities.coreCdiVersions.includes('2.18'),
            'Historical route requires advertised CDI 2.18');
        legacy218Evidence = { status: 'passed', route: selector.route, nodeVersion: plan.nodeVersion,
            fdiVersion: context.fdi, coreCdiVersions: configured.capabilities.coreCdiVersions,
            runId: pointer.runId, preparationNonce: pointer.nonce, sources: context.sources,
            qualification: 'published-first-four-flows' };
    }
    // extractLegacy054 verified this pin during the required fresh preparation task.
    const publishedAarSha256 = fs.readFileSync(path.join(root, 'app/src/test/compatibility/legacy054.sha256'), 'utf8').trim();
    const candidateAarSha256 = preparation.artifactHashes['app/build/outputs/aar/app-debug.aar'];
    return { ...selector, sessionJwt: configured.flags.sessionJwt, backend: context.backend, core: context.core,
        nodeVersion: configured.identity.nodeSdkVersion, coreVersion: configured.identity.coreVersion,
        coreSha256: configured.identity.coreSha256, nodeSdkSha256: configured.identity.sdkImplementationSha256,
        qualifiedIdentitySha256: hash(JSON.stringify(configured.identity)), coreCdiVersions: configured.capabilities.coreCdiVersions,
        runId: pointer.runId, preparationNonce: pointer.nonce, freshPreparation: true, completed: true,
        scopedCleanup: rows.every(row => row.scopedCleanup), ownedCleanup: cleanup.backendStopped,
        cleanupSha256: hash(fs.readFileSync(path.join(owned, 'lifecycle-completed.json'))),
        preparation: { nonce: preparation.nonce, sources: preparation.sources, sha: context.sha, candidateAarSha256, publishedAarSha256 },
        ...(legacy218Evidence ? { legacy218Evidence } : {}), report, rows };
}
module.exports = { collectSelection, phase };
