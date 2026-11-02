const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { OwnedProcess } = require('./ownedProcess');
const { OwnedCommandGroup } = require('./ownedCommandGroup');
const { ownedBackendRequest } = require('./ownedBackendRequest');
const { environment, snapshotDependencies, digestTree } = require('./ordinaryEnvironment');

async function main() {
    const [source, temporary, backendPortText, corePortText, ...command] = process.argv.slice(2);
    const backendPort = Number(backendPortText);
    const corePort = Number(corePortText);
    if (!source || !temporary || !command.length || backendPort === corePort ||
        [backendPort, corePort].some(port => !Number.isInteger(port) || port < 1024 || port > 65535 || [8080, 9000].includes(port))) {
        throw new Error('Usage: node runOrdinary.js CORE_SOURCE TEMP_DIR BACKEND_PORT CORE_PORT COMMAND [ARGS...]');
    }
    const root = fs.mkdtempSync(path.join(fs.realpathSync(temporary), 'ordinary-owned-'));
    const runId = crypto.randomBytes(24).toString('hex');
    fs.writeFileSync(path.join(root, 'owner.json'), JSON.stringify({ root, runId }), { mode: 0o600 });
    fs.copyFileSync(path.join(source, 'version.yaml'), path.join(root, 'version.yaml'));
    const serverDirectory = path.join(root, 'backend');
    fs.mkdirSync(serverDirectory);
    for (const name of fs.readdirSync(__dirname)) {
        if (!name.startsWith('.') && /\.(js|json|html)$/.test(name) && fs.statSync(path.join(__dirname, name)).isFile()) {
            fs.copyFileSync(path.join(__dirname, name), path.join(serverDirectory, name));
        }
    }
    const dependencies = snapshotDependencies(fs.realpathSync(path.join(__dirname, 'node_modules')), path.join(serverDirectory, 'node_modules'));
    fs.writeFileSync(path.join(root, 'dependencies.sha256'), dependencies + '\n');
    for (const directory of ['core', 'plugin-interface', 'ee']) {
        if (directory === 'ee' && !fs.existsSync(path.join(source, directory))) continue;
        fs.mkdirSync(path.join(root, directory));
        for (const name of fs.readdirSync(path.join(source, directory)).filter(name => /\.jar$/i.test(name))) {
            fs.copyFileSync(path.join(source, directory, name), path.join(root, directory, name));
        }
    }
    const instance = crypto.randomBytes(24).toString('hex');
    const env = environment(process.env, root, backendPort, corePort, runId, instance);
    const backend = new OwnedProcess(runId);
    const log = fs.openSync(path.join(root, 'backend.log'), 'a');
    let ready = false;
    let commandSucceeded = false;
    const test = new OwnedCommandGroup(runId, entry => fs.appendFileSync(path.join(root, 'process-tree.jsonl'), JSON.stringify(entry) + '\n'));
    let interrupted = false;
    let resolveInterrupted;
    const interruption = new Promise(resolve => { resolveInterrupted = resolve; });
    const interrupt = () => { interrupted = true; resolveInterrupted(130); };
    process.on('SIGINT', interrupt);
    process.on('SIGTERM', interrupt);
    try {
        // Bind probing never signals the current listener if a requested port is occupied.
        const { unusedPort } = require('./ownedCore');
        await unusedPort(backendPort);
        await unusedPort(corePort);
        await backend.start(process.execPath, [path.join(serverDirectory, 'index.js')],
            { cwd: serverDirectory, env, detached: true, stdio: ['ignore', log, log] });
        backend.verifyDetachedGroup();
        const deadline = Date.now() + 15000;
        while (Date.now() < deadline && !interrupted) {
            if (backend.child.exitCode !== null || backend.child.signalCode !== null) throw new Error('Backend exited during startup');
            try {
                await ownedBackendRequest(backend, backendPort, instance);
                ready = true;
                break;
            } catch (_) { }
            await new Promise(resolve => setTimeout(resolve, 100));
        }
        if (!ready || interrupted) throw new Error('Backend startup interrupted or timed out');
        console.log(JSON.stringify({ root, backendPid: backend.child.pid, backendPort, corePort, instance }));
        await test.start(command, { env, cwd: process.cwd() });
        const code = await Promise.race([test.done, interruption]);
        commandSucceeded = code === 0 && !interrupted;
        process.exitCode = code || (interrupted ? 130 : 0);
    } finally {
        try { await test.drain(); }
        catch (error) {
            try {
                try {
                    const record = fs.openSync(path.join(root, 'RETAINED.json'), 'w', 0o600);
                    try {
                        fs.writeFileSync(record, JSON.stringify({ backendPid: backend.child && backend.child.pid,
                            commandGroup: test.group, fingerprint: backend.fingerprint, instance, error: error.message }));
                        fs.fsyncSync(record);
                    } finally { fs.closeSync(record); }
                    fs.writeSync(2, 'Command drain unproven; Core/backend retained at ' + root + ': ' + error.message + '\n');
                } catch (recordError) {
                    try { fs.writeSync(2, 'Command drain unproven; retention record or diagnostic failed (' +
                        (recordError.code || 'I/O error') + '); fixture location: ' + root + '\n'); } catch (_) { }
                }
            } finally { process.exit(1); }
        }
        fs.appendFileSync(path.join(root, 'process-tree.jsonl'), JSON.stringify({ action: 'BEFORE_CORE_CLEANUP' }) + '\n');
        if (ready) {
            // Refuse to kill the backend unless it has acknowledged Core cleanup.
            try {
                await ownedBackendRequest(backend, backendPort, instance, '/after');
                fs.appendFileSync(path.join(root, 'process-tree.jsonl'), JSON.stringify({ action: 'CORE_CLEANUP_ACKNOWLEDGED' }) + '\n');
            }
            catch (error) {
                fs.writeFileSync(path.join(root, 'RETAINED.json'), JSON.stringify({ backendPid: backend.child.pid,
                    fingerprint: backend.fingerprint, instance, error: error.message }));
                throw new Error('Cleanup not acknowledged; backend retained at ' + root + ': ' + error.message);
            }
        }
        if (backend.child && !backend.fingerprint) backend.recoverIdentity(runId);
        await backend.stop(runId);
        fs.appendFileSync(path.join(root, 'process-tree.jsonl'), JSON.stringify({ action: 'BACKEND_STOPPED' }) + '\n');
        fs.closeSync(log);
        if (digestTree(path.join(serverDirectory, 'node_modules')) !== dependencies) throw new Error('Private dependency snapshot mutated during run');
        if (commandSucceeded && !interrupted) {
            const lifecycle = fs.readFileSync(path.join(root, 'process-tree.jsonl'));
            fs.writeFileSync(path.join(root, 'lifecycle-completed.json'), JSON.stringify({
                schema: 1, root, runId, instance, command, exitCode: 0,
                dependenciesSha256: dependencies,
                lifecycleSha256: crypto.createHash('sha256').update(lifecycle).digest('hex'),
            }), { flag: 'wx' });
        }
        console.log('Owned fixture stopped; evidence: ' + root);
    }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
