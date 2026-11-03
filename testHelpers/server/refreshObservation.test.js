const assert = require('assert');
const { EventEmitter } = require('events');
const { createRefreshObservation } = require('./refreshObservation');
const run = 'a'.repeat(48), instance = 'b'.repeat(48), challenge = 'd'.repeat(48);
const auth = { 'x-st-test-run-id': run, 'x-st-test-instance-id': instance, 'x-st-test-challenge': challenge };
function req(headers = {}) { return { method: 'POST', path: '/auth/session/refresh', cookies: {}, get: key => headers[key] }; }
function res() { const r = new EventEmitter(); r.statusCode = 401; r.getHeader = () => 'remove'; r.sendStatus = code => { r.code = code; }; r.json = body => { r.body = body; }; return r; }
let clock = 0;
function setup() { return createRefreshObservation({ runId: run, instance, now: () => clock, ttl: 10 }); }
function arm(o, headers = auth) { const r = res(); o.arm(req(headers), r); return r; }
function consume(o, generation, headers = auth) { const r = res(); o.consume(req({ ...headers, 'x-st-test-generation': generation }), r); return r; }
function hit(o, token, extra = {}) { const r = res(); let next = 0; o.observe(req({ 'x-st-test-case': token, ...extra }), r, () => next++); assert.equal(next, 1); return r; }
{
 const o = createRefreshObservation({}); assert.equal(arm(o).code, 403);
 for (const h of [{}, { ...auth, 'x-st-test-run-id': 'x' }, { ...auth, 'x-st-test-instance-id': 'x' }]) assert.equal(arm(setup(), h).code, 403);
}
{
 const o = setup(), a = arm(o), r = hit(o, a.body.generation); r.emit('finish'); const c = consume(o, a.body.generation);
 assert.deepEqual(c.body.observation, { refreshCookie:false, refreshHeader:false, authorization:false, status:401, frontToken:'remove' });
 assert.equal(consume(o, a.body.generation).code, 409);
}
{
 const o = setup(), a = arm(o), r = hit(o, a.body.generation); hit(o, a.body.generation).emit('finish'); r.emit('finish'); assert.equal(consume(o, a.body.generation).code, 409);
}
{
 const o = setup(), a = arm(o), r = hit(o, 'e'.repeat(48)); r.emit('finish'); assert.equal(consume(o, a.body.generation).code, 409);
}
{
 const o = setup(), a = arm(o), r = hit(o, a.body.generation); r.emit('close'); assert.equal(consume(o, a.body.generation).code, 409);
}
{
 const o = setup(), a = arm(o), old = hit(o, a.body.generation); o.invalidate(); assert.equal(arm(o).code, 409); old.emit('finish');
 const b = arm(o); assert.notEqual(a.body.generation, b.body.generation); const newer = hit(o, b.body.generation); newer.emit('finish'); assert.ok(consume(o, b.body.generation).body);
 const c = arm(o); assert.notEqual(c.body.generation, a.body.generation); const replay = hit(o, a.body.generation); replay.emit('finish'); assert.equal(consume(o, c.body.generation).code, 409);
}
{
 const o = setup(), a = arm(o), r = hit(o, a.body.generation); clock = 11; r.emit('finish'); assert.equal(consume(o, a.body.generation).code, 409); clock = 0;
}
{
 const o = setup(), a = arm(o), r = hit(o, a.body.generation, { authorization:'PRIVATE', 'st-refresh-token':'PRIVATE' }); r.emit('finish');
 const c = consume(o, a.body.generation); assert.equal(c.body.observation.authorization, true); assert.equal(c.body.observation.refreshHeader, true); assert(!JSON.stringify(c.body).includes('PRIVATE'));
}
console.log('refresh observation host controls passed');
