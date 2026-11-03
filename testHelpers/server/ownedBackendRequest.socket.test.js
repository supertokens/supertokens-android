const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('http');
const crypto = require('crypto');
const { ownedBackendRequest } = require(process.env.SOCKET_TEST_SUBJECT || './ownedBackendRequest');
const runId = 'a'.repeat(48), instance = 'b'.repeat(48), generation = 'c'.repeat(48);
async function fixture(t, behavior = {}) {
    const seen = [];
    const server = http.createServer((req, res) => {
        seen.push({ method: req.method, path: req.url, run: req.headers['x-st-test-run-id'], instance: req.headers['x-st-test-instance-id'], generation: req.headers['x-st-test-generation'], socket: req.socket });
        if (req.url.startsWith('/test/instance?')) {
            const challenge = new URL(req.url, 'http://localhost').searchParams.get('challenge');
            const proof = crypto.createHmac('sha256', runId).update(challenge + ':' + instance + ':' + process.pid).digest('hex');
            if (behavior.afterProof) behavior.afterProof();
            if (behavior.closeAfterProof) res.setHeader('Connection', 'close');
            return res.end(JSON.stringify({ pid: process.pid, instance: behavior.badInstance ? 'x' : instance, proof: behavior.badProof ? 'x' : proof }));
        }
        if (behavior.largeBody) return res.end('x'.repeat(70000));
        res.statusCode = behavior.postStatus || 200;
        res.end('{}');
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); });
    let valid = true;
    const owner = { runId, child: { pid: process.pid }, launch: { cwd: '/owned' }, verify() { if (!valid) throw new Error('fingerprint mismatch'); } };
    return { server, seen, owner, port: server.address().port, inspect: () => ({ cwd:'/owned', listening:true }), invalidate: () => { valid = false; } };
}
const call = (f, route, observation) => ownedBackendRequest(f.owner, f.port, instance, route, f.inspect, observation);
test('wrong listener is rejected before bearer disclosure', async t => {
    const f = await fixture(t); f.inspect = () => ({ cwd:'/foreign', listening:true });
    await assert.rejects(call(f, '/after'), /not the listener/); assert.equal(f.seen.length, 0);
});
test('instance and challenge proof mismatch disclose no bearer', async t => {
    for (const options of [{badInstance:true}, {badProof:true}]) {
        const f = await fixture(t, options); await assert.rejects(call(f, '/after'), /proof mismatch/);
        assert.equal(f.seen.length, 1); assert.equal(f.seen[0].run, undefined);
    }
});
test('fingerprint replacement after proof rejects before POST', async t => {
    let f; f = await fixture(t, { afterProof: () => f.invalidate() });
    await assert.rejects(call(f, '/after'), /fingerprint mismatch/); assert.equal(f.seen.length, 1); assert.equal(f.seen[0].run, undefined);
});
test('closed proof socket refuses reconnect', async t => {
    const f = await fixture(t, { closeAfterProof:true });
    await assert.rejects(call(f, '/test/refresh-observation/arm', {action:'arm'}));
    assert.equal(f.seen.filter(x => x.method === 'POST').length, 0);
});
test('arm and consume independently challenge and pin their own POST', async t => {
    const f = await fixture(t);
    await call(f, '/test/refresh-observation/arm', {action:'arm'});
    await call(f, '/test/refresh-observation/consume', {action:'consume', generation});
    assert.deepEqual(f.seen.map(x => x.method), ['GET','POST','GET','POST']);
    assert.equal(f.seen[0].run, undefined); assert.equal(f.seen[2].run, undefined);
    assert.equal(f.seen[1].socket, f.seen[0].socket); assert.equal(f.seen[3].socket, f.seen[2].socket);
    assert.equal(f.seen[1].run, runId); assert.equal(f.seen[1].instance, instance); assert.equal(f.seen[1].generation, undefined);
    assert.equal(f.seen[3].generation, generation);
});
test('after retains POST shape, header and error behavior', async t => {
    const f = await fixture(t); await call(f, '/after');
    assert.equal(f.seen[1].path, '/after'); assert.equal(f.seen[1].run, runId); assert.equal(f.seen[1].instance, undefined);
    const bad = await fixture(t, {postStatus: 503}); await assert.rejects(call(bad, '/after'), /cleanup not acknowledged: 503/);
});
test('arbitrary observer route and metadata rejected before network', async t => {
    const f = await fixture(t);
    for (const [route, options] of [['/after',{action:'arm'}], ['/test/refresh-observation/arm',{action:'arm', headers:{authorization:'secret'}}], ['/test/refresh-observation/arm',{action:'arm', body:'secret'}]]) {
        await assert.rejects(call(f, route, options));
    }
    assert.equal(f.seen.length, 0);
});
test('oversized body rejected without echoing it', async t => {
    const f = await fixture(t, {largeBody:true});
    await assert.rejects(call(f, '/after'), error => !error.message.includes('x'.repeat(20)));
});
