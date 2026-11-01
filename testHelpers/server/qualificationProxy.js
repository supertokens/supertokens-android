const http = require("http");

const PROFILE = "/auth/plugin/rownd/user";
const REFRESH = "/auth/session/refresh";
const METHODS = new Map([
    [PROFILE, "GET"], [REFRESH, "POST"], ["/test/protected", "GET"],
    ["/test/capabilities", "GET"], ["/test/email-change-owner", "POST"],
    ["/test/revoke-session", "POST"], ["/test/cleanup-owner", "POST"],
    ["/test/owner-session-state", "POST"]
]);

function headersWithoutHopByHop(headers) {
    const removed = new Set(["connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailer", "transfer-encoding", "upgrade", "host"]);
    for (const name of (headers.connection || "").split(",")) removed.add(name.trim().toLowerCase());
    const result = {};
    for (const [name, value] of Object.entries(headers)) if (!removed.has(name)) result[name] = value;
    return result;
}

function configuration(env) {
    return {
        enabled: env.TEST_MODE === "testing" && env.CORE_PROTOCOL_TESTS === "1",
        target: env.QUALIFICATION_PROXY_TARGET
    };
}

module.exports = function createQualificationProxy(options) {
    const enabled = options.enabled === true && !!options.target;
    let target;
    if (options.target) {
        target = new URL(options.target);
        if (target.protocol !== "http:" || !["127.0.0.1", "localhost", "[::1]"].includes(target.hostname)
                || target.username || target.password || target.pathname !== "/" || target.search || target.hash) {
            throw new Error("Qualification target must be an explicit loopback HTTP origin");
        }
    }
    const deadlineMs = options.deadlineMs || 8000;
    const maxBytes = options.maxBytes || 1024 * 1024;
    const maxActive = options.maxActive || 16;
    const maxOwners = options.maxOwners || 32;
    for (const value of [deadlineMs, maxBytes, maxActive, maxOwners]) {
        if (!Number.isSafeInteger(value) || value < 1) throw new Error("Invalid proxy limit");
    }
    const jobs = new Set(), sockets = new Set(), upstreams = new Set(), owners = new Map(), cleaned = new Map();
    let gates = new Map(), generation = 0, setupPending = false, closed = false, ownerReservations = 0;
    let accountingEpoch = 0;
    let controlsActive = 0, unresolvedCreations = 0;
    let pendingArm = null;
    const counts = {}, events = [];
    let sequence = 0;

    function event(path, kind, epoch = accountingEpoch) {
        if (epoch !== accountingEpoch) return;
        const count = counts[path] || (counts[path] = {});
        count[kind] = (count[kind] || 0) + 1;
        events.push({ sequence: ++sequence, generation, accountingEpoch, path, kind });
        if (events.length > 64) events.shift();
    }

    function json(res, status, body) {
        if (res.destroyed || res.writableEnded) return;
        const bytes = Buffer.from(JSON.stringify(body));
        res.writeHead(status, { "content-type": "application/json", "content-length": bytes.length });
        res.end(bytes);
    }

    function collect(stream, limit, callback) {
        let size = 0, chunks = [], ended = false;
        function complete(error, body) {
            if (ended) return;
            ended = true;
            chunks = [];
            callback(error, body);
        }
        stream.on("data", chunk => {
            size += chunk.length;
            if (size > limit) complete(new Error("BODY_LIMIT"));
            else if (!ended) chunks.push(chunk);
        });
        stream.on("end", () => complete(null, Buffer.concat(chunks)));
        stream.on("aborted", () => complete(new Error("ABORTED")));
        stream.on("error", () => complete(new Error("STREAM_ERROR")));
    }

    function upstream(path, method, headers, body, callback) {
        const request = http.request(new URL(path, target), { method, headers: headersWithoutHopByHop(headers), agent: false });
        upstreams.add(request);
        let done = false;
        const timer = setTimeout(() => finish(new Error("UPSTREAM_TIMEOUT")), deadlineMs);
        function finish(error, result) {
            if (done) return;
            done = true;
            clearTimeout(timer);
            upstreams.delete(request);
            if (error) request.destroy();
            callback(error, result);
        }
        request.on("error", () => finish(new Error("UPSTREAM_ERROR")));
        request.on("response", response => collect(response, maxBytes, (error, bytes) => {
            if (error) response.destroy();
            finish(error, error ? null : { status: response.statusCode,
                headers: headersWithoutHopByHop(response.headers), body: bytes });
        }));
        request.end(body);
        return { abort() { finish(new Error("CANCELLED")); } };
    }

    function capability(result) {
        if (result.status !== 200 || (result.headers["content-encoding"] && result.headers["content-encoding"] !== "identity")) {
            throw new Error("CAPABILITY_UNAVAILABLE");
        }
        const report = JSON.parse(result.body.toString("utf8"));
        if (!report || typeof report !== "object" || Array.isArray(report)) throw new Error("INVALID_CAPABILITY_RESPONSE");
        report.qualificationProxy = enabled && report.enabled === true && report.compatible === true && report.mappedGoogleSession === true;
        report.qualificationProxyDetails = {
            state: report.qualificationProxy ? "configured" : "backend_fixture_unavailable",
            completedResponses: true, profileAlias: "/test/protected", maxActive, maxBytes, deadlineMs,
            unsupported: ["account-linking-fixture", "parallel-control-owners", "arbitrary-route-gating"]
        };
        return report;
    }

    function newGate(path) {
        const epoch = accountingEpoch;
        const gate = { path, claimed: false, responseReady: false, released: false, delivered: false,
            timedOut: false, dropped: false, aborted: false, failed: false, status: null,
            hasAccess: false, hasRefresh: false, hasFront: false, accessChangedFromRequest: false,
            job: null, timer: null };
        gate.timer = setTimeout(() => {
            gate.timedOut = true;
            event(path, "timedOut", epoch);
            if (gate.job) gate.job.fail(504, "GATE_TIMEOUT");
        }, deadlineMs);
        return gate;
    }

    function state() {
        const visible = {};
        for (const [path, gate] of gates) {
            const { job, timer, ...metadata } = gate;
            visible[path] = metadata;
        }
        return { generation, accountingEpoch, gates: visible, counts, events, active: jobs.size,
            upstreamActive: upstreams.size, ownedFixtures: owners.size, ownerReservations,
            controlsActive, unresolvedCreations };
    }

    function reset() {
        generation++;
        accountingEpoch++;
        setupPending = false;
        pendingArm = null;
        for (const gate of gates.values()) clearTimeout(gate.timer);
        for (const job of Array.from(jobs)) job.fail(503, "PROXY_RESET");
        for (const request of upstreams) request.destroy();
        gates = new Map();
        for (const path of Object.keys(counts)) delete counts[path];
        events.length = 0;
    }

    function controls(req, res, path, body) {
        if (path === "/__qualification/state" && req.method === "GET") return json(res, 200, state());
        if (path === "/__qualification/owners" && req.method === "GET") {
            return json(res, 200, { fixtures: Array.from(owners.values()), unresolvedCreations });
        }
        if (req.method !== "POST") return json(res, 405, { status: "METHOD_NOT_ALLOWED" });
        let input;
        try { input = JSON.parse(body.toString("utf8")); } catch (_) { return json(res, 400, { status: "INVALID_JSON" }); }
        if (!input || typeof input !== "object" || Array.isArray(input)) return json(res, 400, { status: "INVALID_CONTROL" });
        if (path === "/__qualification/arm") {
            if (!Array.isArray(input.paths) || !input.paths.length || input.paths.length > 2
                    || new Set(input.paths).size !== input.paths.length || input.paths.some(p => ![PROFILE, REFRESH].includes(p))) {
                return json(res, 400, { status: "INVALID_GATE_PATHS" });
            }
            if (setupPending || Array.from(gates.values()).some(g => g.job || (!g.released && !g.timedOut && !g.aborted && !g.dropped && !g.failed))) {
                return json(res, 409, { status: "GATES_ALREADY_OWNED" });
            }
            setupPending = true;
            const epoch = generation;
            const ticket = {};
            pendingArm = ticket;
            ticket.transport = upstream("/test/capabilities", "GET", { "accept-encoding": "identity" }, null, (error, result) => {
                if (closed || epoch !== generation || pendingArm !== ticket) return json(res, 409, { status: "STALE_ARM" });
                setupPending = false;
                pendingArm = null;
                if (res.destroyed) return;
                if (error) return json(res, 502, { status: "CAPABILITY_UNAVAILABLE" });
                let report;
                try { report = capability(result); } catch (_) { return json(res, 502, { status: "INVALID_CAPABILITY_RESPONSE" }); }
                if (!report.qualificationProxy) return json(res, 503, { status: "BACKEND_FIXTURE_UNAVAILABLE", capabilities: report });
                for (const gate of gates.values()) clearTimeout(gate.timer);
                generation++;
                gates = new Map(input.paths.map(p => [p, newGate(p)]));
                json(res, 200, { armed: true, generation });
            });
            res.once("close", () => {
                if (pendingArm === ticket && !res.writableFinished) {
                    pendingArm = null;
                    setupPending = false;
                    ticket.transport.abort();
                }
            });
            return;
        }
        if (path === "/__qualification/reset") {
            reset();
            return json(res, 200, { reset: true, generation });
        }
        if (path === "/__qualification/release-all") {
            if (pendingArm) {
                const ticket = pendingArm;
                pendingArm = null;
                setupPending = false;
                ticket.transport.abort();
            }
            for (const gate of gates.values()) {
                clearTimeout(gate.timer);
                gate.released = true;
                if (gate.job) gate.job.deliver();
            }
            return json(res, 200, { released: true });
        }
        if (path === "/__qualification/release" || path === "/__qualification/drop") {
            const gate = gates.get(input.path);
            if (!gate) return json(res, 404, { status: "UNKNOWN_GATE" });
            if (!gate.responseReady || !gate.job || gate.timedOut || gate.aborted || gate.delivered || gate.dropped || gate.failed) {
                return json(res, 409, { status: "GATE_NOT_HELD" });
            }
            clearTimeout(gate.timer);
            if (path.endsWith("/drop")) gate.job.drop();
            else { gate.released = true; gate.job.deliver(); }
            return json(res, 200, { released: gate.released, dropped: gate.dropped });
        }
        return json(res, 404, { status: "UNKNOWN_CONTROL" });
    }

    const server = http.createServer((req, res) => {
        const path = req.url.split("?", 1)[0];
        if (!enabled || closed) return json(res, path === "/test/capabilities" ? 200 : 501,
            { qualificationProxy: false, status: "QUALIFICATION_PROXY_DISABLED" });
        if (!req.url.startsWith("/") || req.url.startsWith("//")) return json(res, 400, { status: "INVALID_TARGET" });
        const control = path.startsWith("/__qualification/");
        if (!control && (!METHODS.has(path) || METHODS.get(path) !== req.method)) return json(res, 404, { status: "ROUTE_NOT_FORWARDED" });
        // State/release/reset remain usable even when all data slots are occupied.
        if (!control && jobs.size >= maxActive) return json(res, 503, { status: "PROXY_CAPACITY" });
        if (control) {
            if (controlsActive >= 8) return json(res, 503, { status: "CONTROL_CAPACITY" });
            controlsActive++;
            const timer = setTimeout(() => res.destroy(), deadlineMs);
            res.on("close", () => { clearTimeout(timer); controlsActive--; });
            collect(req, 4096, (error, body) => {
                if (error) return json(res, 413, { status: "CONTROL_BODY_LIMIT" });
                controls(req, res, path, body);
            });
            return;
        }
        const gate = gates.get(path);
        const claimed = gate && !gate.claimed && !gate.released && !gate.timedOut ? gate : null;
        if (claimed) claimed.claimed = true;
        const job = { done: false, result: null, transport: null, reserved: false };
        const epoch = accountingEpoch;
        jobs.add(job);
        event(path, "requests", epoch);
        const timer = setTimeout(() => job.fail(504, "REQUEST_TIMEOUT"), deadlineMs);
        function finish() {
            if (job.done) return false;
            job.done = true;
            clearTimeout(timer);
            if (claimed) { clearTimeout(claimed.timer); claimed.job = null; }
            if (job.reserved) {
                if (job.dispatched && !job.ownerResolved) unresolvedCreations++;
                ownerReservations--;
                job.reserved = false;
            }
            jobs.delete(job);
            job.result = null;
            if (job.transport) job.transport.abort();
            return true;
        }
        job.fail = (status, reason) => {
            if (!finish()) return;
            if (claimed) claimed.failed = true;
            event(path, "failed", epoch);
            json(res, status, { status: reason });
        };
        job.drop = () => {
            if (!finish()) return;
            if (claimed) claimed.dropped = true;
            event(path, "dropped", epoch);
            res.destroy();
        };
        job.deliver = () => {
            if (job.done || !job.result) return;
            const result = job.result;
            if (!finish()) return;
            res.once("finish", () => {
                if (claimed) claimed.delivered = true;
                event(path, "delivered", epoch);
            });
            res.writeHead(result.status, result.headers);
            res.end(result.body);
        };
        if (claimed) claimed.job = job;
        res.on("close", () => {
            if (!job.done && !res.writableFinished) {
                if (claimed) claimed.aborted = true;
                event(path, "aborted", epoch);
                finish();
            }
        });
        collect(req, maxBytes, (error, body) => {
            if (job.done) return;
            if (error) return job.fail(413, "REQUEST_BODY_ERROR");
            if (path === "/test/email-change-owner") {
                if (unresolvedCreations) return job.fail(409, "UNRESOLVED_CREATION_REQUIRES_RECONCILIATION");
                if (owners.size + ownerReservations >= maxOwners) return job.fail(503, "OWNER_CAPACITY");
                ownerReservations++;
                job.reserved = true;
            }
            if (["/test/revoke-session", "/test/cleanup-owner", "/test/owner-session-state"].includes(path)) {
                let input;
                try { input = JSON.parse(body.toString("utf8")); } catch (_) { return job.fail(400, "INVALID_OWNER_BODY"); }
                if (!input || typeof input !== "object") return job.fail(400, "INVALID_OWNER_BODY");
                const owned = path === "/test/cleanup-owner" ? owners.has(input.fixtureId) || cleaned.has(input.fixtureId) :
                    [...owners.values(), ...cleaned.values()].some(owner => owner.handle === input.handle &&
                        (path !== "/test/owner-session-state" || owner.fixtureId === input.fixtureId));
                if (!owned) return job.fail(409, "FIXTURE_NOT_OWNED_BY_PROXY");
            }
            const forwardedPath = path === PROFILE ? "/test/protected" + req.url.slice(PROFILE.length) : req.url;
            job.dispatched = true;
            job.transport = upstream(forwardedPath, req.method, req.headers, body, (upstreamError, result) => {
                if (job.done) return;
                if (upstreamError) return job.fail(502, "UPSTREAM_FAILED");
                event(path, "responses", epoch);
                if (path === "/test/capabilities") {
                    let report;
                    try { report = capability(result); } catch (_) { return job.fail(502, "INVALID_CAPABILITY_RESPONSE"); }
                    result.body = Buffer.from(JSON.stringify(report));
                    result.headers["content-length"] = result.body.length;
                    result.headers["content-type"] = "application/json";
                    for (const name of ["etag", "content-md5", "digest"]) delete result.headers[name];
                }
                if (path === "/test/email-change-owner" && result.status === 200) {
                    let owner;
                    try { owner = JSON.parse(result.body.toString("utf8")); } catch (_) { return job.fail(502, "INVALID_OWNER_RESPONSE"); }
                    if (!owner || typeof owner.fixtureId !== "string" || !owner.fixtureId
                            || typeof owner.handle !== "string" || !owner.handle) return job.fail(502, "INVALID_OWNER_RESPONSE");
                    owners.set(owner.fixtureId, { fixtureId: owner.fixtureId, handle: owner.handle });
                    job.ownerResolved = true;
                }
                if (["/test/cleanup-owner", "/test/revoke-session"].includes(path) && result.status === 200) {
                    let input, output;
                    try { input = JSON.parse(body.toString("utf8")); output = JSON.parse(result.body.toString("utf8")); }
                    catch (_) { return job.fail(502, "INVALID_CLEANUP_RESPONSE"); }
                    if (!output || typeof output !== "object") return job.fail(502, "INVALID_CLEANUP_RESPONSE");
                    const cleanupById = path === "/test/cleanup-owner";
                    if (cleanupById ? output.cleaned === true : output.ownerCleaned === true) {
                        for (const [id, owner] of owners) {
                            if (cleanupById ? id === input.fixtureId : owner.handle === input.handle) {
                                owners.delete(id);
                                cleaned.set(id, owner);
                                if (cleaned.size > maxOwners) cleaned.delete(cleaned.keys().next().value);
                            }
                        }
                    }
                }
                if (claimed) {
                    claimed.responseReady = true;
                    claimed.status = result.status;
                    claimed.hasAccess = !!result.headers["st-access-token"];
                    claimed.hasRefresh = !!result.headers["st-refresh-token"];
                    claimed.hasFront = !!result.headers["front-token"];
                    claimed.accessChangedFromRequest = claimed.hasAccess &&
                        "Bearer " + result.headers["st-access-token"] !== req.headers.authorization;
                    event(path, "ready", epoch);
                }
                job.result = result;
                if (!claimed || claimed.released) job.deliver();
            });
        });
    });
    server.on("connection", socket => {
        if (sockets.size >= maxActive + 16) return socket.destroy();
        sockets.add(socket);
        const lifetime = setTimeout(() => socket.destroy(), deadlineMs * 2);
        socket.on("close", () => { clearTimeout(lifetime); sockets.delete(socket); });
    });
    server.on("clientError", (_, socket) => socket.destroy());
    return {
        server,
        state,
        close() {
            closed = true;
            reset();
            for (const request of upstreams) request.destroy();
            for (const socket of sockets) socket.destroy();
            return new Promise(resolve => server.close(resolve));
        }
    };
};
module.exports.configuration = configuration;
