const http = require('http');
const net = require('net');
const crypto = require('crypto');
const { inspect } = require('./coreRuntimeIdentity');

async function ownedBackendRequest(owner, port, instance, route, inspectListener = inspect) {
    function verifyListener() {
        owner.verify();
        const metadata = inspectListener(owner.child.pid, port);
        if (metadata.cwd !== owner.launch.cwd || !metadata.listening) throw new Error('Owned backend is not the listener');
    }
    verifyListener();
    const agent = new http.Agent({ keepAlive: true, maxSockets: 1 });
    let pinned;
    let authenticated = false;
    agent.createConnection = (options, callback) => {
        if (authenticated) {
            callback(new Error('Authenticated backend socket closed; refusing a new listener'));
            return;
        }
        const socket = net.connect({ host: '127.0.0.1', port });
        socket.once('error', callback);
        socket.once('connect', () => {
            try { verifyListener(); pinned = socket; callback(null, socket); }
            catch (error) { socket.destroy(); callback(error); }
        });
    };
    const request = (url, method, headers = {}) => new Promise((resolve, reject) => {
        const req = http.request({ host: '127.0.0.1', port, path: url, method, headers, agent }, response => {
            let data = '';
            response.on('data', chunk => { data += chunk; });
            response.on('end', () => resolve({ status: response.statusCode, body: data }));
            response.on('error', reject);
        });
        req.setTimeout(15000, () => req.destroy(new Error('Owned backend request timed out')));
        req.on('error', reject);
        req.end();
    });
    try {
        const challenge = crypto.randomBytes(24).toString('hex');
        const response = await request('/test/instance?challenge=' + challenge, 'GET');
        verifyListener();
        const proof = crypto.createHmac('sha256', owner.runId)
            .update(challenge + ':' + instance + ':' + owner.child.pid).digest('hex');
        const body = JSON.parse(response.body);
        if (response.status !== 200 || body.pid !== owner.child.pid || body.instance !== instance || body.proof !== proof) {
            throw new Error('Backend instance proof mismatch');
        }
        if (!route) return body;
        authenticated = true;
        if (!pinned || pinned.destroyed) throw new Error('Backend proof socket closed');
        verifyListener();
        const result = await request(route, 'POST', { 'x-st-test-run-id': owner.runId });
        verifyListener();
        if (result.status !== 200) throw new Error('Owned backend cleanup not acknowledged: ' + result.status);
        return result;
    } finally { agent.destroy(); }
}

module.exports = { ownedBackendRequest };
