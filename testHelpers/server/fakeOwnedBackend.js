const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const [mode, marker] = process.argv.slice(2);
const server = http.createServer((req, res) => {
    fs.appendFileSync(marker, JSON.stringify({ path: req.url, guard: Boolean(req.headers['x-st-test-run-id']) }) + '\n');
    if (req.url.startsWith('/test/instance')) {
        const challenge = new URL(req.url, 'http://localhost').searchParams.get('challenge');
        const instance = 'fake-instance';
        const proof = crypto.createHmac('sha256', 'fake-run').update(challenge + ':' + instance + ':' + process.pid).digest('hex');
        if (mode === 'close') res.setHeader('connection', 'close');
        res.end(JSON.stringify({ instance, pid: process.pid, proof }));
    } else { res.statusCode = 500; res.end('cleanup failed'); }
});
server.listen(0, '127.0.0.1', () => process.send({ port: server.address().port }));
