const crypto = require('crypto');

function createRefreshObservation({ runId, instance, now = Date.now, ttl = 30000 }) {
    const validId = value => typeof value === 'string' && /^[a-f0-9]{48}$/.test(value);
    const enabled = validId(runId) && validId(instance);
    let lease = null;
    const prefix = crypto.randomBytes(16).toString('hex');
    let sequence = 0n;
    let inflight = 0;
    function authorized(req) {
        return enabled && req.get('x-st-test-run-id') === runId &&
            req.get('x-st-test-instance-id') === instance;
    }
    function invalidate() { lease = null; }
    function arm(req, res) {
        if (!authorized(req)) return res.sendStatus(403);
        if (lease || inflight || sequence === 0xffffffffffffffffn) return res.sendStatus(409);
        sequence++;
        const generation = prefix + sequence.toString(16).padStart(16, '0');
        lease = { generation, deadline: now() + ttl, attempts: 0, completed: 0, failed: false, result: null };
        return res.json({ generation });
    }
    function consume(req, res) {
        if (!authorized(req)) return res.sendStatus(403);
        const current = lease;
        if (!current || req.get('x-st-test-generation') !== current.generation || now() > current.deadline ||
            current.failed || current.attempts !== 1 || current.completed !== 1 || inflight) {
            invalidate();
            return res.sendStatus(409);
        }
        invalidate();
        return res.json({ generation: current.generation, observation: current.result });
    }
    function observe(req, res, next) {
        if (req.method !== 'POST' || req.path !== '/auth/session/refresh') return next();
        inflight++;
        const current = lease;
        if (current) {
            current.attempts++;
            if (current.attempts !== 1 || now() > current.deadline || req.get('x-st-test-case') !== current.generation) current.failed = true;
        }
        let settled = false;
        function settle(completed) {
            if (settled) return;
            settled = true;
            inflight--;
            if (!current || lease !== current) return;
            if (!completed || now() > current.deadline) { current.failed = true; return; }
            current.completed++;
            const front = res.getHeader('front-token');
            current.result = {
                refreshCookie: Object.prototype.hasOwnProperty.call(req.cookies || {}, 'sRefreshToken'),
                refreshHeader: req.get('st-refresh-token') !== undefined,
                authorization: req.get('authorization') !== undefined,
                status: res.statusCode,
                frontToken: front === undefined ? 'absent' : typeof front === 'string' && front.toLowerCase() === 'remove' ? 'remove' : 'other'
            };
        }
        res.once('finish', () => settle(true));
        res.once('close', () => settle(false));
        return next();
    }
    return { arm, consume, observe, invalidate };
}
module.exports = { createRefreshObservation };
