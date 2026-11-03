const fs = require('fs');
const path = require('path');
const os = require('os');
const assert = require('assert');
const crypto = require('crypto');

async function exercise(fault) {
    const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'owned-completion-'));
    try {
        const source = path.join(temporary, 'source'), server = path.join(temporary, 'server'), output = path.join(temporary, 'output');
        for (const folder of [source, server, output, path.join(source, 'core'), path.join(source, 'plugin-interface'), path.join(server, 'node_modules')]) {
            fs.mkdirSync(folder, { recursive: true });
        }
        fs.writeFileSync(path.join(source, 'version.yaml'), 'core_version: 99.0.0\n');
        fs.writeFileSync(path.join(server, 'index.js'), '// never executed\n');
        fs.writeFileSync(path.join(server, 'refreshObservation.js'), '// observer\n');
        const events = [], handlers = {};
        const fakeProcess = { execPath: process.execPath, argv: ['node', 'runOrdinary.js', source, output, '18081', '19001', 'fake-command'],
            env: {}, cwd: () => temporary, on: (name, fn) => { handlers[name] = fn; } };
        const fail = name => { if (fault === name || (name === 'core-cleanup' && fault === 'cleanup-and-source')) throw new Error(name); };
        const sourceFault = ['startup-and-source', 'cleanup-and-source'].includes(fault);
        const copyFault = ['command-and-copy', 'command-throw-and-copy', 'command-copy-diagnostic-fail'].includes(fault);
        class Backend {
            async start() { this.child = { pid: 123, exitCode: null, signalCode: null }; this.fingerprint = 'fake-owner'; this.launch = { cwd: server }; events.push('backend-start'); }
            verifyDetachedGroup() { if (fault === 'startup-and-source') { fs.writeFileSync(path.join(server, 'index.js'), 'changed'); throw new Error('startup-primary'); } }
            async stop() { events.push('backend-stop'); fail('backend-stop'); }
        }
        class Command {
            constructor() { this.done = Promise.resolve(['command', 'command-and-copy', 'command-copy-diagnostic-fail'].includes(fault) ? 7 : 0); }
            async start() { events.push('command'); if (fault === 'interrupted') handlers.SIGTERM(); if (fault === 'command-throw-and-copy') throw new Error('command-primary'); }
            async drain() { events.push('drain'); fail('drain');
                if (fault === 'source-change' || sourceFault) fs.writeFileSync(path.join(server, 'index.js'), 'changed');
                if (fault === 'copy-change' || copyFault) { const owned = path.join(output, fs.readdirSync(output)[0]); fs.writeFileSync(path.join(owned, 'backend', 'index.js'), 'changed'); }
            }
        }
        const dependencies = 'a'.repeat(64);
        const load = name => {
            if (name === 'fs' && fault === 'command-copy-diagnostic-fail') return { ...fs, writeFileSync(file, ...args) { if (String(file).endsWith('backend-source-mismatch.json')) throw new Error('diagnostic-write-failed'); return fs.writeFileSync(file, ...args); } };
            if (name === './ownedProcess') return { OwnedProcess: Backend };
            if (name === './ownedCommandGroup') return { OwnedCommandGroup: Command };
            if (name === './ordinaryEnvironment') return {
                environment: () => ({}), snapshotDependencies: (_, target) => { fs.mkdirSync(target); return dependencies; },
                digestTree: () => fault === 'dependencies' ? 'b'.repeat(64) : dependencies,
            };
            if (name === './ownedCore') return { unusedPort: async () => {} };
            if (name === './ownedBackendRequest') return { ownedBackendRequest: async (_, __, ___, route) => {
                if (route === '/after') { events.push('core-cleanup'); fail('core-cleanup'); }
            } };
            return require(name);
        };
        const script = fs.readFileSync(path.join(__dirname, 'runOrdinary.js'), 'utf8')
            .replace(/main\(\)\.catch\(error => \{ console\.error\(error\); process\.exitCode = 1; \}\);\s*$/, 'return main();');
        let failed = false, failure;
        try { await new Function('require', 'process', '__dirname', 'console', script)(load, fakeProcess, server, { log() {} }); }
        catch (error) { failed = true; failure = error; }
        const owned = path.join(output, fs.readdirSync(output)[0]);
        const receipt = path.join(owned, 'lifecycle-completed.json');
        if (fault === 'startup-and-source') assert.match(failure.message, /startup-primary/);
        if (fault === 'command-throw-and-copy') assert.match(failure.message, /command-primary/);
        if (fault === 'cleanup-and-source') assert.match(failure.message, /Cleanup not acknowledged/);
        if (['command-and-copy', 'command-copy-diagnostic-fail'].includes(fault)) assert.equal(fakeProcess.exitCode, 7);
        if (['startup-and-source', 'command-and-copy', 'command-throw-and-copy', 'cleanup-and-source'].includes(fault)) {
            assert.deepStrictEqual(JSON.parse(fs.readFileSync(path.join(owned, 'backend-source-mismatch.json'))), { code: 'BACKEND_SOURCE_MISMATCH' });
        }
        if (fault === 'command-copy-diagnostic-fail') assert(!fs.existsSync(path.join(owned, 'backend-source-mismatch.json')));
        if (fault === 'cleanup-and-source') assert(fs.existsSync(path.join(owned, 'RETAINED.json')) && !events.includes('backend-stop'));
        if (['startup-and-source', 'command-and-copy', 'command-throw-and-copy', 'command-copy-diagnostic-fail'].includes(fault)) assert(events.includes('backend-stop'));

        if (fault) {
            assert(failed || fakeProcess.exitCode !== 0, 'Fault must fail the owned run');
            assert(!fs.existsSync(receipt), 'Fault cannot issue successful completion: ' + fault);
            if (fault === 'source-change' || fault === 'copy-change') assert(events.includes('core-cleanup') && events.includes('backend-stop'));
        } else {
            assert(!failed);
            assert.strictEqual(fakeProcess.exitCode, 0);
            assert.deepStrictEqual(events, ['backend-start', 'command', 'drain', 'core-cleanup', 'backend-stop']);
            const observed = JSON.parse(fs.readFileSync(receipt));
            const owner = JSON.parse(fs.readFileSync(path.join(owned, 'owner.json')));
            assert.strictEqual(observed.runId, owner.runId);
            assert.strictEqual(observed.exitCode, 0);
            assert.strictEqual(observed.dependenciesSha256, dependencies);
            assert.match(observed.backendSourceSha256, /^[a-f0-9]{64}$/);
            assert.strictEqual(observed.lifecycleSha256, crypto.createHash('sha256').update(fs.readFileSync(path.join(owned, 'process-tree.jsonl'))).digest('hex'));
        }
    } finally { fs.rmSync(temporary, { recursive: true, force: true }); }
}
(async () => {
    for (const fault of [null, 'command', 'drain', 'core-cleanup', 'backend-stop', 'dependencies', 'interrupted', 'source-change', 'copy-change', 'startup-and-source', 'command-and-copy', 'command-throw-and-copy', 'cleanup-and-source', 'command-copy-diagnostic-fail']) await exercise(fault);
    console.log('PASS: actual owned supervisor emits completion only after successful command/drain/Core/backend/dependency checks; six failure paths reject (all external effects mocked)');
})().catch(error => { console.error(error); process.exitCode = 1; });
