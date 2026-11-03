const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { hash, cleanupProof } = require('./integrationEvidence');
const { selectors, flows, key, names } = require('./upgradeEvidence');
const { acceptance, publishedPin, executionSources, artifact, classifyJvm, validateComposite } = require('./nativeUpgradeEvidence');
const stage = path.resolve(__dirname, '..');
const h = digit => digit.repeat(64), n = digit => digit.repeat(48);

function fixture(t, setup = () => {}, overrides = {}) {
    const root = fs.mkdtempSync(path.join(stage, 'host-fixture-'));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    setup(root);
    const context = { schema: 1, sha: 'a'.repeat(40), workflow: 'synthetic-host-test', attempt: 1, nonce: n('b'),
        fdi: '1.16', policySha256: h('c'), sources: h('d'), backend: 'http://127.0.0.1:18081', core: 'http://127.0.0.1:19001', ...overrides };
    // Synthetic reviewed values exercise validation, never supply release policy defaults.
    const plan = { nodeVersion: '16.7.0', coreVersion: '9.3.0', review: { reference: 'synthetic-test-only' },
        phaseVersions: Object.fromEntries(selectors.map(selector => [key(selector),
            { publishedLogin: selector.route === '/login-2.18' ? '2' : '5',
                publishedHandoff: { LOGIN: selector.route === '/login-2.18' ? '2' : '5', REFRESH: '5', SIGN_OUT: 'ABSENT',
                    PAYLOAD_UPDATE: selector.route === '/login-2.18' ? '2' : '5' },
                candidateRefresh: '5' }])) };
    const upgrade = { schema: 2, acceptance, context, planSha256: hash(JSON.stringify(plan)),
        executionSources: executionSources(root), publishedVersion: '0.5.4', publishedAarSha256: publishedPin,
        candidateAarSha256: h('e'), nodeSdkSha256: h('3'), coreSha256: h('4'), coreManifestSha256: h('5'), selections: [] };
    const expectedByKey = new Map();
    function save(directory, file, value) {
        const bytes = typeof value === 'string' ? value : JSON.stringify(value);
        fs.writeFileSync(path.join(directory, file), bytes);
        return { path: file, sha256: hash(bytes) };
    }
    for (let index = 0; index < selectors.length; index++) {
        const selector = selectors[index], directory = path.join(root, 'receipt-' + index);
        fs.mkdirSync(directory);
        const binding = { context, ...selector, sessionJwt: selector.enableJWT, nodeVersion: plan.nodeVersion, coreVersion: plan.coreVersion,
            planSha256: upgrade.planSha256, executionSourcesSha256: upgrade.executionSources.manifestSha256,
            candidateAarSha256: upgrade.candidateAarSha256, publishedAarSha256: publishedPin,
            runId: n(String(index + 1)), instance: n('9'), qualifiedIdentitySha256: h('1'), dependenciesSha256: h('2'),
            nodeSdkSha256: h('3'), coreSha256: h('4'), coreManifestSha256: h('5') };
        const expected = { binding: structuredClone(binding), selector, plan, directory, owned: directory,
            preparationNonce: n(String(index + 5)), startedMs: 1, endedMs: 100, jvmWorkersDrained: true,
            jvmArgv: ['gradlew', '--rerun-tasks', '--no-build-cache', ':app:upgradeLiveTest'] };
        expectedByKey.set(key(selector), expected);
        const failures = ['COOKIE', 'EXPOSED_COOKIE'].includes(selector.mode) ? ['REFRESH', 'SIGN_OUT'] : [];
        const report = { tests: 4, passed: 4 - failures.length, failed: failures.length, skipped: 0, errors: 0, aborted: 0,
            rerun: true, excluded: [], excludes: [], excludedTasks: [] };
        report.cases = names(selector).map((identity, index) => ({ className: 'com.example.example.PublishedUpgradeLiveTest',
            name: identity.slice('com.example.example.PublishedUpgradeLiveTest.'.length), result: failures.includes(flows[index]) ? 'FAILURE' : 'SUCCESS' }));
        const details = failures.map(flow => {
            const method = flow === 'REFRESH' ? 'refresh' : 'signOut';
            const raw = 'java.lang.IllegalArgumentException: Empty cookie header string\n' +
                '\tat java.base/java.net.HttpCookie.parseInternal(HttpCookie.java:828)\n' +
                '\tat com.supertokens.session.SuperTokensHttpURLConnection.manuallySetCookiesFromResponse(SuperTokensHttpURLConnection.java:76)\n' +
                '\tat com.supertokens.session.Legacy054Driver.' + method + '(Legacy054Driver.java:85)\n';
            return { identity: names(selector)[flows.indexOf(flow)], phase: 'published', publishedAarSha256: publishedPin,
                candidateInvoked: false, rootType: 'java.lang.IllegalArgumentException', rootMessage: 'Empty cookie header string',
                frames: ['java.net.HttpCookie.parseInternal', 'com.supertokens.session.SuperTokensHttpURLConnection.manuallySetCookiesFromResponse',
                    'com.supertokens.session.Legacy054Driver.' + method, 'com.supertokens.testing.PublishedUpgradeScenario.run'],
                rawFailure: save(directory, flow + '.failure', raw) };
        });
        function observation(flow) {
            return { flow, binding: structuredClone(binding), preparationNonce: expected.preparationNonce,
                publishedFirst: true, candidateContinued: true, scopedCleanup: true, observed: { schemaVersion: 2,
                    publishedLogin: { status: 'PRESENT', tokenVersion: plan.phaseVersions[key(selector)].publishedLogin }, candidateRefresh: { status: 'PRESENT', tokenVersion: '5' },
                    publishedHandoff: { status: flow === 'SIGN_OUT' ? 'ABSENT' : 'PRESENT',
                        tokenVersion: flow === 'SIGN_OUT' ? null : plan.phaseVersions[key(selector)].publishedHandoff[flow] } } };
        }
        const jvm = { originalReport: save(directory, 'report.json', report), failureDetails: save(directory, 'failures.json', details),
            successfulObservations: flows.filter(flow => !failures.includes(flow)).map(observation) };
        jvm.terminalExecution = save(directory, 'terminal.json', { schema: 1, kind: 'jvm-terminal', binding, nonce: expected.preparationNonce,
            exit: failures.length ? 1 : 0, signal: null, spawnError: null, outcome: failures.length ? 'test-failure' : 'passed',
            javaMajor: 11, javaVersion: 'openjdk version "11.0.32"', argv: expected.jvmArgv, startedMs: 2, endedMs: 10,
            report: jvm.originalReport, failureDetails: jvm.failureDetails, workersDrained: true });
        const android = failures.map((flow, row) => {
            const executionNonce = String(index + 1) + String(row + 1).repeat(47);
            return { ...observation(flow), schema: 2, kind: 'matrix-native-upgrade', mode: selector.mode, route: selector.route,
                sessionJwt: selector.enableJWT, parentPreparationNonce: expected.preparationNonce, executionNonce,
                rowId: selector.mode + ':' + flow + ':' + executionNonce, startedMs: 11 + row * 10, endedMs: 20 + row * 10,
                rawEvidenceSha256: h('f') };
        });
        fs.writeFileSync(path.join(directory, 'owner.json'), JSON.stringify({ root: directory, runId: binding.runId }));
        fs.writeFileSync(path.join(directory, 'dependencies.sha256'), binding.dependenciesSha256);
        const log = ['BEFORE_CORE_CLEANUP', 'CORE_CLEANUP_ACKNOWLEDGED', 'BACKEND_STOPPED'].map(action => JSON.stringify({ action })).join('\n');
        fs.writeFileSync(path.join(directory, 'process-tree.jsonl'), log);
        fs.writeFileSync(path.join(directory, 'lifecycle-completed.json'), JSON.stringify({ schema: 1, root: directory,
            runId: binding.runId, instance: binding.instance, command: ['synthetic-composite'], exitCode: 0,
            lifecycleSha256: hash(log), dependenciesSha256: binding.dependenciesSha256 }));
        upgrade.selections.push({ ...selector, binding, preparationNonce: expected.preparationNonce, jvm, android,
            cleanup: cleanupProof(directory, binding.runId, binding.instance) });
    }
    const options = { root, candidateAarSha256: h('e'), collectExpected: selection => expectedByKey.get(key(selection)),
        verifyNative: row => ({ binding: row.binding, executionNonce: row.executionNonce, rawEvidenceSha256: row.rawEvidenceSha256,
            deviceCleanup: true, workersDrained: true, pendingCreations: 0, ownerReceiptsResolved: true }) };
    const selected = upgrade.selections[1], expected = expectedByKey.get(key(selected));
    function rewrite(reference, change) {
        const value = JSON.parse(fs.readFileSync(path.join(expected.directory, reference.path)));
        change(value);
        Object.assign(reference, save(expected.directory, reference.path, value));
    }
    return { root, upgrade, context, plan, options, selected, expected, rewrite,
        run: () => validateComposite(upgrade, context, plan, options) };
}

test('synthetic exact four alternates preserve failed reports and exit codes', t => {
    const f = fixture(t), before = JSON.stringify(f.upgrade), result = f.run();
    assert.equal(result.status, 'passed');
    assert.equal(result.selections.reduce((sum, item) => sum + item.report.failed, 0), 4);
    assert.equal(result.selections.flatMap(item => item.rowPlatforms).filter(row => row.platform === 'android').length, 4);
    assert.equal(JSON.stringify(f.upgrade), before);
    assert.equal(classifyJvm(f.selected.jvm, f.expected, f.expected.directory).terminal.exit, 1);
});

test('synthetic enabled composite passes receipt and publisher with original JVM failures retained', t => {
    const { spawnSync } = require('node:child_process');
    const plan = { nodeVersion: '16.7.0', coreVersion: '9.3.0', review: { reference: 'synthetic-test-only' },
        phaseVersions: Object.fromEntries(selectors.map(selector => [key(selector), {
            publishedLogin: selector.route === '/login-2.18' ? '2' : '5',
            publishedHandoff: { LOGIN: selector.route === '/login-2.18' ? '2' : '5', REFRESH: '5', SIGN_OUT: 'ABSENT',
                PAYLOAD_UPDATE: selector.route === '/login-2.18' ? '2' : '5' }, candidateRefresh: '5' }])) };
    const { lanes, exactCases } = require('./integrationEvidence');
    const policy = { schema: 1, required: { '4.1': lanes }, upgrade: { '4.1': plan }, upgradeAcceptance: acceptance };
    const text = JSON.stringify(policy);
    const setup = root => {
        fs.mkdirSync(path.join(root, '.circleci'), { recursive: true });
        fs.mkdirSync(path.join(root, 'testHelpers/testapp'), { recursive: true });
        fs.mkdirSync(path.join(root, 'app'), { recursive: true });
        fs.mkdirSync(path.join(root, 'bin'), { recursive: true });
        for (const name of ['integrationReceipt.js', 'integrationEvidence.js', 'upgradeEvidence.js', 'nativeUpgradeEvidence.js', 'markAsSuccess.sh'])
            fs.copyFileSync(path.join(__dirname, name), path.join(root, '.circleci', name));
        for (const name of ['exposureAcceptance.js', 'upgradeHandoff.js'])
            fs.copyFileSync(path.join(stage, 'testHelpers/testapp', name), path.join(root, 'testHelpers/testapp', name));
        fs.writeFileSync(path.join(root, '.circleci/integration-policy.json'), text);
        fs.writeFileSync(path.join(root, 'frontendDriverInterfaceSupported.json'), JSON.stringify({ versions: ['4.1'] }));
        fs.writeFileSync(path.join(root, 'app/build.gradle'), 'publishVersionID = "0.6.0"\n');
        fs.writeFileSync(path.join(root, 'bin/git'), '#!/bin/bash\nprintf "%s\\n" "$CIRCLE_SHA1"\n', { mode: 0o700 });
        fs.writeFileSync(path.join(root, 'bin/jq'), '#!/bin/bash\ncat >/dev/null\necho 0\n', { mode: 0o700 });
        fs.writeFileSync(path.join(root, 'bin/curl'), '#!/bin/bash\nprintf "%s\\n" "$*" >> "$PUBLISH_MARKER"\nprintf 200\n', { mode: 0o700 });
        fs.writeFileSync(path.join(root, 'bin/node'), '#!/bin/bash\nif [ "$1" = integrationReceipt.js ]; then exec "$REAL_NODE" "$1" verify "$TEST_RECEIPTS"; fi\nif [ "$1" = requiredCompatibility.js ]; then exit 0; fi\nexit 99\n', { mode: 0o700 });
        fs.writeFileSync(path.join(root, 'preload.js'), `const Module = require('module'), fs = require('fs');
const original = Module._load;
Module._load = function(request, parent, main) {
    if (request === './publishedNativeUpgradeExport' && parent.filename.endsWith('/integrationEvidence.js')) return {
        validationOptions: (root, context, plan, candidateAarSha256) => ({ root, candidateAarSha256,
            collectExpected: selection => JSON.parse(fs.readFileSync(process.env.EXPECTED))[selection.mode + ':' + selection.route],
            verifyNative: row => ({ binding: row.binding, executionNonce: row.executionNonce, rawEvidenceSha256: row.rawEvidenceSha256,
                deviceCleanup: true, workersDrained: true, pendingCreations: 0, ownerReceiptsResolved: true }) }) };
    return original.apply(this, arguments);
};`);
    };
    const f = fixture(t, setup, { fdi: '4.1', policySha256: hash(text) });
    assert.deepEqual(f.plan, plan);
    const expected = Object.fromEntries(f.upgrade.selections.map(selection => [key(selection), f.options.collectExpected(selection)]));
    fs.writeFileSync(path.join(f.root, 'expected.json'), JSON.stringify(expected));
    const identity = { runId: n('d'), instance: n('e'), backend: f.context.backend, core: f.context.core,
        qualifiedIdentitySha256: h('f'), dependenciesSha256: h('1') };
    const evidence = { schema: 1, context: f.context, identity, candidateAarSha256: f.upgrade.candidateAarSha256,
        capabilities: { enabled: true, compatible: true, qualificationProxy: false, mappedGoogleSession: true, nonPublicTenantSession: true },
        cleanup: { runId: identity.runId, commandSucceeded: true, coreAcknowledged: true, backendStopped: true,
            dependenciesUnchanged: true, lifecycleSha256: h('0') },
        lanes: lanes.map(name => {
            const identities = [...(exactCases[name] || ['example.Test.case'])];
            if (name === 'ordinary' || name === 'retrofit') {
                for (const transport of [false, true]) identities.push('com.example.example.MissingRefreshCookieLiveTest.' +
                    'missingRefreshCookieExplicitRemovalClearsSession[urlConnection=' + transport + ']');
            }
            const report = { tests: identities.length, passed: identities.length, failed: 0, skipped: 0,
                cases: identities.map(full => ({ className: full.slice(0, full.lastIndexOf('.')), name: full.slice(full.lastIndexOf('.') + 1), result: 'SUCCESS' })) };
            return { name, status: 'passed', execution: { exit: 0, argv: name === 'node-core' ? ['npm', 'run', 'test:core'] : ['synthetic', name], sourceSha256: h('2') },
                reports: [report], ...(name === 'published-upgrade' ? { upgrade: f.upgrade } : {}),
                ...(name === 'ordinary' ? { exposedReport: { tests: 3, passed: 3, failed: 0, skipped: 0,
                    cases: exactCases.exposed.map(full => ({ className: full.slice(0, full.lastIndexOf('.')), name: full.slice(full.lastIndexOf('.') + 1), result: 'SUCCESS' })) } } : {}),
                ...(name === 'proxy-qualification' ? { finalState: { owners: { fixtures: [], unresolvedCreations: 0 }, state: {
                    active: 0, upstreamActive: 0, ownedFixtures: 0, ownerReservations: 0, unresolvedCreations: 0, gates: {} } } } : {}) };
        }) };
    const receipts = path.join(f.root, 'receipts'), marker = path.join(f.root, 'patch-called');
    const input = path.join(f.root, 'evidence.json');
    fs.writeFileSync(input, JSON.stringify(evidence));
    const env = { ...process.env, NODE_OPTIONS: '--require=' + path.join(f.root, 'preload.js'), EXPECTED: path.join(f.root, 'expected.json'),
        PATH: path.join(f.root, 'bin') + path.delimiter + process.env.PATH, REAL_NODE: process.execPath, TEST_RECEIPTS: receipts,
        CIRCLE_SHA1: f.context.sha, CIRCLE_WORKFLOW_ID: f.context.workflow, SUPERTOKENS_API_KEY: 'fake', PUBLISH_MARKER: marker };
    const write = spawnSync(process.execPath, ['-e', `const fs=require('fs'), p=require('path'), r=process.argv[1], e=JSON.parse(fs.readFileSync(process.argv[2]));
        const receipt=require(p.join(r,'.circleci/integrationReceipt.js')), policy=require(p.join(r,'.circleci/integrationEvidence.js')).config(r);
        receipt.writeReceipt(e.context,e,policy,process.argv[3]);`, f.root, input, receipts], { env, encoding: 'utf8' });
    assert.strictEqual(write.status, 0, write.stderr);
    const written = JSON.parse(fs.readFileSync(path.join(receipts, '4.1.json')));
    const selections = written.evidence.lanes.find(lane => lane.name === 'published-upgrade').upgrade.selections;
    for (const selection of selections.filter(value => value.android.length)) {
        const original = JSON.parse(fs.readFileSync(path.join(expected[key(selection)].directory, selection.jvm.originalReport.path)));
        const terminal = JSON.parse(fs.readFileSync(path.join(expected[key(selection)].directory, selection.jvm.terminalExecution.path)));
        assert.equal(original.failed, 2);
        assert.equal(terminal.exit, 1);
    }
    const publish = spawnSync('bash', [path.join(f.root, '.circleci/markAsSuccess.sh')], { cwd: f.root, env, encoding: 'utf8', timeout: 5000 });
    assert.strictEqual(publish.status, 0, publish.stderr);
    assert.match(fs.readFileSync(marker, 'utf8'), /-X PATCH/);
});

const mutations = {
    'no native producer': f => delete f.options.verifyNative,
    'no fresh owned collector': f => delete f.options.collectExpected,
    'four failures alone': f => f.selected.android.splice(0),
    'old API34 archive': f => f.selected.android[0].kind = 'archived-build',
    'arbitrary flow': f => f.selected.android[0].flow = 'LOGIN',
    'wrong route': f => f.selected.android[0].route = '/login-2.18',
    'wrong mode': f => f.selected.android[0].mode = 'HEADER',
    'duplicate row': f => f.selected.android[1] = structuredClone(f.selected.android[0]),
    'parallel native execution': f => f.selected.android[1].startedMs = 15,
    'parent nonce': f => f.selected.android[0].parentPreparationNonce = n('0'),
    'changed instance': f => f.selected.android[0].binding.instance = n('0'),
    'changed dependencies': f => f.selected.android[0].binding.dependenciesSha256 = h('0'),
    'changed core manifest': f => f.selected.android[0].binding.coreManifestSha256 = h('0'),
    'wrong actual pair': f => f.selected.binding.coreVersion = '1.0.0',
    'different resolved core across selectors': f => f.selected.binding.coreSha256 = h('0'),
    'wrong candidate': f => f.upgrade.candidateAarSha256 = h('0'),
    'wrong published pin': f => f.upgrade.publishedAarSha256 = h('0'),
    'unreviewed plan': f => delete f.plan.review,
    'null plan': f => f.plan.phaseVersions = null,
    'scalar historical handoff': f => f.plan.phaseVersions['HEADER:/login-2.18'].publishedHandoff = '5',
    'wrong phase': f => f.selected.android[0].observed.candidateRefresh.tokenVersion = '4',
    'unknown token': f => f.selected.android[0].observed.publishedLogin.tokenVersion = '6',
    'signout still present': f => f.selected.android[1].observed.publishedHandoff = { status: 'PRESENT', tokenVersion: '5' },
    'native cleanup failure': f => f.options.verifyNative = () => ({ deviceCleanup: false }),
    'retained lifecycle': f => fs.writeFileSync(path.join(f.expected.owned, 'RETAINED.json'), '{}'),
    'changed source': f => fs.mkdirSync(path.join(f.root, '.circleci')),
    'missing terminal': f => fs.unlinkSync(path.join(f.expected.directory, 'terminal.json')),
    'interrupted terminal': f => fs.writeFileSync(path.join(f.expected.directory, 'terminal.json'), '{'),
    'terminal signal': f => f.rewrite(f.selected.jvm.terminalExecution, terminal => terminal.signal = 'SIGTERM'),
    'fake success receipt': f => f.rewrite(f.selected.jvm.terminalExecution, terminal => terminal.kind = 'completed'),
    'build failure': f => f.rewrite(f.selected.jvm.terminalExecution, terminal => terminal.outcome = 'build-failure'),
    'wrong java': f => f.rewrite(f.selected.jvm.terminalExecution, terminal => terminal.javaMajor = 17),
    'failed raw report relabeled success': f => f.rewrite(f.selected.jvm.originalReport, report => report.cases[1].result = 'SUCCESS'),
    'path escape': f => f.selected.jvm.originalReport.path = '../report.json',
};
for (const [name, mutate] of Object.entries(mutations)) test('reject ' + name, t => {
    const f = fixture(t); assert.doesNotThrow(f.run); mutate(f); assert.throws(f.run);
});
for (const field of ['sha', 'workflow', 'attempt', 'nonce', 'fdi', 'policySha256', 'sources', 'backend', 'core']) {
    test('reject native context drift: ' + field, t => {
        const f = fixture(t); f.selected.android[0].binding.context[field] = 'changed'; assert.throws(f.run);
    });
}

test('artifact reader rejects symlinks even within the receipt tree', t => {
    const f = fixture(t), directory = f.expected.directory;
    fs.symlinkSync('report.json', path.join(directory, 'linked.json'));
    assert.throws(() => artifact(directory, { ...f.selected.jvm.originalReport, path: 'linked.json' }), /symlink/);
});

test('execution manifest covers added device, server, gate and instrumentation inputs', t => {
    const f = fixture(t);
    for (const directory of ['testHelpers/device', 'testHelpers/server', '.circleci', 'testHelpers/upgradeapp']) {
        const before = executionSources(f.root);
        fs.mkdirSync(path.join(f.root, directory), { recursive: true });
        fs.writeFileSync(path.join(f.root, directory, 'new-source'), 'changed');
        assert.notEqual(executionSources(f.root).manifestSha256, before.manifestSha256);
    }
});

for (const [name, mutate] of Object.entries({
    'candidate origin': failure => failure.phase = 'candidate',
    'candidate already invoked': failure => failure.candidateInvoked = true,
    'missing structured stack': failure => failure.frames = [],
    'different exception': failure => failure.rootType = 'java.io.IOException',
})) test('reject authenticated failure details: ' + name, t => {
    const f = fixture(t);
    f.rewrite(f.selected.jvm.failureDetails, failures => mutate(failures[0]));
    f.rewrite(f.selected.jvm.terminalExecution, terminal => terminal.failureDetails = f.selected.jvm.failureDetails);
    assert.throws(f.run, /Wrong structured published failure/);
});

for (const [name, mutate] of Object.entries({
    skip: report => report.skipped = 1,
    error: report => report.errors = 1,
    duplicate: report => report.cases[1] = report.cases[0],
    'extra failure': report => report.cases[0].result = 'FAILURE',
    exclusion: report => report.excludes.push('some-test'),
})) test('reject authenticated report: ' + name, t => {
    const f = fixture(t);
    f.rewrite(f.selected.jvm.originalReport, mutate);
    f.rewrite(f.selected.jvm.terminalExecution, terminal => terminal.report = f.selected.jvm.originalReport);
    assert.throws(f.run, /JVM cases|JVM row|Excluded/);
});

test('strict JVM success classifier accepts historical selector without native substitution', t => {
    const f = fixture(t), historical = f.upgrade.selections[3];
    const expected = f.options.collectExpected(historical);
    assert.deepEqual(classifyJvm(historical.jvm, expected, expected.directory).nativeFlows, []);
});

test('unchanged all-JVM validator path stays strict for all four selectors', t => {
    const f = fixture(t);
    const upgrade = { ...f.context, publishedVersion: '0.5.4', publishedAarSha256: publishedPin,
        candidateAarSha256: h('e'), nodeVersion: f.plan.nodeVersion, coreVersion: f.plan.coreVersion,
        coreSha256: h('4'), nodeSdkSha256: h('3'), selections: f.upgrade.selections.map(selected => {
            const binding = selected.binding;
            return { ...selected, ...binding, backend: f.context.backend, core: f.context.core,
                freshPreparation: true, completed: true, scopedCleanup: true, ownedCleanup: true, cleanupSha256: h('f'),
                preparation: { nonce: selected.preparationNonce, sources: f.context.sources, sha: f.context.sha,
                    candidateAarSha256: h('e'), publishedAarSha256: publishedPin },
                coreCdiVersions: ['2.18'], legacy218Evidence: { status: 'passed', route: selected.route,
                    nodeVersion: f.plan.nodeVersion, fdiVersion: f.context.fdi, runId: binding.runId,
                    preparationNonce: selected.preparationNonce, sources: f.context.sources,
                    qualification: 'published-first-four-flows', coreCdiVersions: ['2.18'] },
                report: { tests: 4, passed: 4, failed: 0, skipped: 0, cases: names(selected).map(identity => ({
                    className: 'com.example.example.PublishedUpgradeLiveTest',
                    name: identity.slice('com.example.example.PublishedUpgradeLiveTest.'.length), result: 'SUCCESS' })) },
                rows: flows.map(flow => ({ flow, publishedFirst: true, scopedCleanup: true, candidateContinued: true,
                    publishedLogin: f.plan.phaseVersions[key(selected)].publishedLogin,
                    publishedHandoff: f.plan.phaseVersions[key(selected)].publishedHandoff[flow], candidateRefresh: '5' })) };
        }) };
    assert.deepEqual(validateComposite(upgrade, f.context, f.plan), { status: 'passed', platform: 'jvm' });
    upgrade.selections[1].report.cases[1].result = 'FAILURE';
    assert.throws(() => validateComposite(upgrade, f.context, f.plan), /Wrong upgrade test/);
});
