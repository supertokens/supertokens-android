const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');
const { cgroup, counters } = require('./matrixDiagnostics');

function test(name, run) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'matrix-diagnostics-'));
    try {
        run(dir);
        console.log('PASS ' + name);
    } finally {
        fs.rmSync(dir, { recursive: true, force: true });
    }
}

test('cgroup v2 reads only bounded memory counters', dir => {
    const mount = path.join(dir, 'mount');
    fs.mkdirSync(path.join(mount, 'job'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'groups'), '0::/job\n');
    fs.writeFileSync(path.join(dir, 'mounts'), `1 0 0:1 / ${mount} rw - cgroup2 cgroup rw\n`);
    fs.writeFileSync(path.join(mount, 'job/memory.max'), '1024\n');
    fs.writeFileSync(path.join(mount, 'job/memory.current'), '512\n');
    fs.writeFileSync(path.join(mount, 'job/memory.events'), 'oom 2\noom_kill 1\nsecret 123\n');
    assert.deepEqual(cgroup(path.join(dir, 'groups'), path.join(dir, 'mounts')), {
        version: 2, limit: '1024', current: '512', events: { oom: '2', oom_kill: '1' }, failcnt: null
    });
    assert.deepEqual(counters('oom 1\noom 1 secret\n', ['oom']), { oom: '1' });
});

test('cgroup v1 reads memory controller counters', dir => {
    fs.writeFileSync(path.join(dir, 'groups'), '4:memory:/parent/job\n');
    fs.writeFileSync(path.join(dir, 'mounts'), `1 0 0:1 /parent ${dir} rw - cgroup cgroup rw,memory\n`);
    fs.mkdirSync(path.join(dir, 'job'));
    fs.writeFileSync(path.join(dir, 'job/memory.limit_in_bytes'), '2048');
    fs.writeFileSync(path.join(dir, 'job/memory.usage_in_bytes'), '512');
    fs.writeFileSync(path.join(dir, 'job/memory.failcnt'), '3');
    fs.writeFileSync(path.join(dir, 'job/memory.oom_control'), 'under_oom 0\noom_kill 1\n');
    assert.deepEqual(cgroup(path.join(dir, 'groups'), path.join(dir, 'mounts')), {
        version: 1, limit: '2048', current: '512', events: { under_oom: '0', oom_kill: '1' }, failcnt: '3'
    });
});

test('ambiguous namespaced membership is explicitly unavailable rather than reading a mount root', dir => {
    fs.writeFileSync(path.join(dir, 'groups'), '0::/\n');
    fs.writeFileSync(path.join(dir, 'mounts'), `1 0 0:1 /host/subtree ${dir} rw - cgroup2 cgroup rw\n`);
    fs.writeFileSync(path.join(dir, 'memory.current'), '999');
    assert.deepEqual(cgroup(path.join(dir, 'groups'), path.join(dir, 'mounts')),
        { version: null, reason: 'membership-not-resolvable' });
    fs.writeFileSync(path.join(dir, 'mounts'), `1 0 0:1 /.. ${dir} rw - cgroup2 cgroup rw\n`);
    assert.deepEqual(cgroup(path.join(dir, 'groups'), path.join(dir, 'mounts')),
        { version: null, reason: 'membership-not-resolvable' });
});

test('wrapper retains failure and permits outer cleanup even when diagnostics cannot write', dir => {
    const wrapper = path.join(__dirname, 'diagnoseOwnedMatrix.sh');
    for (const broken of [false, true]) {
        const install = path.join(dir, broken ? 'file' : 'instance');
        if (broken) fs.writeFileSync(install, 'not a directory');
        else {
            fs.mkdirSync(path.join(install, 'matrix'), { recursive: true });
            fs.writeFileSync(path.join(install, 'matrix/steps.json'), JSON.stringify([
                { name: 'root-build', status: 'failed', exit: 137, signal: null, secret: 'do-not-copy' }
            ]));
        }
        const marker = path.join(dir, broken ? 'cleanup-broken' : 'cleanup');
        const result = spawnSync('bash', ['-c', 'trap \'touch "$1"\' EXIT; bash "$2" bash -c "exit 23"', '_', marker, wrapper],
            { env: { ...process.env, INSTALL_PATH: install }, encoding: 'utf8' });
        assert.equal(result.status, 23);
        assert.equal(fs.existsSync(marker), true);
        if (!broken) {
            const before = JSON.parse(fs.readFileSync(path.join(install, 'diagnostics/matrix-before.json')));
            const raw = fs.readFileSync(path.join(install, 'diagnostics/matrix-after.json'), 'utf8');
            const after = JSON.parse(raw);
            assert.equal(before.phase, 'before');
            assert.equal(after.commandExit, 23);
            assert.deepEqual(after.rootBuild, { status: 'failed', exit: 137, signal: null });
            assert.doesNotMatch(raw, /do-not-copy/);
            const success = spawnSync('bash', [wrapper, 'bash', '-c', 'exit 0'],
                { env: { ...process.env, INSTALL_PATH: install } });
            assert.equal(success.status, 0);
            assert.equal(JSON.parse(fs.readFileSync(path.join(install, 'diagnostics/matrix-after.json'))).commandExit, 0);
        }
    }
});
