const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const net = require('net');
const { spawnSync } = require('child_process');
const { qualify } = require('./ownedProxyQualification');

async function main() {
    const reservation = net.createServer();
    await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve));
    const port = reservation.address().port;
    await new Promise(resolve => reservation.close(resolve));
    const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'owned-proxy-idle-')));
    try {
        fs.mkdirSync(path.join(root, 'node_modules'));
        fs.symlinkSync(path.dirname(require.resolve('node-fetch/package.json')), path.join(root, 'node_modules/node-fetch'));
        fs.writeFileSync(path.join(root, 'runQualificationProxy.js'), `
            const server = require('http').createServer((req, res) => {
                const body = req.url === '/test/capabilities' ? { qualificationProxy: true } :
                    req.url === '/__qualification/owners' ? { fixtures: [], unresolvedCreations: 0 } :
                    req.url === '/__qualification/state' ? { active: 0, upstreamActive: 0, ownedFixtures: 0,
                        ownerReservations: 0, unresolvedCreations: 0, gates: {} } : {};
                res.setHeader('content-type', 'application/json');
                res.end(JSON.stringify(body));
            });
            server.keepAliveTimeout = 2000;
            server.keepAliveTimeoutBuffer = 0;
            server.listen(Number(process.env.QUALIFICATION_PROXY_PORT), '127.0.0.1');
            process.once('SIGTERM', () => server.close(() => process.exit(0)));
        `);
        await qualify({ backend: root, evidence: root, env: { ...process.env, NODE_PORT: String(port - 1),
            ST_TEST_CORE_PORT: '19011', ST_TEST_BACKEND_URL: 'http://127.0.0.1:' + (port - 1), ST_TEST_RUN_ID: 'proxy-idle-control' },
            runTests: async () => {
                // Match synchronous Gradle: prevent socket-close callbacks until the server expires its connection.
                const child = spawnSync(process.execPath, ['-e', 'Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 3500)']);
                assert.strictEqual(child.status, 0);
            }
        });
        const events = fs.readFileSync(path.join(root, 'proxy-lifecycle.jsonl'), 'utf8').trim().split('\n').map(JSON.parse);
        assert.deepStrictEqual(events.map(event => event.action), ['STARTED', 'ZERO_LEAKS_ACKNOWLEDGED', 'STOPPED']);
        console.log('PASS: actual owned proxy control path survives blocked event loop (synthetic zero-owner backend; no Core qualification)');
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
