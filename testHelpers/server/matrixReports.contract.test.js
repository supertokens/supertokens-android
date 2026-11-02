const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const test = require('node:test');

function groovyJar() {
    const root = path.join(process.env.GRADLE_USER_HOME || path.join(os.homedir(), '.gradle'),
        'wrapper/dists/gradle-6.5-all');
    if (!fs.existsSync(root)) throw new Error('Gradle 6.5 distribution required for Groovy reporting contract');
    for (const hash of fs.readdirSync(root)) {
        const jar = path.join(root, hash, 'gradle-6.5/lib/groovy-all-1.3-2.5.11.jar');
        if (fs.existsSync(jar)) return jar;
    }
    throw new Error('Gradle 6.5 Groovy runtime unavailable');
}

test('testapp progress stays bound to its invocation and leaves interrupted tests active', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'matrix-report-contract-'));
    try {
        const reports = path.join(root, 'reports');
        fs.mkdirSync(reports);
        const invocation = { nonce: 'a'.repeat(48), mode: 'retrofit', sources: 'b'.repeat(64), progressScriptSha256: 'c'.repeat(64) };
        fs.writeFileSync(path.join(root, 'invocation.json'), JSON.stringify(invocation));
        const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java';
        const result = spawnSync(java, ['-cp', groovyJar(), 'groovy.ui.GroovyMain',
            path.join(__dirname, 'matrixReports.contract.groovy'), path.join(__dirname, 'matrixReports.gradle')],
        { env: { ...process.env, ST_MATRIX_REPORTS: '', ST_TEST_REPORT_DIR: reports }, encoding: 'utf8' });
        assert.equal(result.status, 43, result.stderr);
        const progressDir = path.join(reports, 'progress');
        const files = fs.readdirSync(progressDir);
        assert.equal(files.length, 1);
        assert.ok(!files[0].startsWith('.'));
        const events = fs.readFileSync(path.join(progressDir, files[0]), 'utf8').trim().split('\n').map(JSON.parse);
        assert.deepEqual(events.map(event => event.event), ['start', 'finish', 'start']);
        assert.deepEqual(events.map(event => event.sequence), [1, 2, 3]);
        assert.equal(events[0].test, events[1].test);
        assert.notEqual(events[1].test, events[2].test);
        assert.equal(events[2].method, 'refresh');
        assert.equal(events[2].transportHint, 'okhttp');
        assert.equal(events[1].result, 'FAILURE');
        assert.deepEqual(events[2].binding, { kind: 'testapp', invocation: invocation.nonce,
            profile: invocation.mode, sources: invocation.sources, progressScriptSha256: invocation.progressScriptSha256 });
        assert.ok(events.every(event => event.timestamp && Number.isFinite(event.monotonicNanos)));
        fs.writeFileSync(path.join(root, 'invocation.json'), JSON.stringify({ ...invocation, sources: 'invalid' }));
        const invalid = spawnSync(java, ['-cp', groovyJar(), 'groovy.ui.GroovyMain',
            path.join(__dirname, 'matrixReports.contract.groovy'), path.join(__dirname, 'matrixReports.gradle')],
        { env: { ...process.env, ST_MATRIX_REPORTS: '', ST_TEST_REPORT_DIR: reports }, encoding: 'utf8' });
        assert.notEqual(invalid.status, 0);
        assert.match(invalid.stderr, /Invalid test progress invocation binding/);
        assert.deepEqual(fs.readdirSync(progressDir), files);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});
