const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { isDeepStrictEqual: equal } = require('util');
const { OwnedCommandGroup } = require('./ownedCommandGroup');
const { main: qualify } = require('./runOwnedTests');
const { config, contextValid, hash } = require('../../.circleci/integrationEvidence');
const { selectors, key, validatePlan } = require('../../.circleci/upgradeEvidence');
const { acceptance, executionSources, artifact, classifyJvm, validateSelection } = require('../../.circleci/nativeUpgradeEvidence');
const { bindingFor, observations } = require('../../.circleci/publishedNativeUpgradeExport');
const native = require('../upgradeapp/tools/nativeArchive');
const nativeBackend = require('./nativeUpgradeControl');
const nativeExecutor = require('./nativeExecutor');
const { commands } = require('../testapp/runTests');
const { sourceDigest, artifactHashes } = require('../testapp/artifactReceipt');
const read = file => JSON.parse(fs.readFileSync(file));
const nonce = () => crypto.randomBytes(24).toString('hex');
function check(value, message) { if (!value) throw new Error(message); }
function save(file, value) { fs.writeFileSync(file, JSON.stringify(value, null, 2), { flag: 'wx', mode: 0o600 }); }

async function runNativeRows(flows, execute) {
    check(equal(flows, []) || equal(flows, ['REFRESH', 'SIGN_OUT']), 'Unapproved native substitution list');
    for (const flow of flows) await execute(flow);
}

async function runChild(argv, root, env, logFile, timeoutMs, runtime = {}) {
    fs.writeFileSync(logFile, '', { flag: 'wx', mode: 0o600 });
    const Group = runtime.Group || OwnedCommandGroup, signals = runtime.signals || process;
    const schedule = runtime.setTimeout || setTimeout, unschedule = runtime.clearTimeout || clearTimeout;
    const group = new Group(env.ST_TEST_RUN_ID, event => fs.appendFileSync(logFile, JSON.stringify(event) + '\n'));
    let timer, abort;
    const interrupted = new Promise((resolve, reject) => {
        abort = () => reject(new Error('Composite interrupted'));
        signals.once('SIGTERM', abort); signals.once('SIGINT', abort);
        timer = schedule(() => reject(new Error('Composite child timed out')), timeoutMs);
    });
    interrupted.catch(() => {});
    try {
        const execution = group.start(argv, { cwd: root, env }).then(() => group.done);
        return await Promise.race([execution, interrupted]);
    } finally {
        unschedule(timer);
        group.cancelStart();
        try { await group.drain(); }
        finally { signals.removeListener('SIGTERM', abort); signals.removeListener('SIGINT', abort); }
    }
}

function nativeInput(binding, plan, flow, preparationNonce, nativeNonce, deviceBackend, executor, leaseNonce) {
    const phases = plan.phaseVersions[key(binding)];
    return { schema: 1, kind: 'matrix-native-input', mode: binding.mode, flow, route: binding.route,
        runId: binding.runId, instance: binding.instance, preparationNonce, nativeNonce, deviceBackend,
        candidateAarSha256: binding.candidateAarSha256, qualifiedIdentitySha256: binding.qualifiedIdentitySha256,
        dependenciesSha256: binding.dependenciesSha256, nodeSdkSha256: binding.nodeSdkSha256, coreSha256: binding.coreSha256,
        coreManifestSha256: binding.coreManifestSha256, planSha256: binding.planSha256, publishedAarSha256: binding.publishedAarSha256,
        executionSourcesSha256: binding.executionSourcesSha256, context: binding.context,
        toolchain: { apksigner: executor.nativeTools.apksigner }, leaseNonce,
        plan: { review: plan.review.reference, nodeVersion: plan.nodeVersion, coreVersion: plan.coreVersion,
            phases: { publishedLogin: phases.publishedLogin, publishedHandoff: phases.publishedHandoff[flow], candidateRefresh: phases.candidateRefresh } } };
}

function nativeCommand(root, binding, inputFile, output, config, executor, lifecycle = 'row') {
    const command = ['python3', path.join(root, 'testHelpers/upgradeapp/tools/upgrade.py'), '--scenario', 'matrix-native',
        '--matrix-config', inputFile, '--run-id', binding.runId, '--serial', executor.nativeSerial, '--backend', config.backend,
        '--device-lease-root', executor.nativeLeaseRoot, '--output', output, '--sdk-apk', config.sdkApk, '--sdk-test-apk', config.sdkTestApk,
        '--candidate-apk', config.candidateApk, '--candidate-test-apk', config.candidateTestApk,
        '--candidate-aar-sha256', binding.candidateAarSha256, '--candidate-build-provenance', config.candidateBuildProvenance,
        '--aapt', executor.nativeTools.aapt.path, '--apksigner', executor.nativeTools.apksigner.path,
        '--native-build-receipt', executor.nativeBuild.path, '--native-build-sha256', executor.nativeBuild.sha256];
    if (config.allowTestOnly === true) command.push('--allow-test-only');
    check(['preflight', 'row', 'close'].includes(lifecycle), 'Invalid native lease lifecycle');
    if (lifecycle === 'preflight') command.push('--preflight-only');
    else {
        command.push('--device-lease-session', executor.nativePreflight);
        if (lifecycle === 'close') command.push('--release-lease-only');
    }
    check(command.every(value => typeof value === 'string' && value.length > 0), 'Missing exact native arguments');
    return command;
}

async function withDevicePreflight({ root, owned, expected, config }, execute, child = runChild) {
    if (expected.selector.mode === 'HEADER') return execute(null);
    const leaseNonce = nonce(), request = path.join(owned, 'native-lease-input.json');
    const input = nativeInput(expected.binding, expected.plan, 'REFRESH', expected.preparationNonce, leaseNonce,
        config.backend, expected, leaseNonce);
    input.challenge = nonce();
    save(request, input);
    const env = { ...process.env, ST_TEST_RUN_ID: expected.binding.runId, ADB: expected.nativeTools.adb.path, PYTHONDONTWRITEBYTECODE: '1' };
    const code = await child(nativeCommand(root, expected.binding, request, expected.nativePreflight, config, expected, 'preflight'),
        root, env, path.join(owned, 'native-preflight-process-tree.jsonl'), 10 * 60 * 1000);
    check(code === 0, 'Exclusive native preflight failed before JVM dispatch');
    nativeExecutor.assertHeld(expected);
    try { return await execute(leaseNonce); }
    finally {
        const closed = await child(nativeCommand(root, expected.binding, request, expected.nativeLeaseClose, config, expected, 'close'),
            root, env, path.join(owned, 'native-close-process-tree.jsonl'), 10 * 60 * 1000);
        check(closed === 0, 'Native selector lease cleanup failed; ownership retained');
    }
}

async function main(args = process.argv.slice(2)) {
    const [repository, contextFile, deviceConfigFile, ...options] = args;
    const root = fs.realpathSync(repository), owned = fs.realpathSync(process.env.INSTALL_PATH);
    const context = read(contextFile), settings = config(root), plan = settings.policy.upgrade[context.fdi];
    contextValid(context); validatePlan(plan);
    check(settings.policy.upgradeAcceptance === acceptance && plan.review && typeof plan.review.reference === 'string' &&
        plan.review.reference.trim() && context.policySha256 === settings.policySha256 && context.sources === sourceDigest(root),
    'Unapproved composite mode/review/source');
    const selector = selectors.find(item => item.mode === options[0] && item.route === (options[5] || '/login'));
    check(selector && options[1] === context.fdi, 'Wrong composite selector');
    const baseline = read(path.join(root, 'owned-integration-evidence/native-baseline.json'));
    check(equal(baseline.context, context) && /^[a-f0-9]{64}$/.test(baseline.candidateAarSha256 || ''), 'Missing baseline artifact binding');
    const deviceConfig = read(deviceConfigFile), frozenConfig = hash(fs.readFileSync(deviceConfigFile));
    nativeExecutor.freeze(root, owned, context, deviceConfigFile);
    const executor = nativeExecutor.reconstruct(root, owned, context);
    for (const field of ['sdkApk', 'sdkTestApk', 'candidateApk', 'candidateTestApk', 'candidateBuildProvenance']) {
        check(typeof deviceConfig[field] === 'string' && fs.statSync(deviceConfig[field]).isFile(), 'Missing native artifact: ' + field);
    }
    const lock = path.join(root, '.integration-lock'), preparationNonce = nonce();
    fs.mkdirSync(lock, { mode: 0o700 });
    fs.writeFileSync(path.join(lock, 'nonce'), preparationNonce, { flag: 'wx' });
    fs.writeFileSync(path.join(lock, 'owner'), process.env.ST_TEST_RUN_ID, { flag: 'wx' });
    const startedMs = Date.now(), frozenSources = executionSources(root);
    let cleanupProven = false;
    try {
        await qualify(['upgrade', root, ...options], async qualified => {
            const binding = bindingFor(root, owned, context, plan, selector, baseline.candidateAarSha256);
            const expected = { root, owned, binding, selector, plan, preparationNonce, startedMs, endedMs: Date.now(),
                directory: path.join(root, 'testHelpers/testapp/app/build/integration-receipts', preparationNonce),
                jvmArgv: [path.join(root, 'testHelpers/testapp/gradlew'), ...commands('upgrade', qualified.selected.properties, context.backend)[1]],
                jvmWorkersDrained: false, ...executor };
            const bindingFile = path.join(owned, 'composite-binding.json');
            save(bindingFile, binding);
            function assertFrozen() {
                if (selector.mode !== 'HEADER') nativeExecutor.assertHeld(expected);
                check(equal(executor, nativeExecutor.reconstruct(root, owned, context)), 'Native executor tools/selection changed');
                check(context.sources === sourceDigest(root) && equal(frozenSources, executionSources(root)) &&
                    hash(fs.readFileSync(deviceConfigFile)) === frozenConfig &&
                    equal(binding, bindingFor(root, owned, context, plan, selector, baseline.candidateAarSha256)) &&
                    equal(qualified.identity, qualified.qualification.identity()), 'Composite source/pair/instance drift');
            }
            const selection = await withDevicePreflight({ root, owned, expected, config: deviceConfig }, async leaseNonce => {
            assertFrozen();
            const argv = [process.execPath, path.join(root, 'testHelpers/testapp/runTests.js'), 'upgrade', ...qualified.selected.properties];
            const exit = await runChild(argv, root, { ...process.env, ST_UPGRADE_COMPOSITE_BINDING: bindingFile,
                ST_UPGRADE_PREPARATION_NONCE: preparationNonce }, path.join(owned, 'jvm-process-tree.jsonl'), 30 * 60 * 1000);
            const pointer = read(path.join(owned, 'upgrade-terminal.json'));
            check(pointer.nonce === preparationNonce && pointer.directory === expected.directory, 'Wrong JVM terminal pointer');
            const terminal = JSON.parse(artifact(expected.directory, pointer.terminalExecution));
            expected.jvmStartedMs = terminal.startedMs;
            expected.jvmEndedMs = terminal.endedMs;
            check(exit === terminal.exit, 'JVM child/Gradle outcome disagreement');
            save(path.join(owned, 'jvm-drained.json'), { runId: binding.runId, nonce: preparationNonce, exit,
                logSha256: hash(fs.readFileSync(path.join(owned, 'jvm-process-tree.jsonl'))) });
            expected.jvmWorkersDrained = true; expected.endedMs = Date.now();
            const jvm = { terminalExecution: pointer.terminalExecution, originalReport: pointer.originalReport,
                failureDetails: pointer.failureDetails };
            const classified = classifyJvm(jvm, expected, expected.directory);
            jvm.successfulObservations = observations(expected.directory, binding, preparationNonce, classified.report);
            const selection = { ...selector, owned, binding, preparationNonce, jvm, android: [] };
            const preparedArtifacts = artifactHashes(root);
            check(preparedArtifacts['app/build/outputs/aar/app-debug.aar'] === binding.candidateAarSha256, 'Candidate differs from baseline');
            await runNativeRows(classified.nativeFlows, async flow => {
                assertFrozen();
                const executionNonce = nonce(), output = path.join(expected.directory, 'native', executionNonce);
                const input = nativeInput(binding, plan, flow, preparationNonce, executionNonce, deviceConfig.backend, executor, leaseNonce);
                const control = nativeBackend.createControl(owned, binding, executionNonce);
                input.challenge = control.challenge;
                const inputFile = path.join(expected.directory, 'native-' + flow + '-input.json');
                save(inputFile, input);
                const command = nativeCommand(root, binding, inputFile, output, deviceConfig, executor);
                const rowStartedMs = Date.now();
                const nativeExit = await runChild(command, root, { ...process.env, ADB: executor.nativeTools.adb.path,
                    PYTHONDONTWRITEBYTECODE: '1' }, path.join(owned, 'native-' + flow + '-process-tree.jsonl'), 20 * 60 * 1000);
                check(nativeExit === 0, 'Native child failed');
                await nativeBackend.finalize(owned, binding, executionNonce, path.join(expected.directory, 'native-backend', executionNonce),
                    require(path.join(owned, 'backend/node_modules/node-fetch')));
                const device = read(path.join(output, 'native-device.json'));
                check(device.kind === 'matrix-native-device' && device.status === 'device-assertions-passed' &&
                    equal(device.binding, input) && Array.isArray(device.stages) && device.stages.length === 3 &&
                    Number.isSafeInteger(device.startedMs) && Number.isSafeInteger(device.endedMs) &&
                    rowStartedMs <= device.startedMs && device.startedMs <= device.endedMs && device.endedMs <= Date.now(),
                'Invalid native device receipt');
                const row = { schema: 2, kind: 'matrix-native-upgrade', mode: selector.mode, route: selector.route, flow,
                    sessionJwt: selector.enableJWT, parentPreparationNonce: preparationNonce, executionNonce,
                    rowId: selector.mode + ':' + flow + ':' + executionNonce, binding, startedMs: device.startedMs, endedMs: device.endedMs,
                    rawEvidenceSha256: hash(fs.readFileSync(path.join(output, 'native-device.json'))),
                    publishedFirst: device.stages[0].assertionsPassed,
                    candidateContinued: device.stages[1].assertionsPassed, scopedCleanup: device.stages[2].cleanupDone,
                    observed: device.stages[2].observed };
                selection.android.push(row);
                expected.endedMs = Date.now();
                assertFrozen();
                check(equal(preparedArtifacts, artifactHashes(root)), 'Prepared artifacts changed during native execution');
            });
            return selection;
            });
            expected.endedMs = Date.now();
            nativeExecutor.verifyClosed(expected);
            validateSelection(selection, expected, expected.directory, native.verifyNative);
            check(context.sources === sourceDigest(root) && equal(frozenSources, executionSources(root)) &&
                equal(binding, bindingFor(root, owned, context, plan, selector, baseline.candidateAarSha256)) &&
                equal(qualified.identity, qualified.qualification.identity()), 'Composite changed during native lease close');
            save(path.join(owned, 'composite-window.json'), { startedMs, endedMs: Date.now() });
            save(path.join(owned, 'composite-selection.json'), selection);
        });
        cleanupProven = true;
    } finally {
        if (cleanupProven) fs.rmSync(lock, { recursive: true });
        else {
            // Keep the build/device ownership barrier. Recovery cannot create composite-selection.json.
            fs.writeFileSync(path.join(lock, 'RETAINED.json'), JSON.stringify({ runId: process.env.ST_TEST_RUN_ID, preparationNonce }), { flag: 'wx' });
            // Native producer owns scoped failure cleanup; unresolved device ownership remains retained.
        }
    }
}

module.exports = { main, nativeInput, nativeCommand, runChild, runNativeRows, withDevicePreflight };
if (require.main === module) main().catch(error => { console.error(error); process.exitCode = 1; });
