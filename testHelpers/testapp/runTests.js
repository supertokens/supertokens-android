const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawnSync } = require('child_process');
const { sourceDigest, artifactDigest, artifactHashes, validate } = require('./artifactReceipt');
const diagnosticCases = {
    'diagnostic-retrofit': 'com.example.SuperTokensRetrofitTest.okHttp_testThatMultipleAPICallsInParallelAndOnly1RefreshShouldBeCalled',
    'diagnostic-ordinary': 'com.example.SuperTokensRetrofitTest.retrofit_testThatThingsShouldWorkIfAntiCsrfIsDisabled',
};
const isDiagnostic = mode => Object.prototype.hasOwnProperty.call(diagnosticCases, mode);

function commands(mode, extra, backend, root = fs.realpathSync(path.resolve(__dirname, '../..'))) {
    if (!['ordinary', 'retrofit', 'mutation', 'upgrade', 'exposed'].includes(mode) && !isDiagnostic(mode)) throw new Error('Choose ordinary, retrofit, mutation, upgrade or exposed');
    if (isDiagnostic(mode) ? extra.length !== 1 || !['focused', 'full'].includes(extra[0]) : extra.some(arg => mode !== 'upgrade' || !/^-Pupgrade\.[A-Za-z][A-Za-z0-9]*=.+$/.test(arg))) {
        throw new Error('Only upgrade accepts additional -Pupgrade.* properties; task overrides and exclusions are forbidden');
    }
    if (mode === 'mutation' && (!backend || process.env.CORE_PROTOCOL_TESTS !== '1' || !process.env.ST_TEST_RUN_ID)) {
        throw new Error('Mutation requires runOwnedTests.js mutation with explicit CORE_PROTOCOL_TESTS=1');
    }
    const common = ['--no-daemon', '--no-build-cache', '--rerun-tasks', '--console=plain'];
    const reportScript = fs.realpathSync(path.join(root, 'testHelpers/server/matrixReports.gradle'));
    return [common.concat(':app:preparePublishedUpgradeArtifacts'), common.concat('-I', reportScript,
        mode === 'upgrade' ? ':app:upgradeLiveTest' : ':app:testDebugUnitTest',
        ['retrofit', 'mutation', 'exposed', 'diagnostic-retrofit'].includes(mode) ? ['-PretrofitCompatibility=true'] : [],
        ['ordinary', 'retrofit', 'diagnostic-ordinary', 'diagnostic-retrofit'].includes(mode) ? ['-PstandardIntegrationProfile=true'] : [],
        mode === 'exposed' ? ['--tests', 'com.example.example.ExposedCookieLiveTest'] : [],
        mode === 'mutation' ? ['-PmutationAcceptance=true', '-Pm6BackendUrl=' + backend,
            '--tests', 'com.example.example.BackendMutationCoverageTest'] : [],
        isDiagnostic(mode) ? (extra[0] === 'focused' ? ['--tests', diagnosticCases[mode]] : []) : extra)];
}

function privateEntry(file, directory) {
    const stat = fs.lstatSync(file);
    if (stat.isSymbolicLink() || (directory ? !stat.isDirectory() : !stat.isFile()) ||
        stat.uid !== process.getuid() || (stat.mode & 0o077) !== 0 || fs.realpathSync(file) !== file) {
        throw new Error('Unsafe diagnostic path');
    }
}
function diagnosticPaths(agent, output) {
    if (agent.includes('=') || /[\x00-\x1f\x7f]/.test(agent) || /[\x00-\x1f\x7f]/.test(output)) {
        throw new Error('Ambiguous diagnostic agent or output path');
    }
}
function probe(root) {
    const directory = path.resolve(root, '..', 'probe');
    privateEntry(directory, true);
    const files = ['WorkerWatchdog.java', 'worker-init.gradle', 'worker-agent.jar', 'manifest.json'];
    for (const name of files) privateEntry(path.join(directory, name), false);
    const manifest = JSON.parse(fs.readFileSync(path.join(directory, 'manifest.json'), 'utf8'));
    const hashes = Object.fromEntries(files.slice(0, 3).map(name => [name, crypto.createHash('sha256').update(fs.readFileSync(path.join(directory, name))).digest('hex')]));
    if (Object.keys(hashes).some(name => manifest[name] !== hashes[name])) throw new Error('Diagnostic probe digest mismatch');
    return { directory, hashes, manifestSha256: crypto.createHash('sha256').update(fs.readFileSync(path.join(directory, 'manifest.json'))).digest('hex') };
}
function main() {
    const [mode, ...extra] = process.argv.slice(2);
    const envSource = { ...process.env };
    delete envSource.ST_DIAGNOSTIC_AGENT;
    delete envSource.ST_DIAGNOSTIC_OUTPUT;
    if (['upgrade', 'exposed'].includes(mode) && (!['HEADER', 'COOKIE', 'EXPOSED_COOKIE'].includes(envSource.ST_UPGRADE_MODE) ||
        (mode === 'exposed' && envSource.ST_UPGRADE_MODE !== 'EXPOSED_COOKIE'))) {
        throw new Error('Use runOwnedTests.js upgrade|exposed with explicit owned configuration');
    }
    const port = Number(envSource.NODE_PORT);
    if (!envSource.ST_TEST_RUN_ID || !envSource.INSTALL_PATH || !Number.isInteger(port) || port < 1024 || port > 65535 ||
        [8080, 9000].includes(port) || envSource.ST_TEST_BACKEND_URL !== 'http://127.0.0.1:' + port) {
        throw new Error('Run inside runOrdinary.js with generated owned-instance metadata and URLs; see testHelpers/testapp/RUNNING.md');
    }
    const owner = JSON.parse(fs.readFileSync(path.join(envSource.INSTALL_PATH, 'owner.json'), 'utf8'));
    if (owner.runId !== envSource.ST_TEST_RUN_ID || owner.root !== envSource.INSTALL_PATH) throw new Error('Owned instance receipt mismatch');
    if (mode === 'upgrade' && extra.some(arg => arg.startsWith('-Pupgrade.baseUrl=') && arg !== '-Pupgrade.baseUrl=' + envSource.ST_TEST_BACKEND_URL)) {
        throw new Error('upgrade.baseUrl must match the owned backend URL');
    }
    const root = fs.realpathSync(path.resolve(__dirname, '../..'));
    const plan = commands(mode, extra, process.env.ST_TEST_BACKEND_URL, root);
    const diagnostic = isDiagnostic(mode);
    if (diagnostic) process.umask(0o077);
    const initialProbe = diagnostic ? probe(root) : null;
    if (diagnostic) plan[1].push('-I', path.join(initialProbe.directory, 'worker-init.gradle'));
    const reportScript = plan[1][plan[1].indexOf('-I') + 1];
    const reportScriptDigest = () => crypto.createHash('sha256').update(fs.readFileSync(reportScript)).digest('hex');
    const lock = path.join(root, '.integration-lock');
    const composite = envSource.ST_UPGRADE_COMPOSITE_BINDING && JSON.parse(fs.readFileSync(envSource.ST_UPGRADE_COMPOSITE_BINDING));
    if (composite && mode !== 'upgrade') throw new Error('Composite terminal is upgrade-only');
    const nonce = composite ? envSource.ST_UPGRADE_PREPARATION_NONCE : crypto.randomBytes(24).toString('hex');
    if (composite) {
        if (!/^[a-f0-9]{48}$/.test(nonce || '') || fs.readFileSync(path.join(lock, 'nonce'), 'utf8') !== nonce ||
            fs.readFileSync(path.join(lock, 'owner'), 'utf8') !== envSource.ST_TEST_RUN_ID) throw new Error('Composite build lease mismatch');
    } else {
        try { fs.mkdirSync(lock); } catch (_) { throw new Error('Integration build lane busy: ' + lock + '. If interrupted, prove all workers drained before removing the stale lock.'); }
    }
    try {
    const evidence = path.join(root, 'testHelpers/testapp/app/build', diagnostic ? 'diagnostic-receipts' : 'integration-receipts', nonce);
    fs.mkdirSync(path.dirname(evidence), { recursive: true, mode: 0o700 });
    if (diagnostic) privateEntry(path.dirname(evidence), true);
    fs.mkdirSync(evidence, { mode: 0o700 });
    fs.writeFileSync(path.join(lock, 'nonce'), nonce);
    const receiptFile = path.join(evidence, 'preparation.json');
    const env = { ...envSource, ST_ARTIFACT_NONCE: nonce, ST_ARTIFACT_RECEIPT: receiptFile,
        ST_ARTIFACT_NODE: process.execPath, ST_TEST_REPORT_DIR: path.join(evidence, 'reports') };
    if (diagnostic) delete env.ST_MATRIX_REPORTS;
    let testEnv = env;
    if (diagnostic) {
        privateEntry(envSource.INSTALL_PATH, true);
        const captures = path.join(initialProbe.directory, 'captures');
        fs.mkdirSync(captures, { recursive: true, mode: 0o700 });
        privateEntry(captures, true);
        const capture = path.join(captures, nonce);
        fs.mkdirSync(capture, { mode: 0o700 });
        privateEntry(capture, true);
        const agent = path.join(initialProbe.directory, 'worker-agent.jar');
        diagnosticPaths(agent, capture);
        testEnv = { ...env, ST_DIAGNOSTIC_AGENT: agent, ST_DIAGNOSTIC_OUTPUT: capture };
    }
    let javaVersion = null;
    function run(cwd, args, task) {
        const startedMs = Date.now();
        const logPath = path.join(evidence, task + '-process.log');
        const fd = composite ? fs.openSync(logPath, 'wx', 0o600) : null;
        let result;
        try { result = spawnSync(path.join(cwd, 'gradlew'), args, { cwd, env: task === 'test' ? testEnv : env, stdio: composite ? ['ignore', fd, fd] : 'inherit' }); }
        finally { if (fd !== null) fs.closeSync(fd); }
        if (composite) {
            const terminal = require('./upgradeTerminal').record({ directory: evidence, binding: composite, nonce,
                selector: { mode: envSource.ST_UPGRADE_MODE, route: envSource.ST_UPGRADE_ROUTE || '/login' },
                argv: [path.join(cwd, 'gradlew'), ...args], startedMs, endedMs: Date.now(), result, javaVersion, task,
                log: fs.readFileSync(logPath, 'utf8') });
            if (task === 'test' || result.status !== 0) fs.writeFileSync(path.join(envSource.INSTALL_PATH, 'upgrade-terminal.json'),
                JSON.stringify({ schema: 1, kind: 'jvm-terminal-pointer', nonce, directory: evidence, ...terminal,
                    runId: envSource.ST_TEST_RUN_ID, instance: envSource.ST_TEST_INSTANCE_ID }), { flag: 'wx', mode: 0o600 });
        }
        if (result.error) throw result.error;
        if (result.status !== 0) {
            const error = new Error('Gradle failed: ' + (result.signal || result.status));
            error.exitCode = Number.isInteger(result.status) && result.status > 0 ? result.status : 1;
            throw error;
        }
    }
        const sources = sourceDigest(root);
        const progressScriptSha256 = reportScriptDigest();
        const integrity = (stage, failure, expectedArtifacts) => {
            const mismatches = [];
            const check = (name, fn) => { try { if (!fn()) mismatches.push(name); } catch (_) { mismatches.push(name); } };
            check('sources', () => sourceDigest(root) === sources);
            check('report-script', () => reportScriptDigest() === progressScriptSha256);
            if (diagnostic) check('probe', () => JSON.stringify(probe(root)) === JSON.stringify(initialProbe));
            if (expectedArtifacts) check('artifacts', () => artifactDigest(root) === expectedArtifacts);
            if (mismatches.length) {
                try { fs.writeFileSync(path.join(evidence, 'integrity-' + stage + '.json'), JSON.stringify({ stage, mismatches }), { mode: 0o600 }); }
                catch (_) { try { console.error('Could not record integrity mismatch'); } catch (_) {} }
            }
            if (failure) throw failure;
            if (mismatches.length) throw new Error('Integrity mismatch after ' + stage + ': ' + mismatches.join(', '));
        };
        const started = new Date().toISOString();
        fs.writeFileSync(path.join(evidence, 'invocation.json'), JSON.stringify({ root, nonce, mode, started, sources, progressScriptSha256, commands: plan, ...(diagnostic ? { diagnosticOnly: true, selection: extra[0], probe: initialProbe } : {}) }, null, 2));
        let prepareFailure;
        try { run(root, plan[0], 'prepare'); } catch (error) { prepareFailure = error; }
        if (diagnostic && prepareFailure) integrity('prepare', prepareFailure);
        if (prepareFailure) throw prepareFailure;
        if (diagnostic) integrity('prepare');
        if (sourceDigest(root) !== sources) throw new Error('Sources changed during artifact preparation');
        const java = spawnSync(process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java', ['-version'], { env, encoding: 'utf8' });
        if (java.status !== 0) throw new Error('Cannot record Java version');
        javaVersion = java.stderr;
        const receipt = { schema: 1, root, nonce, mode, started, prepared: new Date().toISOString(), sources,
            ...(diagnostic ? { diagnosticOnly: true, selection: extra[0], probe: initialProbe } : {}),
            ...(mode === 'exposed' ? { runId: envSource.ST_TEST_RUN_ID, instance: envSource.ST_TEST_INSTANCE_ID,
                exposureConfigSha256: crypto.createHash('sha256').update(fs.readFileSync(path.join(envSource.INSTALL_PATH, 'exposure-config.json'))).digest('hex') } : {}),
            artifacts: artifactDigest(root), artifactHashes: artifactHashes(root),
            versionSources: Object.fromEntries(['app/build.gradle', 'build.gradle', 'gradle/wrapper/gradle-wrapper.properties',
                'testHelpers/testapp/build.gradle', 'testHelpers/testapp/gradle/wrapper/gradle-wrapper.properties']
                .map(file => [file, fs.readFileSync(path.join(root, file), 'utf8')])),
            javaHome: process.env.JAVA_HOME || '', javaVersion: java.stderr,
            nodeVersion: process.version, commands: plan };
        fs.writeFileSync(receiptFile, JSON.stringify(receipt, null, 2));
        validate(root, env);
        if (composite && (composite.context.sources !== sources ||
            composite.candidateAarSha256 !== receipt.artifactHashes['app/build/outputs/aar/app-debug.aar'] ||
            composite.publishedAarSha256 !== fs.readFileSync(path.join(root, 'app/src/test/compatibility/legacy054.sha256'), 'utf8').trim())) {
            throw new Error('Composite preparation source/artifact binding changed');
        }
        if (reportScriptDigest() !== progressScriptSha256) throw new Error('Progress report script changed during preparation');
        if (diagnostic && JSON.stringify(probe(root)) !== JSON.stringify(initialProbe)) throw new Error('Diagnostic probe changed during preparation');
        let testFailure;
        try { run(__dirname, plan[1], 'test'); } catch (error) { testFailure = error; }
        if (diagnostic) integrity('test', testFailure, receipt.artifacts);
        if (testFailure) throw testFailure;
        if (reportScriptDigest() !== progressScriptSha256) throw new Error('Progress report script changed during execution');
        if (diagnostic && extra[0] === 'focused') {
            const report = JSON.parse(fs.readFileSync(path.join(env.ST_TEST_REPORT_DIR, 'testDebugUnitTest.json'), 'utf8'));
            const matches = report.cases.filter(test => test.className + '.' + test.name === diagnosticCases[mode]);
            if (report.tests !== 1 || report.cases.length !== 1 || matches.length !== 1 || matches[0].result !== 'SUCCESS' || report.skipped || report.failed) {
                throw new Error('Diagnostic focused result inconclusive: expected one successful target');
            }
        }
        if (['upgrade', 'exposed'].includes(mode)) {
            require('./exposureAcceptance').accept(mode, process.env.ST_UPGRADE_MODE,
                JSON.parse(fs.readFileSync(path.join(env.ST_TEST_REPORT_DIR,
                    mode === 'upgrade' ? 'upgradeLiveTest.json' : 'testDebugUnitTest.json'), 'utf8')), envSource.ST_UPGRADE_ROUTE || '/login');
        }
        if (mode === 'mutation') {
            require('./mutationAcceptance').accept(JSON.parse(fs.readFileSync(path.join(env.ST_TEST_REPORT_DIR, 'testDebugUnitTest.json'), 'utf8')));
        }
        if (mode === 'exposed' && receipt.exposureConfigSha256 !== crypto.createHash('sha256')
            .update(fs.readFileSync(path.join(envSource.INSTALL_PATH, 'exposure-config.json'))).digest('hex')) {
            throw new Error('Exposure configuration changed during execution');
        }
        validate(root, env);
        fs.writeFileSync(path.join(evidence, diagnostic ? 'diagnostic-finished.json' : 'completed.json'), JSON.stringify({ nonce, completed: new Date().toISOString(), ...(diagnostic ? { diagnosticOnly: true } : {}) }));
        if (mode === 'upgrade') {
            fs.writeFileSync(path.join(envSource.INSTALL_PATH, 'upgrade-completed.json'), JSON.stringify({
                schema: 1, runId: envSource.ST_TEST_RUN_ID, instance: envSource.ST_TEST_INSTANCE_ID, nonce, directory: evidence,
                sources, mode: envSource.ST_UPGRADE_MODE, route: envSource.ST_UPGRADE_ROUTE || '/login', backend: envSource.ST_TEST_BACKEND_URL,
            }), { flag: 'wx' });
        }
        console.log('Fresh build/test receipts: ' + evidence);
    } finally {
        if (!composite) fs.rmSync(lock, { recursive: true });
    }
    if (mode === 'retrofit' && envSource.CORE_PROTOCOL_TESTS === '1') {
        const result = spawnSync(process.execPath, [path.join(root, 'testHelpers/server/runOwnedTests.js'), 'exposed', root],
            { cwd: root, env: envSource, stdio: 'inherit' });
        if (result.error) throw result.error;
        if (result.status !== 0) throw new Error('Owned exposed-cookie execution failed: ' + (result.signal || result.status));
    }
}
module.exports = { commands, diagnosticPaths };
if (require.main === module) {
    try { main(); } catch (error) { console.error(error.message); process.exitCode = error.exitCode || 1; }
}
