const { normalize } = require('../testHelpers/testapp/upgradeHandoff');
const selectors = [
    { mode: 'HEADER', route: '/login', enableJWT: false },
    { mode: 'COOKIE', route: '/login', enableJWT: false },
    { mode: 'EXPOSED_COOKIE', route: '/login', enableJWT: true },
    { mode: 'HEADER', route: '/login-2.18', enableJWT: false },
];
const flows = ['LOGIN', 'REFRESH', 'SIGN_OUT', 'PAYLOAD_UPDATE'];
const key = selector => selector.mode + ':' + selector.route;
const names = selector => flows.map(flow => 'com.example.example.PublishedUpgradeLiveTest.' +
    'publishedSessionContinuesAgainstConfiguredFdiMember[' + selector.mode + '-' + flow + '-' + selector.route + ']');
const digest = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
function check(value, message) { if (!value) throw new Error(message); }
function validatePlan(plan) {
    check(plan && typeof plan.nodeVersion === 'string' && /^\d+\.\d+\.\d+$/.test(plan.nodeVersion) &&
        typeof plan.coreVersion === 'string' && /^\d+\.\d+\.\d+$/.test(plan.coreVersion) &&
        plan.phaseVersions && selectors.every(selector => {
            const versions = plan.phaseVersions[key(selector)];
            if (!versions || !['publishedLogin', 'candidateRefresh'].every(phase => typeof versions[phase] === 'string' && /^[2-5]$/.test(versions[phase]))) return false;
            try { normalize(versions.publishedHandoff); return true; } catch (_) { return false; }
        }), 'Required FDI upgrade Node/Core/phase expectations are unresolved; release blocked');
    return plan;
}
function validateUpgrade(upgrade, context, plan) {
    validatePlan(plan);
    check(upgrade && upgrade.fdi === context.fdi && upgrade.sha === context.sha && upgrade.attempt === context.attempt &&
        upgrade.nonce === context.nonce && upgrade.policySha256 === context.policySha256 &&
        upgrade.sources === context.sources && upgrade.workflow === context.workflow &&
        upgrade.nodeVersion === plan.nodeVersion && upgrade.coreVersion === plan.coreVersion &&
        digest(upgrade.coreSha256) && digest(upgrade.nodeSdkSha256) &&
        upgrade.publishedVersion === '0.5.4' &&
        upgrade.publishedAarSha256 === '2f3b4a94a036d21ced610f9c41707d7e1ccf7c9c0256e47e5686c1c76b8224b9' &&
        digest(upgrade.candidateAarSha256) && upgrade.candidateAarSha256 !== upgrade.publishedAarSha256 &&
        Array.isArray(upgrade.selections) && upgrade.selections.length === selectors.length, 'Missing/stale published-first upgrade evidence');
    const runs = new Set(), preparations = new Set();
    for (const selector of selectors) {
        const matches = upgrade.selections.filter(value => value.mode === selector.mode && value.route === selector.route);
        check(matches.length === 1, 'Missing/duplicate required upgrade selector');
        const selected = matches[0];
        check(selected.enableJWT === selector.enableJWT && selected.sessionJwt === selector.enableJWT &&
            selected.backend === context.backend && selected.core === context.core &&
            selected.nodeVersion === plan.nodeVersion && selected.coreVersion === plan.coreVersion &&
            selected.coreSha256 === upgrade.coreSha256 && selected.nodeSdkSha256 === upgrade.nodeSdkSha256 && digest(selected.qualifiedIdentitySha256) &&
            /^[a-f0-9]{48}$/.test(selected.runId || '') && /^[a-f0-9]{48}$/.test(selected.preparationNonce || '') &&
            !runs.has(selected.runId) && !preparations.has(selected.preparationNonce) &&
            selected.freshPreparation === true && selected.completed === true && selected.scopedCleanup === true &&
            selected.ownedCleanup === true && digest(selected.cleanupSha256) &&
            selected.preparation && selected.preparation.nonce === selected.preparationNonce &&
            selected.preparation.sources === context.sources && selected.preparation.sha === context.sha &&
            selected.preparation.candidateAarSha256 === upgrade.candidateAarSha256 &&
            selected.preparation.publishedAarSha256 === upgrade.publishedAarSha256 &&
            Array.isArray(selected.rows) && selected.rows.length === flows.length, 'Upgrade environment/freshness/cleanup not proven');
        runs.add(selected.runId); preparations.add(selected.preparationNonce);
        if (selector.route === '/login-2.18') {
            const routeEvidence = selected.legacy218Evidence;
            check(Array.isArray(selected.coreCdiVersions) && selected.coreCdiVersions.includes('2.18') &&
                routeEvidence && routeEvidence.status === 'passed' && routeEvidence.route === selector.route &&
                routeEvidence.nodeVersion === plan.nodeVersion && routeEvidence.fdiVersion === context.fdi &&
                routeEvidence.runId === selected.runId && routeEvidence.preparationNonce === selected.preparationNonce &&
                routeEvidence.sources === context.sources && routeEvidence.qualification === 'published-first-four-flows' &&
                JSON.stringify(routeEvidence.coreCdiVersions) === JSON.stringify(selected.coreCdiVersions), 'Missing/mismatched legacy route qualification');
        }
        check(selected.report && selected.report.tests === 4 && selected.report.passed === 4 && selected.report.failed === 0 && selected.report.skipped === 0 &&
            Array.isArray(selected.report.cases) && selected.report.cases.every(row => row.result === 'SUCCESS') &&
            JSON.stringify(selected.report.cases.map(row => row.className + '.' + row.name).sort()) === JSON.stringify(names(selector).sort()), 'Wrong upgrade test identities');
        for (const field of ['excludedTasks', 'excludes']) check(!selected.report[field] || selected.report[field].length === 0, 'Upgrade excludes tests');
        for (const flow of flows) {
            const rows = selected.rows.filter(row => row.flow === flow);
            check(rows.length === 1, 'Missing/duplicate published upgrade phase observation');
            const row = rows[0], expected = plan.phaseVersions[key(selector)];
            check(row.publishedFirst === true && row.scopedCleanup === true && row.candidateContinued === true &&
                row.publishedLogin === expected.publishedLogin && row.publishedHandoff === expected.publishedHandoff[flow] &&
                row.candidateRefresh === expected.candidateRefresh, 'Published/candidate phase versions, order or cleanup mismatch');
        }
    }
}
module.exports = { selectors, flows, key, names, validatePlan, validateUpgrade };
