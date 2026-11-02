// A bounded, explicitly armed barrier for the isolated live-test server.
module.exports = function requestBarrier(runId) {
    let barrier;
    function release() {
        if (!barrier) return;
        const current = barrier;
        barrier = undefined;
        clearTimeout(current.timer);
        current.waiters.splice(0).forEach(next => next());
    }
    return function (req, res, next) {
        if (req.path === '/test/request-barrier') {
            if (!runId || req.get('x-st-test-run-id') !== runId) return res.sendStatus(403);
            if (req.method === 'POST') {
                if (barrier) return res.sendStatus(409);
                const id = req.body && req.body.id;
                if (typeof id !== 'string' || !/^[a-zA-Z0-9-]{1,80}$/.test(id)) return res.sendStatus(400);
                const target = req.body.target || 'resource';
                if (!['resource', 'signout'].includes(target)) return res.sendStatus(400);
                barrier = { id, target, arrived: 0, waiters: [], timer: setTimeout(release, 20000) };
                return res.sendStatus(200);
            }
            if (req.method === 'GET') return res.json({ arrived: barrier ? barrier.arrived : 0, active: !!barrier });
            if (req.method === 'DELETE') { release(); return res.sendStatus(200); }
            return res.sendStatus(405);
        }
        if (!barrier) return next();
        const matches = barrier.target === 'resource'
            ? req.method === 'GET' && req.path === '/' && req.get('x-st-test-barrier') === barrier.id
            : req.method === 'POST' && req.path === '/auth/signout';
        if (!matches) return next();
        const current = barrier;
        current.arrived++;
        const resume = () => { if (!res.destroyed) next(); };
        current.waiters.push(resume);
        res.once('close', () => {
            const index = current.waiters.indexOf(resume);
            if (index !== -1) current.waiters.splice(index, 1);
        });
    };
};
