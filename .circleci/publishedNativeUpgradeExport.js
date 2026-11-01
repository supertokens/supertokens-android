const fs = require('fs');
const path = require('path');
const { isDeepStrictEqual: equal } = require('util');
const { hash, cleanupProof, contextValid } = require('./integrationEvidence');
const { key, names, flows, validatePlan } = require('./upgradeEvidence');
const { executionSources, artifact, publishedPin, validateSelection } = require('./nativeUpgradeEvidence');
const { commands } = require('../testHelpers/testapp/runTests');
const { parse } = require('../testHelpers/testapp/upgradeTerminal');
const { verifyNative } = require('../testHelpers/upgradeapp/tools/nativeArchive');
const read = file => JSON.parse(fs.readFileSync(file));
function check(value, message) { if (!value) throw new Error(message); }

function bindingFor(root, owned, context, plan, selector, candidateAarSha256) {
    contextValid(context); validatePlan(plan);
    check(require('../testHelpers/testapp/artifactReceipt').sourceDigest(root) === context.sources,
        'Current sources differ from release context');
    const configured = read(path.join(owned, 'exposure-config.json'));
    const identity = configured.identity, capabilities = configured.capabilities;
    check(configured.profile === 'upgrade' && configured.selected.mode === selector.mode && configured.selected.route === selector.route &&
        configured.flags.sessionJwt === selector.enableJWT && configured.selected.exposed === selector.enableJWT &&
        capabilities.qualificationProxy === false && capabilities.compatible === true && capabilities.coreReachable === true &&
        capabilities.nodeVersion === plan.nodeVersion && identity.nodeSdkVersion === plan.nodeVersion && identity.coreVersion === plan.coreVersion,
    'Wrong actual exposure/pair');
    check(selector.route !== '/login-2.18' || capabilities.coreCdiVersions.includes('2.18'), 'Missing historical CDI qualification');
    const versions = plan.phaseVersions[key(selector)];
    const scalar = selector.route === '/login' && flows.every(flow => flow === 'SIGN_OUT' ||
        versions.publishedHandoff[flow] === versions.publishedHandoff.LOGIN) && configured.selected.properties.includes(
        '-Pupgrade.expectedPublishedHandoffVersion=' + versions.publishedHandoff.LOGIN);
    const selected = require('../testHelpers/server/ownedExposure').select('upgrade', [selector.mode, context.fdi,
        versions.publishedLogin, scalar ? versions.publishedHandoff.LOGIN : JSON.stringify(versions.publishedHandoff), versions.candidateRefresh,
        ...(selector.route === '/login' ? [] : [selector.route])], context.backend);
    check(equal(selected, configured.selected), 'Configured selectors differ from reviewed exact flow plan');
    const owner = read(path.join(owned, 'owner.json'));
    check(owner.root === owned && owner.runId === configured.runId, 'Owned configuration mismatch');
    return { context, mode: selector.mode, route: selector.route, sessionJwt: selector.enableJWT,
        runId: configured.runId, instance: configured.instance, nodeVersion: identity.nodeSdkVersion, coreVersion: identity.coreVersion,
        qualifiedIdentitySha256: hash(JSON.stringify(identity)), nodeSdkSha256: identity.sdkImplementationSha256,
        coreSha256: identity.coreSha256, coreManifestSha256: identity.effectiveClasspathSha256,
        dependenciesSha256: fs.readFileSync(path.join(owned, 'dependencies.sha256'), 'utf8').trim(),
        publishedAarSha256: publishedPin, candidateAarSha256, planSha256: hash(JSON.stringify(plan)),
        executionSourcesSha256: executionSources(root).manifestSha256 };
}

function collectExpected(root, selection, selector, context, plan, candidateAarSha256) {
    const owned = selection.owned;
    const ownerParent = path.join(root, 'owned-integration-evidence', 'upgrade-' + selector.mode +
        (selector.route === '/login-2.18' ? '-login-2.18' : ''));
    check(typeof owned === 'string' && path.dirname(owned) === ownerParent && path.basename(owned).startsWith('ordinary-owned-') &&
        fs.realpathSync(owned) === owned, 'Composite owner path escape');
    const pointer = read(path.join(owned, 'upgrade-terminal.json'));
    check(pointer.kind === 'jvm-terminal-pointer' && /^[a-f0-9]{48}$/.test(pointer.nonce || ''), 'Missing actual JVM terminal pointer');
    const directory = path.join(root, 'testHelpers/testapp/app/build/integration-receipts', pointer.nonce);
    check(pointer.directory === directory && fs.realpathSync(directory) === directory, 'Stale receipt directory');
    const preparation = read(path.join(directory, 'preparation.json'));
    const binding = bindingFor(root, owned, context, plan, selector, candidateAarSha256);
    const selected = read(path.join(owned, 'exposure-config.json')).selected;
    const exactCommands = commands('upgrade', selected.properties, context.backend);
    check(preparation.root === root && preparation.nonce === pointer.nonce && preparation.mode === 'upgrade' &&
        preparation.sources === context.sources && equal(preparation.commands, exactCommands) &&
        preparation.artifactHashes['app/build/outputs/aar/app-debug.aar'] === candidateAarSha256 &&
        pointer.runId === binding.runId && pointer.instance === binding.instance && selection.preparationNonce === pointer.nonce &&
        equal(pointer.terminalExecution, selection.jvm.terminalExecution) && equal(pointer.originalReport, selection.jvm.originalReport) &&
        equal(pointer.failureDetails, selection.jvm.failureDetails), 'Preparation/invocation/terminal pointer mismatch');
    const terminal = JSON.parse(artifact(directory, pointer.terminalExecution));
    const log = artifact(directory, terminal.log).toString();
    if (terminal.exit !== 0) check(equal([...log.matchAll(/^Execution failed for task '([^']+)'\./gm)].map(match => match[1]),
        [':app:upgradeLiveTest']) && log.includes('There were failing tests.'), 'Actual Gradle failure is not solely the selected test task');
    const parsed = parse(artifact(directory, terminal.xml).toString(), JSON.parse(artifact(directory, terminal.summary)), selector.mode, selector.route, pointer.nonce);
    check(equal(parsed.report, JSON.parse(artifact(directory, terminal.report))), 'Normalized JVM report was edited');
    check(equal(selection.jvm.successfulObservations, observations(directory, binding, pointer.nonce, parsed.report)),
        'JVM observations differ from actual archived observations');
    const failures = JSON.parse(artifact(directory, terminal.failureDetails));
    check(failures.length === parsed.failures.length && failures.every((failure, index) => {
        const { rawFailure, publishedAarSha256, ...facts } = failure;
        const { raw, ...actual } = parsed.failures[index];
        return publishedAarSha256 === publishedPin && equal(facts, actual) && artifact(directory, rawFailure).toString() === raw;
    }), 'Failure details differ from original XML');
    const drain = read(path.join(owned, 'jvm-drained.json'));
    check(drain.nonce === pointer.nonce && drain.exit === terminal.exit && drain.runId === binding.runId &&
        drain.logSha256 === hash(fs.readFileSync(path.join(owned, 'jvm-process-tree.jsonl'))) &&
        readLines(path.join(owned, 'jvm-process-tree.jsonl')).filter(event => event.action === 'DRAINED').length === 1,
    'JVM worker drain not proven');
    const window = read(path.join(owned, 'composite-window.json'));
    const executor = require('../testHelpers/server/nativeExecutor');
    const nativeSelection = executor.reconstruct(root, owned, context);
    for (const row of selection.android) require('../testHelpers/server/nativeUpgradeControl').verifyBackendArchive(
        owned, binding, row.executionNonce, path.join(directory, 'native-backend', row.executionNonce));
    const expected = { root, binding, selector, plan, preparationNonce: pointer.nonce, directory, owned,
        startedMs: window.startedMs, endedMs: window.endedMs, jvmWorkersDrained: true,
        ...nativeSelection, jvmStartedMs: terminal.startedMs, jvmEndedMs: terminal.endedMs,
        jvmArgv: [path.join(root, 'testHelpers/testapp/gradlew'), ...exactCommands[1]] };
    executor.verifyClosed(expected);
    return expected;
}

function readLines(file) { return fs.readFileSync(file, 'utf8').trim().split('\n').filter(Boolean).map(line => JSON.parse(line)); }

function observations(directory, binding, preparationNonce, report) {
    const successful = flows.filter((flow, index) => report.cases.find(row => row.className + '.' + row.name === names(binding)[index]).result === 'SUCCESS');
    const folder = path.join(directory, 'reports/upgrade-observations');
    check(equal(fs.readdirSync(folder).sort(), successful.map(flow => flow + '.json').sort()), 'Wrong observation set');
    return successful.map(flow => {
        const row = read(path.join(folder, flow + '.json'));
        check(row.flow === flow && row.mode === binding.mode && row.route === binding.route && row.fdi === binding.context.fdi &&
            row.backend === binding.context.backend && row.runId === binding.runId && row.preparationNonce === preparationNonce &&
            row.nodeVersion === binding.nodeVersion && row.sessionJwt === binding.sessionJwt, 'Stale phase observation');
        return { ...row, binding };
    });
}

function collectNativeSelection(root, owned, context, plan, selector, candidateAarSha256) {
    const selection = read(path.join(owned, 'composite-selection.json'));
    check(selection.owned === owned, 'Composite owner changed');
    const expected = collectExpected(root, selection, selector, context, plan, candidateAarSha256);
    check(equal(selection.jvm.successfulObservations, observations(expected.directory, expected.binding, expected.preparationNonce,
        JSON.parse(artifact(expected.directory, selection.jvm.originalReport)))), 'JVM observations were changed');
    validateSelection(selection, expected, expected.directory, verifyNative);
    return { ...selection, cleanup: cleanupProof(owned, expected.binding.runId, expected.binding.instance) };
}

function validationOptions(root, context, plan, candidateAarSha256) {
    return { root, candidateAarSha256, verifyNative,
        collectExpected: (selection, selector) => collectExpected(root, selection, selector, context, plan, candidateAarSha256) };
}
module.exports = { bindingFor, collectExpected, observations, collectNativeSelection, validationOptions };
