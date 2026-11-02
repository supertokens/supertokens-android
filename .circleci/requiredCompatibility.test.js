const assert = require('node:assert/strict');
const test = require('node:test');
const { requirements, verifyRun, inspect, list, poll } = require('./requiredCompatibility');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const expected = { sha: 'a'.repeat(40), repository: 'supertokens/supertokens-android', ref: 'dev-v0.6.0', workflow: 'unit.yml' };
function fixture() {
    return {
        run: { id: 21, run_attempt: 2, head_sha: expected.sha, event: 'push', head_repository: { full_name: expected.repository },
            head_branch: expected.ref, path: '.github/workflows/unit.yml', status: 'completed', conclusion: 'success' },
        jobs: [{ id: 31, name: 'JVM and API compatibility', head_sha: expected.sha, run_id: 21, run_attempt: 2, status: 'completed', conclusion: 'success' }],
    };
}
test('exact workflow, SHA, ref and attempt succeeds', () => {
    const { run, jobs } = fixture();
    assert.equal(verifyRun(run, jobs, expected), true);
});
test('both exact published upgrade and R8 device jobs are required', () => {
    const workflow = 'release-compatibility.yml';
    const names = ['Published upgrade and R8 API 21', 'Published upgrade and R8 API 34'];
    assert.deepEqual(requirements[workflow], names);
    const f = fixture();
    const exp = { ...expected, workflow };
    f.run.path = '.github/workflows/' + workflow;
    f.jobs = names.map((name, index) => ({ ...f.jobs[0], id: 31 + index, name }));
    assert.equal(verifyRun(f.run, f.jobs, exp), true);
    for (const name of names) {
        assert.throws(() => verifyRun(f.run, f.jobs.filter(job => job.name !== name), exp), /Missing or duplicate required job/);
        assert.throws(() => verifyRun(f.run, f.jobs.map(job => job.name === name ? { ...job, conclusion: 'skipped' } : job), exp), /stale, skipped, pending or failed/);
    }
});
for (const [name, mutate] of [
    ['stale SHA', f => { f.run.head_sha = 'b'.repeat(40); }],
    ['wrong workflow', f => { f.run.path = '.github/workflows/other.yml'; }],
    ['wrong repository', f => { f.run.head_repository.full_name = 'fork/android'; }],
    ['wrong ref', f => { f.run.head_branch = 'master'; }],
    ['PR merge success', f => { f.run.event = 'pull_request'; }],
    ['manual run success', f => { f.run.event = 'workflow_dispatch'; }],
    ['missing job', f => { f.jobs = []; }],
    ['duplicate job', f => { f.jobs.push({ ...f.jobs[0] }); }],
    ['stale job SHA', f => { f.jobs[0].head_sha = 'b'.repeat(40); }],
    ['old attempt', f => { f.jobs[0].run_attempt = 1; }],
    ['wrong run', f => { f.jobs[0].run_id = 20; }],
    ['skipped job', f => { f.jobs[0].conclusion = 'skipped'; }],
    ['failed job', f => { f.jobs[0].conclusion = 'failure'; }],
    ['pending job', f => { f.jobs[0].status = 'in_progress'; }],
    ['neutral job', f => { f.jobs[0].conclusion = 'neutral'; }],
    ['cancelled job', f => { f.jobs[0].conclusion = 'cancelled'; }],
    ['failed workflow', f => { f.run.conclusion = 'failure'; }],
]) test(name + ' cannot pass', () => {
    const f = fixture(); mutate(f);
    assert.throws(() => verifyRun(f.run, f.jobs, expected));
});
test('pending workflow never passes', () => {
    const f = fixture(); f.run.status = 'in_progress'; f.run.conclusion = null;
    assert.equal(verifyRun(f.run, f.jobs, expected), false);
});
test('missing workflow remains pending', async () => {
    assert.equal(await inspect(expected, 'fake', async () => ({ total_count: 0, workflow_runs: [] })), null);
});
test('latest failed run cannot be masked by older green run', async () => {
    const f = fixture();
    const get = async url => {
        if (url.includes('/workflows/')) return { total_count: 2, workflow_runs: [{ ...f.run, id: 20 }, f.run] };
        if (url.includes('/jobs')) return { total_count: 1, jobs: f.jobs };
        assert.ok(url.endsWith('/runs/21'));
        return { ...f.run, conclusion: 'failure' };
    };
    await assert.rejects(inspect(expected, 'fake', get));
});
test('job retrieval is pinned to the latest attempt', async () => {
    const f = fixture();
    const paths = [];
    const get = async url => {
        paths.push(url);
        if (url.includes('/workflows/')) return { total_count: 1, workflow_runs: [f.run] };
        if (url.includes('/jobs')) return { total_count: 1, jobs: f.jobs };
        return f.run;
    };
    assert.equal((await inspect(expected, 'fake', get)).attempt, 2);
    assert.ok(paths.some(url => url.includes('/runs/21/attempts/2/jobs')));
});
for (const message of ['HTTP 401', 'HTTP 403', 'HTTP 429', 'HTTP 500', 'timeout', 'invalid JSON']) {
    test(message + ' never passes', async () => {
        await assert.rejects(inspect(expected, 'fake', async () => { throw new Error(message); }));
    });
}
test('pagination reads all jobs', async () => {
    assert.deepEqual(await list('/jobs', 'jobs', 'fake', async url => ({ total_count: 2, jobs: [url.includes('&page=1') ? 1 : 2] })), [1, 2]);
});
test('truncated pagination fails closed', async () => {
    await assert.rejects(list('/jobs', 'jobs', 'fake', async () => ({ total_count: 2, jobs: [] })));
});
for (const object of ['run', 'job']) {
    for (const field of object === 'run' ? ['id', 'run_attempt'] : ['id', 'run_id', 'run_attempt']) {
        for (const value of [undefined, null, 0, -1, '2', 1.5, Number.MAX_SAFE_INTEGER + 1]) {
            test(`${object}.${field} rejects ${String(value)}`, () => {
                const f = fixture();
                (object === 'run' ? f.run : f.jobs[0])[field] = value;
                assert.throws(() => verifyRun(f.run, f.jobs, expected));
            });
        }
    }
}
test('matching undefined IDs cannot pass', () => {
    const f = fixture();
    delete f.run.id; delete f.run.run_attempt; delete f.jobs[0].run_id; delete f.jobs[0].run_attempt;
    assert.throws(() => verifyRun(f.run, f.jobs, expected));
});
for (const field of ['head_sha', 'event', 'head_repository', 'path', 'head_branch', 'status', 'conclusion']) {
    test('missing run field ' + field + ' rejected', () => {
        const f = fixture(); delete f.run[field];
        assert.throws(() => verifyRun(f.run, f.jobs, expected));
    });
}
for (const field of ['name', 'head_sha', 'status', 'conclusion']) {
    test('missing job field ' + field + ' rejected', () => {
        const f = fixture(); delete f.jobs[0][field];
        assert.throws(() => verifyRun(f.run, f.jobs, expected));
    });
}
test('malformed list run cannot form an API detail URL', async () => {
    let calls = 0;
    await assert.rejects(inspect(expected, 'fake', async () => {
        calls++;
        return { total_count: 1, workflow_runs: [{ ...fixture().run, id: '21' }] };
    }));
    assert.equal(calls, 1);
});
for (const value of [null, false]) test('poll timeout blocks ' + value + ' statuses', async () => {
    let clock = 0, checks = 0;
    await assert.rejects(poll(async () => { checks++; return [value, value, value, value]; }, {
        now: () => clock, sleep: async ms => { clock += ms; }, timeout: 60000,
    }), /Timed out/);
    assert.equal(clock, 60000); assert.equal(checks, 2);
});
test('poll does not accept results arriving after deadline', async () => {
    let clock = 0;
    await assert.rejects(poll(async () => { clock = 101; return [true, true, true, true]; }, {
        now: () => clock, sleep: async () => {}, timeout: 100,
    }), /Timed out/);
});
test('well-formed complete results finish polling', async () => {
    assert.deepEqual(await poll(async () => [true, true, true, true]), [true, true, true, true]);
});
for (const failingGate of ['integrationReceipt.js', 'requiredCompatibility.js']) {
    test('publisher never calls curl when ' + failingGate + ' fails', () => {
        const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'publisher-gate-'));
        try {
            fs.writeFileSync(path.join(temporary, 'node'), '#!/bin/bash\nif [ "$1" = "' + failingGate + '" ]; then exit 1; fi\nexit 0\n', { mode: 0o700 });
            const marker = path.join(temporary, 'published');
            fs.writeFileSync(path.join(temporary, 'curl'), '#!/bin/bash\ntouch "$PUBLISH_MARKER"\n', { mode: 0o700 });
            const result = spawnSync('bash', [path.join(__dirname, 'markAsSuccess.sh')], {
                env: { ...process.env, PATH: temporary + ':' + process.env.PATH, PUBLISH_MARKER: marker }, timeout: 3000,
            });
            assert.equal(result.status, 1);
            assert.equal(fs.existsSync(marker), false);
        } finally { fs.rmSync(temporary, { recursive: true }); }
    });
}
