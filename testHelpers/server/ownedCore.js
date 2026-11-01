const fs = require('fs');
const path = require('path');
const net = require('net');
const fetch = require('node-fetch');
const { OwnedProcess, processGroup } = require('./ownedProcess');
const { inspect, CORE_ARGS, recordCoreLaunch, effectiveArtifacts } = require('./coreRuntimeIdentity');

const runId = process.env.ST_TEST_RUN_ID;
const root = process.env.INSTALL_PATH;
const port = Number(process.env.ST_TEST_CORE_PORT);
const core = new OwnedProcess(runId);

function assertOwner() {
    if (!runId || !root || !Number.isInteger(port) || port < 1024 || port > 65535 || port === 9000) {
        throw new Error('Isolated runner environment required');
    }
    const owner = JSON.parse(fs.readFileSync(path.join(root, 'owner.json'), 'utf8'));
    if (owner.runId !== runId || owner.root !== fs.realpathSync(root)) throw new Error('Foreign installation');
    if (fs.existsSync(path.join(root, 'plugin'))) throw new Error('Database plugins prohibited in isolated fixture');
}

async function unusedPort(port) {
    await new Promise((resolve, reject) => {
        const server = net.createServer();
        server.once('error', reject);
        server.listen(port, '127.0.0.1', () => server.close(resolve));
    });
}

async function stopST(pid) {
    assertOwner();
    if (core.child && !core.fingerprint) core.recoverIdentity(runId);
    await core.stop(runId, pid);
}

async function setupST() {
    assertOwner();
    if (core.child) throw new Error('Stop owned Core before resetting configuration');
    fs.writeFileSync(path.join(root, 'config.yaml'), 'core_config_version: 0\ndisable_telemetry: true\n');
}

async function setKeyValueInConfig(key, value) {
    assertOwner();
    if (core.child) throw new Error('Stop owned Core before changing configuration');
    if (!['access_token_validity', 'access_token_signing_key_update_interval'].includes(key) ||
        !Number.isFinite(Number(value)) || Number(value) <= 0) throw new Error('Invalid Core configuration');
    const file = path.join(root, 'config.yaml');
    const lines = fs.readFileSync(file, 'utf8').split('\n').filter(line => !line.startsWith(key + ':'));
    fs.writeFileSync(file, lines.join('\n') + key + ': ' + Number(value) + '\n');
}

async function startST() {
    assertOwner();
    if (fs.existsSync(path.join(root, '.started', '127.0.0.1-' + port))) {
        throw new Error('Existing Core marker; refusing to adopt or overwrite it');
    }
    await unusedPort(port);
    const args = CORE_ARGS;
    try {
        const child = await core.start(process.env.ST_TEST_JAVA || 'java', args, { cwd: root, stdio: 'inherit' });
        if (processGroup(process.pid) !== process.pid || processGroup(child.pid) !== process.pid) {
            throw new Error('Core must inherit the isolated backend process group');
        }
        const deadline = Date.now() + 15000;
        while (Date.now() < deadline) {
            if (child.exitCode !== null || child.signalCode !== null) throw new Error('Owned Core exited during startup');
            try {
                const marker = fs.readFileSync(path.join(root, '.started', '127.0.0.1-' + port), 'utf8').trim();
                if (!marker) throw new Error('Core is still writing its PID marker');
                if (marker !== String(child.pid)) throw new Error('Foreign Core marker');
                const response = await fetch('http://127.0.0.1:' + port + '/hello', { timeout: 500 });
                if (response.status === 200) {
                    const identity = inspect(child.pid, port);
                    if (identity.cwd !== fs.realpathSync(root) || !identity.listening) throw new Error('Foreign Core listener');
                    recordCoreLaunch(child.pid, root, effectiveArtifacts(root).digest);
                    return;
                }
            } catch (error) {
                if (error.message.startsWith('Foreign Core')) throw error;
            }
            await new Promise(resolve => setTimeout(resolve, 100));
        }
        throw new Error('Owned Core startup timed out');
    } catch (error) {
        try { await stopST(); } catch (cleanup) { error.message += '; cleanup failed: ' + cleanup.message; }
        throw error;
    }
}

async function cleanST() {
    await stopST();
    // Runtime files stay in this run's directory as evidence. No installation-wide deletion.
}

module.exports = { assertOwner, unusedPort, startST, stopST, setupST, cleanST, setKeyValueInConfig };
