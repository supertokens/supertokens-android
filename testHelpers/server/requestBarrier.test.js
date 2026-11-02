const assert = require('assert');
const { EventEmitter } = require('events');
const createBarrier = require('./requestBarrier');

const middleware = createBarrier('owned');
function call(method, path, headers = {}, body) {
    const req = { method, path, body, get: name => headers[name] };
    const res = new EventEmitter();
    res.sendStatus = status => { res.status = status; return res; };
    res.json = value => { res.value = value; return res; };
    res.nextCount = 0;
    middleware(req, res, () => res.nextCount++);
    return res;
}
const owner = { 'x-st-test-run-id': 'owned' };
assert.strictEqual(call('POST', '/test/request-barrier', {}, { id: 'test' }).status, 403);
assert.strictEqual(call('POST', '/test/request-barrier', owner, { id: 'test' }).status, 200);
assert.strictEqual(call('POST', '/test/request-barrier', owner, { id: 'other' }).status, 409);
assert.strictEqual(call('GET', '/', {}).nextCount, 1);
const waiting = call('GET', '/', { 'x-st-test-barrier': 'test' });
const disconnected = call('GET', '/', { 'x-st-test-barrier': 'test' });
assert.strictEqual(waiting.nextCount, 0);
assert.deepStrictEqual(call('GET', '/test/request-barrier', owner).value, { arrived: 2, active: true });
disconnected.emit('close');
assert.strictEqual(call('DELETE', '/test/request-barrier', owner).status, 200);
assert.strictEqual(waiting.nextCount, 1);
assert.strictEqual(disconnected.nextCount, 0);
assert.deepStrictEqual(call('GET', '/test/request-barrier', owner).value, { arrived: 0, active: false });
assert.strictEqual(call('POST', '/test/request-barrier', owner, { id: '../bad' }).status, 400);
assert.strictEqual(call('POST', '/test/request-barrier', owner, { id: 'signout', target: 'signout' }).status, 200);
const signout = call('POST', '/auth/signout');
assert.strictEqual(signout.nextCount, 0);
assert.strictEqual(call('POST', '/login').nextCount, 1);
call('DELETE', '/test/request-barrier', owner);
assert.strictEqual(signout.nextCount, 1);
