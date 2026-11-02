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

test('bounded progress is private, crash-persistent and never masks test outcomes', () => {
    const parent = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'matrix-report-contract-')));
    try {
        const root = path.join(parent, 'a'.repeat(48));
        const reports = path.join(root, 'reports');
        fs.mkdirSync(reports, { recursive: true });
        const invocation = { nonce: 'a'.repeat(48), mode: 'retrofit', sources: 'b'.repeat(64), progressScriptSha256: 'c'.repeat(64) };
        fs.writeFileSync(path.join(root, 'invocation.json'), JSON.stringify(invocation));
        const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java';
        const run = mode => spawnSync(java, ['-cp', groovyJar(), 'groovy.ui.GroovyMain',
            path.join(__dirname, 'matrixReports.contract.groovy'), path.join(__dirname, 'matrixReports.gradle'), mode],
        { env: { ...process.env, ST_MATRIX_REPORTS: '', ST_TEST_REPORT_DIR: reports, ST_ARTIFACT_NONCE: invocation.nonce }, encoding: 'utf8' });
        const result = run('normal');
        assert.equal(result.status, 43, result.stderr);
        assert(!result.stdout.includes('SECRETtoken') && !result.stderr.includes('SECRETtoken'));
        const progressDir = path.join(reports, 'progress');
        const files = fs.readdirSync(progressDir);
        assert.equal(files.length, 2);
        assert.ok(files.every(file => !file.startsWith('.')));
        const history = files.find(file => file.endsWith('.jsonl'));
        const text = fs.readFileSync(path.join(progressDir, history), 'utf8');
        assert(!text.includes('SECRETtoken'));
        const events = text.trim().split('\n').map(JSON.parse);
        assert.deepEqual(events.map(event => event.event), ['start', 'finish', 'start', 'finish', 'start']);
        assert.deepEqual(events.map(event => event.sequence), [1, 2, 3, 4, 5]);
        assert.equal(events[0].test, events[1].test);
        assert.notEqual(events[1].test, events[4].test);
        assert.equal(events[2].method, null);
        assert.equal(events[4].method, 'refresh');
        assert.equal(events[4].transportHint, 'okhttp');
        assert.equal(events[1].result, 'FAILURE');
        assert.deepEqual(events[4].binding, { kind: 'testapp', invocation: invocation.nonce,
            profile: invocation.mode, sources: invocation.sources, progressScriptSha256: invocation.progressScriptSha256 });
        assert.ok(events.every(event => event.timestamp && Number.isFinite(event.monotonicNanos)));
        const snapshot = JSON.parse(fs.readFileSync(path.join(progressDir, files.find(file => file.endsWith('.json'))), 'utf8'));
        assert.deepEqual(snapshot.active.map(item => item.method), ['refresh']);
        fs.rmSync(progressDir, { recursive: true });
        const many = run('many');
        assert.equal(many.status, 43, many.stderr);
        const manyFiles = fs.readdirSync(progressDir);
        const manyHistory = fs.readFileSync(path.join(progressDir, manyFiles.find(file => file.endsWith('.jsonl'))), 'utf8').trim().split('\n').map(JSON.parse);
        assert.equal(manyHistory.length, 10000);
        assert.equal(manyHistory.at(-1).event, 'truncated');
        const latest = JSON.parse(fs.readFileSync(path.join(progressDir, manyFiles.find(file => file.endsWith('.json'))), 'utf8'));
        assert.equal(latest.historyTruncated, true);
        assert.deepEqual(latest.active.map(item => item.method), ['refresh']);
        fs.rmSync(progressDir, { recursive: true });
        fs.writeFileSync(progressDir, 'not a directory');
        const unwritable = run('unwritable');
        assert.equal(unwritable.status, 23, unwritable.stderr);
        assert.equal((unwritable.stderr.match(/test progress unavailable/g) || []).length, 1);
        fs.unlinkSync(progressDir);
        const writeFailure = run('unwritable-after-setup');
        assert.equal(writeFailure.status, 23, writeFailure.stderr);
        assert.equal((writeFailure.stderr.match(/test progress unavailable/g) || []).length, 1);
        fs.unlinkSync(progressDir);
        fs.writeFileSync(path.join(root, 'invocation.json'), JSON.stringify({ ...invocation, sources: 'invalid' }));
        const invalid = run('unwritable');
        assert.equal(invalid.status, 23, invalid.stderr);
        assert.match(invalid.stderr, /test progress unavailable/);
        assert(!fs.existsSync(progressDir));
        fs.writeFileSync(path.join(root, 'invocation.json'), JSON.stringify({ ...invocation, nonce: 'd'.repeat(48) }));
        const wrongNonce = run('unwritable');
        assert.equal(wrongNonce.status, 23, wrongNonce.stderr);
        assert.match(wrongNonce.stderr, /test progress unavailable/);
        assert(!fs.existsSync(progressDir));
    } finally {
        fs.rmSync(parent, { recursive: true, force: true });
    }
});
