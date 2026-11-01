const fs = require('fs');
const path = require('path');
const { spawnSync, execFileSync } = require('child_process');
const { config, contextValid, hash, validateEvidence } = require('./integrationEvidence');
const { selectors, key, validatePlan, validateUpgrade } = require('./upgradeEvidence');
const { collectSelection } = require('./publishedUpgradeExport');
const { normalize } = require('../testHelpers/testapp/upgradeHandoff');

// Keep the five-option primary CLI unchanged; historical selection adds the route.
function primaryCommand(root, context, plan, selector, select) {
    contextValid(context);
    validatePlan(plan);
    if (!selectors.some(value => key(value) === key(selector) && value.enableJWT === selector.enableJWT)) throw new Error('Invalid upgrade selector');
    const versions = plan.phaseVersions[key(selector)];
    const handoffs = JSON.stringify(normalize(versions.publishedHandoff));
    const options = [selector.mode, context.fdi, versions.publishedLogin, handoffs, versions.candidateRefresh];
    if (selector.route !== '/login') options.push(selector.route);
    const actual = select('upgrade', options, context.backend);
    const properties = ['-Pupgrade.baseUrl=' + context.backend, '-Pupgrade.fdiVersion=' + context.fdi,
        '-Pupgrade.expectedPublishedLoginVersion=' + versions.publishedLogin,
        '-Pupgrade.expectedPublishedHandoffVersions=' + handoffs,
        '-Pupgrade.expectedCandidateRefreshVersion=' + versions.candidateRefresh,
        ...(selector.route !== '/login' ? ['-Pupgrade.loginRoute=' + selector.route] : [])];
    if (actual.mode !== selector.mode || actual.route !== selector.route || actual.exposed !== selector.enableJWT || JSON.stringify(actual.properties) !== JSON.stringify(properties)) {
        throw new Error('Owned upgrade selector contract changed');
    }
    return [process.execPath, path.join(root, 'testHelpers/server/runOwnedTests.js'), 'upgrade', root, ...options];
}

function preflight(root, context, plan) {
    validatePlan(plan);
    const moduleFile = path.join(root, 'testHelpers/server/ownedExposure.js');
    if (!fs.existsSync(moduleFile)) throw new Error('Approved exposed-cookie owned upgrade integration is pending; release blocked');
    const { select } = require(moduleFile);
    return selectors.map(selector => primaryCommand(root, context, plan, selector, select));
}

function selectionDirectory(selector) {
    return 'upgrade-' + selector.mode + (selector.route === '/login-2.18' ? '-login-2.18' : '');
}

function main() {
    const root = path.resolve(__dirname, '..');
    const context = JSON.parse(fs.readFileSync(path.join(root, 'owned-integration-evidence/release-context.json')));
    contextValid(context);
    const settings = config(root);
    if (context.policySha256 !== settings.policySha256 || context.sha !== process.env.CIRCLE_SHA1 ||
        context.workflow !== process.env.CIRCLE_WORKFLOW_ID || context.attempt !== Number(process.env.CIRCLE_BUILD_NUM) ||
        context.sha !== execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim()) throw new Error('Stale upgrade source/policy/attempt context');
    const plan = settings.policy.upgrade[context.fdi];
    const commands = preflight(root, context, plan);
    const composite = settings.policy.upgradeAcceptance === require('./nativeUpgradeEvidence').acceptance;
    if (settings.policy.upgradeAcceptance !== undefined && !composite) throw new Error('Unknown upgrade acceptance policy');
    const matrices = fs.readdirSync(path.join(root, 'owned-integration-evidence')).map(name =>
        path.join(root, 'owned-integration-evidence', name, 'matrix/release-evidence.json')).filter(file => fs.existsSync(file));
    if (matrices.length !== 1) throw new Error('Missing/ambiguous baseline matrix evidence');
    const baselineBytes = fs.readFileSync(matrices[0]);
    const evidence = JSON.parse(baselineBytes);
    if (composite) {
        if (!process.env.ST_NATIVE_UPGRADE_CONFIG || !plan.review || !plan.review.reference ||
            !/^[a-f0-9]{64}$/.test(evidence.candidateAarSha256 || '') || JSON.stringify(evidence.context) !== JSON.stringify(context)) {
            throw new Error('Composite requires reviewed plan, native configuration and same-context baseline artifact');
        }
        fs.writeFileSync(path.join(root, 'owned-integration-evidence/native-baseline.json'),
            JSON.stringify({ context, candidateAarSha256: evidence.candidateAarSha256 }), { flag: 'wx', mode: 0o600 });
        for (let index = 0; index < commands.length; index++) commands[index] = [process.execPath,
            path.join(root, 'testHelpers/server/runOwnedNativeUpgrade.js'), root,
            path.join(root, 'owned-integration-evidence/release-context.json'), fs.realpathSync(process.env.ST_NATIVE_UPGRADE_CONFIG),
            ...commands[index].slice(4)];
    }
    const coreSource = process.argv[2];
    if (!coreSource || !fs.existsSync(path.join(coreSource, 'version.yaml'))) throw new Error('Resolved Core source required');
    const evidenceRoot = path.join(root, 'owned-integration-evidence');
    const selections = [];
    for (let index = 0; index < commands.length; index++) {
        const selector = selectors[index];
        const parent = path.join(evidenceRoot, selectionDirectory(selector));
        fs.mkdirSync(parent); // A previous attempt's directory is never reused.
        const result = spawnSync('timeout', ['--signal=TERM', '--kill-after=60s', composite ? '90m' : '35m', process.execPath,
            path.join(root, 'testHelpers/server/runOrdinary.js'), coreSource, parent, '18081', '19001', ...commands[index]],
        { cwd: root, env: process.env, stdio: 'inherit' });
        if (result.error || result.status !== 0) throw result.error || new Error('Required published upgrade failed: ' + selector.mode);
        if (require('../testHelpers/testapp/artifactReceipt').sourceDigest(root) !== context.sources) throw new Error('Upgrade SDK/test sources changed');
        const owned = fs.readdirSync(parent).filter(name => name.startsWith('ordinary-owned-'));
        if (owned.length !== 1) throw new Error('Ambiguous upgrade owner');
        selections.push(composite ? require('./publishedNativeUpgradeExport').collectNativeSelection(root,
            path.join(parent, owned[0]), context, plan, selector, evidence.candidateAarSha256) :
            collectSelection(root, path.join(parent, owned[0]), context, plan, selector));
        fs.writeFileSync(path.join(evidenceRoot, 'upgrade-draft.json'), JSON.stringify({ context, selections }, null, 2));
    }
    // All four selectors must complete before upgrading the release evidence.
    const first = selections[0];
    const upgrade = composite ? { schema: 2, acceptance: require('./nativeUpgradeEvidence').acceptance, context,
        executionSources: require('./nativeUpgradeEvidence').executionSources(root), planSha256: hash(JSON.stringify(plan)),
        publishedVersion: '0.5.4', publishedAarSha256: first.binding.publishedAarSha256,
        candidateAarSha256: evidence.candidateAarSha256, nodeSdkSha256: first.binding.nodeSdkSha256,
        coreSha256: first.binding.coreSha256, coreManifestSha256: first.binding.coreManifestSha256, selections } :
        { ...context, nodeVersion: first.nodeVersion, coreVersion: first.coreVersion,
        coreSha256: first.coreSha256, nodeSdkSha256: first.nodeSdkSha256,
        publishedVersion: '0.5.4', publishedAarSha256: first.preparation.publishedAarSha256,
        candidateAarSha256: first.preparation.candidateAarSha256, selections };
    if (composite) require('./nativeUpgradeEvidence').validateComposite(upgrade, context, plan,
        require('./publishedNativeUpgradeExport').validationOptions(root, context, plan, evidence.candidateAarSha256));
    else validateUpgrade(upgrade, context, plan);
    const lane = evidence.lanes.find(value => value.name === 'published-upgrade');
    if (!lane || lane.status !== 'missing') throw new Error('Upgrade evidence already present or malformed');
    Object.assign(lane, { status: 'passed', upgrade, execution: { exit: 0, argv: ['owned-published-upgrade-matrix'],
        sourceSha256: hash(fs.readFileSync(__filename)) } });
    validateEvidence(evidence, context, settings.policy.required[context.fdi], plan);
    if (composite) {
        if (hash(fs.readFileSync(matrices[0])) !== hash(baselineBytes)) throw new Error('Baseline changed during composite execution');
        const temporary = matrices[0] + '.native-' + context.nonce + '.tmp';
        fs.writeFileSync(temporary, JSON.stringify(evidence, null, 2), { flag: 'wx', mode: 0o600 });
        fs.renameSync(temporary, matrices[0]);
    } else fs.writeFileSync(matrices[0], JSON.stringify(evidence, null, 2));
}
module.exports = { primaryCommand, preflight, selectionDirectory };
if (require.main === module) {
    try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
