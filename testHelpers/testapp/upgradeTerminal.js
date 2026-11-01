const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawnSync } = require('child_process');
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const publishedPin = '2f3b4a94a036d21ced610f9c41707d7e1ccf7c9c0256e47e5686c1c76b8224b9';
function write(directory, name, value) {
    const bytes = typeof value === 'string' || Buffer.isBuffer(value) ? value : JSON.stringify(value, null, 2);
    fs.writeFileSync(path.join(directory, name), bytes, { flag: 'wx', mode: 0o600 });
    return { path: name, sha256: hash(bytes) };
}
function parse(xml, summary, mode, route, nonce) {
    const child = spawnSync('python3', [path.join(__dirname, 'upgradeFailureXml.py')], {
        input: JSON.stringify({ xml, summary, mode, route, nonce }), encoding: 'utf8', maxBuffer: 8 * 1024 * 1024,
        timeout: 30000,
    });
    if (child.error || child.status !== 0) throw new Error('JVM XML validation failed: ' + (child.error || child.stderr));
    return JSON.parse(child.stdout);
}
function extract(directory, selector, nonce, publishedAarSha256, originals) {
    if (publishedAarSha256 !== publishedPin) throw new Error('Published pin changed');
    const xmlRoot = path.join(directory, 'reports/xml/upgradeLiveTest');
    const files = fs.readdirSync(xmlRoot).filter(name => name.endsWith('.xml'));
    if (files.length !== 1 || files[0] !== 'TEST-com.example.example.PublishedUpgradeLiveTest.xml') throw new Error('Unexpected JUnit suite set');
    const xmlBytes = fs.readFileSync(path.join(directory, originals.xml.path));
    const summaryBytes = fs.readFileSync(path.join(directory, originals.summary.path));
    const parsed = parse(xmlBytes.toString(), JSON.parse(summaryBytes), selector.mode, selector.route, nonce);
    const details = parsed.failures.map((failure, index) => {
        const { raw, ...facts } = failure;
        return { ...facts, publishedAarSha256, rawFailure: write(directory, 'failure-' + index + '.txt', raw) };
    });
    return { originalReport: write(directory, 'validated-report.json', parsed.report),
        failureDetails: write(directory, 'failure-details.json', details), ...originals };
}
function record({ directory, binding, nonce, selector, argv, startedMs, endedMs, result, javaVersion, task, log }) {
    let evidence, validationError = null;
    const originals = {};
    for (const [field, input, output] of [
        ['xml', 'reports/xml/upgradeLiveTest/TEST-com.example.example.PublishedUpgradeLiveTest.xml', 'original-junit.xml'],
        ['summary', 'reports/upgradeLiveTest.json', 'original-gradle-report.json'],
    ]) {
        const file = path.join(directory, input);
        if (task === 'test' && fs.existsSync(file)) originals[field] = write(directory, output, fs.readFileSync(file));
    }
    try {
        if (task !== 'test' || result.error || result.signal || ![0, 1].includes(result.status)) throw new Error('Non-test terminal outcome');
        evidence = extract(directory, selector, nonce, binding.publishedAarSha256, originals);
        const report = JSON.parse(fs.readFileSync(path.join(directory, evidence.originalReport.path)));
        if ((result.status === 0) !== (report.failed === 0)) throw new Error('Exit/report mismatch');
        if (result.status === 1) {
            const failedTasks = [...log.matchAll(/^Execution failed for task '([^']+)'\./gm)].map(match => match[1]);
            if (JSON.stringify(failedTasks) !== JSON.stringify([':app:upgradeLiveTest']) ||
                !log.includes('There were failing tests.')) throw new Error('Build failure or ambiguous Gradle termination');
        }
    } catch (error) { validationError = error.message; }
    const terminal = { schema: 1, kind: 'jvm-terminal', binding, nonce, task, argv, startedMs, endedMs,
        exit: result.status, signal: result.signal || null, spawnError: result.error ? result.error.message : null,
        javaVersion, javaMajor: /^openjdk version "11\./m.test(javaVersion || '') ? 11 : null,
        outcome: validationError ? 'unclassified' : result.status === 0 ? 'passed' : 'test-failure', validationError,
        report: evidence ? evidence.originalReport : null, failureDetails: evidence ? evidence.failureDetails : null,
        xml: originals.xml || null, summary: originals.summary || null,
        log: write(directory, task + '-terminal.log', log) };
    const terminalExecution = write(directory, task + '-terminal.json', terminal);
    return { ...evidence, terminalExecution };
}

module.exports = { parse, extract, record, write, hash };
