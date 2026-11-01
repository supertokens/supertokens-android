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
        const events = [], handlers = {};
        const fakeProcess = { execPath: process.execPath, argv: ['node', 'runOrdinary.js', source, output, '18081', '19001', 'fake-command'],
            env: {}, cwd: () => temporary, on: (name, fn) => { handlers[name] = fn; } };
        const fail = name => { if (fault === name) throw new Error(name); };
        class Backend {
            async start() { this.child = { pid: 123, exitCode: null, signalCode: null }; this.fingerprint = 'fake-owner'; events.push('backend-start'); }
            verifyDetachedGroup() {}
            async stop() { events.push('backend-stop'); fail('backend-stop'); }
        }
        class Command {
            constructor() { this.done = Promise.resolve(fault === 'command' ? 7 : 0); }
            async start() { events.push('command'); if (fault === 'interrupted') handlers.SIGTERM(); }
            async drain() { events.push('drain'); fail('drain'); }
        }
        const dependencies = 'a'.repeat(64);
        const load = name => {
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
        let failed = false;
        try { await new Function('require', 'process', '__dirname', 'console', script)(load, fakeProcess, server, { log() {} }); }
        catch (_) { failed = true; }
        const owned = path.join(output, fs.readdirSync(output)[0]);
        const receipt = path.join(owned, 'lifecycle-completed.json');
        if (fault) {
            assert(failed || fakeProcess.exitCode !== 0, 'Fault must fail the owned run');
            assert(!fs.existsSync(receipt), 'Fault cannot issue successful completion: ' + fault);
        } else {
            assert(!failed);
            assert.strictEqual(fakeProcess.exitCode, 0);
            assert.deepStrictEqual(events, ['backend-start', 'command', 'drain', 'core-cleanup', 'backend-stop']);
            const observed = JSON.parse(fs.readFileSync(receipt));
            const owner = JSON.parse(fs.readFileSync(path.join(owned, 'owner.json')));
            assert.strictEqual(observed.runId, owner.runId);
            assert.strictEqual(observed.exitCode, 0);
            assert.strictEqual(observed.dependenciesSha256, dependencies);
            assert.strictEqual(observed.lifecycleSha256, crypto.createHash('sha256').update(fs.readFileSync(path.join(owned, 'process-tree.jsonl'))).digest('hex'));
        }
    } finally { fs.rmSync(temporary, { recursive: true, force: true }); }
}
(async () => {
    for (const fault of [null, 'command', 'drain', 'core-cleanup', 'backend-stop', 'dependencies', 'interrupted']) await exercise(fault);
    console.log('PASS: actual owned supervisor emits completion only after successful command/drain/Core/backend/dependency checks; six failure paths reject (all external effects mocked)');
})().catch(error => { console.error(error); process.exitCode = 1; });
