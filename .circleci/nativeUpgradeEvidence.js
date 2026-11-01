const fs = require('fs');
const path = require('path');
const { isDeepStrictEqual: equal } = require('util');
const { selectors, flows, key, names, validatePlan, validateUpgrade } = require('./upgradeEvidence');
const { hash, contextValid, cleanupProof } = require('./integrationEvidence');
const { accept } = require('../testHelpers/testapp/exposureAcceptance');

const acceptance = 'published-upgrade-native-v1';
const publishedPin = '2f3b4a94a036d21ced610f9c41707d7e1ccf7c9c0256e47e5686c1c76b8224b9';
const alternateFlows = ['REFRESH', 'SIGN_OUT'];
const digest = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const nonce = value => typeof value === 'string' && /^[a-f0-9]{48}$/.test(value);
function check(value, message) { if (!value) throw new Error(message); }
function eligible(selector) { return selector.route === '/login' && ['COOKIE', 'EXPOSED_COOKIE'].includes(selector.mode); }
function exactSelector(selector) {
    check(selectors.some(item => key(item) === key(selector) && item.enableJWT === selector.enableJWT), 'Invalid selector');
}

// Capture all execution inputs, including new files, rather than extending the older SDK-only digest silently.
function executionSources(root) {
    const entries = ['app', 'buildSrc', 'build.gradle', 'settings.gradle', 'gradle.properties', 'local.properties',
        'gradle', 'gradlew', 'frontendDriverInterfaceSupported.json', '.circleci',
        'testHelpers/testapp', 'testHelpers/upgradeapp', 'testHelpers/device', 'testHelpers/server'];
    const manifest = [];
    function visit(relative) {
        const file = path.join(root, relative);
        let stat;
        try { stat = fs.lstatSync(file); } catch (error) {
            if (error.code !== 'ENOENT') throw error;
            manifest.push({ path: relative, missing: true }); return;
        }
        check(!stat.isSymbolicLink(), 'Symlink in execution sources: ' + relative);
        if (stat.isDirectory()) {
            for (const name of fs.readdirSync(file).sort()) {
                if (['build', '.gradle', '.integration-lock', 'node_modules', '__pycache__'].includes(name)) continue;
                visit(relative + '/' + name);
            }
        } else {
            check(stat.isFile(), 'Non-file execution source');
            manifest.push({ path: relative, sha256: hash(fs.readFileSync(file)) });
        }
    }
    entries.forEach(visit);
    return { manifest, manifestSha256: hash(JSON.stringify(manifest)) };
}

// All paths originate in a fresh private receipt tree; reject even in-tree symlinks.
function artifact(directory, reference) {
    check(reference && typeof reference.path === 'string' && digest(reference.sha256), 'Missing artifact reference');
    const parts = reference.path.split('/');
    check(parts.every(part => part && part !== '.' && part !== '..') && !path.isAbsolute(reference.path), 'Artifact path escape');
    let file = directory;
    check(fs.realpathSync(directory) === directory, 'Receipt directory symlink');
    for (const part of parts) {
        file = path.join(file, part);
        check(!fs.lstatSync(file).isSymbolicLink(), 'Artifact symlink');
    }
    check(fs.statSync(file).isFile(), 'Artifact is not a file');
    const bytes = fs.readFileSync(file);
    check(hash(bytes) === reference.sha256, 'Artifact hash mismatch');
    return bytes;
}

function classifyJvm(jvm, expected, directory) {
    exactSelector(expected.selector);
    const terminal = JSON.parse(artifact(directory, jvm.terminalExecution));
    check(terminal.schema === 1 && terminal.kind === 'jvm-terminal' && equal(terminal.binding, expected.binding) &&
        terminal.nonce === expected.preparationNonce && terminal.signal === null && terminal.spawnError === null &&
        terminal.javaMajor === 11 && /^openjdk version "11\./m.test(terminal.javaVersion) &&
        equal(terminal.argv, expected.jvmArgv) && terminal.argv.includes('--rerun-tasks') &&
        terminal.argv.includes('--no-build-cache') && terminal.argv.includes(':app:upgradeLiveTest') &&
        Number.isSafeInteger(terminal.startedMs) && Number.isSafeInteger(terminal.endedMs) &&
        terminal.startedMs >= expected.startedMs && terminal.endedMs >= terminal.startedMs &&
        terminal.endedMs <= expected.endedMs && equal(terminal.report, jvm.originalReport) &&
        equal(terminal.failureDetails, jvm.failureDetails) && expected.jvmWorkersDrained === true,
    'Invalid JVM terminal execution');
    const report = JSON.parse(artifact(directory, jvm.originalReport));
    const details = JSON.parse(artifact(directory, jvm.failureDetails));
    check(report.rerun === true && ['excluded', 'excludedTasks', 'excludes'].every(field =>
        Array.isArray(report[field]) && report[field].length === 0), 'Excluded/unfresh JVM execution');
    check(report.tests === 4 && report.skipped === 0 && report.errors === 0 && report.aborted === 0 &&
        Array.isArray(report.cases) && report.cases.length === 4 &&
        equal(report.cases.map(row => row.className + '.' + row.name).sort(), names(expected.selector).sort()),
    'Missing/duplicate/skipped/error JVM cases');
    if (terminal.exit === 0) {
        accept('upgrade', expected.selector.mode, report, expected.selector.route);
        check(terminal.outcome === 'passed' && equal(details, []), 'Inconsistent successful JVM terminal');
        return { report, terminal, nativeFlows: [] };
    }
    check(terminal.exit === 1 && terminal.outcome === 'test-failure' && eligible(expected.selector) &&
        report.passed === 2 && report.failed === 2 && Array.isArray(details) && details.length === 2,
    'Not an approved published Java11 failure');
    for (let index = 0; index < flows.length; index++) {
        const flow = flows[index], identity = names(expected.selector)[index];
        const row = report.cases.find(item => item.className + '.' + item.name === identity);
        check(row.result === (alternateFlows.includes(flow) ? 'FAILURE' : 'SUCCESS'), 'Unexpected JVM row result');
        if (!alternateFlows.includes(flow)) continue;
        const failures = details.filter(item => item.identity === identity);
        check(failures.length === 1, 'Missing/duplicate failure origin');
        const failure = failures[0];
        const method = flow === 'REFRESH' ? 'refresh' : 'signOut';
        check(failure.phase === 'published' && failure.publishedAarSha256 === publishedPin &&
            failure.candidateInvoked === false && failure.rootType === 'java.lang.IllegalArgumentException' &&
            failure.rootMessage === 'Empty cookie header string' && Array.isArray(failure.frames) &&
            failure.frames.includes('java.net.HttpCookie.parseInternal') &&
            failure.frames.includes('com.supertokens.session.SuperTokensHttpURLConnection.manuallySetCookiesFromResponse') &&
            failure.frames.includes('com.supertokens.session.Legacy054Driver.' + method) &&
            failure.frames.includes('com.supertokens.testing.PublishedUpgradeScenario.run'), 'Wrong structured published failure');
        // The producer must extract structured details from these original per-case XML failure bytes.
        const raw = artifact(directory, failure.rawFailure).toString();
        check(/^java\.(?:io\.IOException: java\.lang\.|lang\.)IllegalArgumentException: Empty cookie header string\r?\n/.test(raw) &&
            new RegExp('^\\s+at com\\.supertokens\\.session\\.Legacy054Driver\\.' + method + '\\(', 'm').test(raw) &&
            /^\s+at java\.base\/java\.net\.HttpCookie\.parseInternal\(/m.test(raw) &&
            /^\s+at com\.supertokens\.session\.SuperTokensHttpURLConnection\.manuallySetCookiesFromResponse\(/m.test(raw),
        'Failure bytes do not match classified origin');
    }
    return { report, terminal, nativeFlows: [...alternateFlows] };
}

function validatePhases(row, selector, plan) {
    const expected = plan.phaseVersions[key(selector)];
    check(row && row.observed && row.observed.schemaVersion === 2 && row.publishedFirst === true &&
        row.candidateContinued === true && row.scopedCleanup === true, 'Missing actual continuation/cleanup observations');
    for (const phase of ['publishedLogin', 'publishedHandoff', 'candidateRefresh']) {
        const observed = row.observed[phase];
        const absent = row.flow === 'SIGN_OUT' && phase === 'publishedHandoff';
        check(observed && observed.status === (absent ? 'ABSENT' : 'PRESENT') &&
            observed.tokenVersion === (absent ? null : phase === 'publishedHandoff' ? expected.publishedHandoff[row.flow] : expected[phase]),
        'Phase observation differs from reviewed plan');
    }
}

// verifyNative is a trusted producer-side archive verifier, never a receipt-provided function or boolean.
// No default verifier: existing continuation archives cannot enter this route.
function validateSelection(selection, expected, directory, verifyNative) {
    const classified = classifyJvm(selection.jvm, expected, directory);
    check(Array.isArray(selection.android) && selection.android.length === classified.nativeFlows.length,
        'Missing/extra native alternatives');
    const observations = selection.jvm.successfulObservations;
    check(Array.isArray(observations) && observations.length === 4 - classified.nativeFlows.length, 'Missing JVM observations');
    const rowPlatforms = [], seen = new Set();
    let previousEnd = classified.terminal.endedMs;
    for (const flow of flows) {
        if (!classified.nativeFlows.includes(flow)) {
            const matches = observations.filter(row => row.flow === flow);
            check(matches.length === 1, 'Duplicate/missing successful observation');
            check(equal(matches[0].binding, expected.binding) && matches[0].preparationNonce === expected.preparationNonce,
                'Stale JVM observation');
            validatePhases(matches[0], expected.selector, expected.plan);
            rowPlatforms.push({ flow, platform: 'jvm' });
        }
    }
    for (let index = 0; index < classified.nativeFlows.length; index++) {
        const flow = classified.nativeFlows[index], row = selection.android[index];
        check(row && row.schema === 2 && row.kind === 'matrix-native-upgrade' && row.flow === flow &&
            row.mode === expected.selector.mode && row.route === '/login' && row.sessionJwt === expected.selector.enableJWT &&
            row.parentPreparationNonce === expected.preparationNonce && equal(row.binding, expected.binding) &&
            nonce(row.executionNonce) && !seen.has(row.executionNonce) && row.executionNonce !== expected.preparationNonce &&
            row.rowId === expected.selector.mode + ':' + flow + ':' + row.executionNonce &&
            Number.isSafeInteger(row.startedMs) && Number.isSafeInteger(row.endedMs) && row.startedMs >= previousEnd &&
            row.endedMs >= row.startedMs && row.endedMs <= expected.endedMs, 'Native row identity/freshness/order mismatch');
        seen.add(row.executionNonce); previousEnd = row.endedMs;
        validatePhases(row, expected.selector, expected.plan);
        check(typeof verifyNative === 'function', 'Dedicated native archive verifier not integrated; release blocked');
        const facts = verifyNative(row, expected, directory);
        check(facts && equal(facts.binding, expected.binding) && facts.executionNonce === row.executionNonce &&
            facts.rawEvidenceSha256 === row.rawEvidenceSha256 && digest(facts.rawEvidenceSha256) &&
            facts.deviceCleanup === true && facts.workersDrained === true && facts.pendingCreations === 0 &&
            facts.ownerReceiptsResolved === true, 'Native archive/cleanup verification failed');
        rowPlatforms.push({ flow, platform: 'android' });
    }
    return { report: classified.report, rowPlatforms: flows.map(flow => rowPlatforms.find(row => row.flow === flow)) };
}

function validateComposite(upgrade, context, plan, options) {
    contextValid(context); validatePlan(plan);
    if (upgrade.schema !== 2) { validateUpgrade(upgrade, context, plan); return { status: 'passed', platform: 'jvm' }; }
    check(upgrade.acceptance === acceptance && equal(upgrade.context, context) &&
        plan.review && typeof plan.review.reference === 'string' && plan.review.reference.trim() &&
        upgrade.planSha256 === hash(JSON.stringify(plan)) &&
        equal(upgrade.executionSources, executionSources(options.root)) &&
        upgrade.publishedVersion === '0.5.4' && upgrade.publishedAarSha256 === publishedPin &&
        upgrade.candidateAarSha256 === options.candidateAarSha256 && digest(upgrade.candidateAarSha256) &&
        ['nodeSdkSha256', 'coreSha256', 'coreManifestSha256'].every(field => digest(upgrade[field])) &&
        upgrade.candidateAarSha256 !== publishedPin && Array.isArray(upgrade.selections) && upgrade.selections.length === 4,
    'Composite context/source/artifact/review mismatch');
    const results = [], runs = new Set(), preparations = new Set(), executions = new Set();
    for (const selector of selectors) {
        const matches = upgrade.selections.filter(item => key(item) === key(selector));
        check(matches.length === 1, 'Missing/duplicate selector');
        const selection = matches[0], binding = selection.binding;
        check(binding && equal(binding.context, context) && binding.mode === selector.mode && binding.route === selector.route &&
            binding.sessionJwt === selector.enableJWT && binding.nodeVersion === plan.nodeVersion && binding.coreVersion === plan.coreVersion &&
            binding.planSha256 === upgrade.planSha256 && binding.executionSourcesSha256 === upgrade.executionSources.manifestSha256 &&
            binding.candidateAarSha256 === upgrade.candidateAarSha256 && binding.publishedAarSha256 === publishedPin &&
            nonce(binding.runId) && nonce(binding.instance) && !runs.has(binding.runId) &&
            ['qualifiedIdentitySha256', 'dependenciesSha256', 'nodeSdkSha256', 'coreSha256', 'coreManifestSha256'].every(field => digest(binding[field])) &&
            ['nodeSdkSha256', 'coreSha256', 'coreManifestSha256'].every(field => binding[field] === upgrade[field]) &&
            nonce(selection.preparationNonce) && !preparations.has(selection.preparationNonce), 'Composite actual instance/pair binding mismatch');
        runs.add(binding.runId); preparations.add(selection.preparationNonce);
        // The collector re-reads preparation, qualified identity, frozen dependencies, and exact selected argv.
        check(typeof options.collectExpected === 'function', 'Fresh owned collector not integrated');
        const expected = options.collectExpected(selection, selector, context, plan);
        check(equal(expected.binding, binding) && expected.preparationNonce === selection.preparationNonce &&
            equal(expected.selector, selector) && equal(expected.plan, plan), 'Owned producer disagrees with composite binding');
        const result = validateSelection(selection, expected, expected.directory, options.verifyNative);
        for (const row of selection.android) {
            check(!executions.has(row.executionNonce), 'Reused native execution'); executions.add(row.executionNonce);
        }
        // Preserve existing lifecycle success semantics; the failed JVM child is nested under a successful composite command.
        const cleanup = cleanupProof(expected.owned, binding.runId, binding.instance);
        check(equal(selection.cleanup, cleanup), 'Composite cleanup differs from actual lifecycle');
        results.push({ mode: selector.mode, route: selector.route, ...result });
    }
    return { status: 'passed', acceptance, selections: results };
}

module.exports = { acceptance, publishedPin, executionSources, artifact, classifyJvm, validateSelection, validateComposite };
