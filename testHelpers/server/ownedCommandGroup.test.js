const assert = require('assert');
const fs = require('fs');
const vm = require('vm');
const path = require('path');
const { test } = require('node:test');
const { fork } = require('child_process');

function discovery(files, ps) {
    const source = fs.readFileSync(path.join(__dirname, 'ownedCommandGroup.js'), 'utf8') + '\nmodule.exports.taggedPids = taggedPids; module.exports.linuxIdentityMatches = linuxIdentityMatches;';
    const fakeFs = {
        readdirSync: () => Object.keys(files),
        readFileSync: name => {
            const [pid, file] = name.split('/').slice(-2);
            const value = files[pid][file];
            if (value instanceof Error) throw value;
            if (value === undefined) throw Object.assign(new Error('gone'), { code: 'ENOENT' });
            return Buffer.from(typeof value === 'function' ? value() : value);
        }
    };
    const sandbox = { module: { exports: {} }, process: { platform: 'linux', env: {}, getuid: () => 501 },
        require: name => name === 'fs' ? fakeFs : name === 'child_process' ? { execFileSync: ps } : require(name),
        __dirname, Buffer, Set, console, setTimeout };
    vm.runInNewContext(source, sandbox, { filename: 'ownedCommandGroup.js' });
    return sandbox.module.exports;
}

function stat(pid, group, ticks) {
    const fields = Array(20).fill('0');
    fields[0] = 'S'; fields[1] = '1'; fields[2] = String(group); fields[19] = String(ticks);
    return `${pid} (command with ) spaces) ${fields.join(' ')}`;
}

test('Linux discovery avoids incompatible ps and matches only exact environment entries', () => {
    const { taggedPids } = discovery({
        10: { stat: stat(10, 10, 123), environ: 'ST_TEST_COMMAND_ID=abc\0OTHER=x\0' },
        11: { stat: stat(11, 11, 124), environ: 'ST_TEST_COMMAND_ID=abcd\0' },
        12: { stat: stat(12, 12, 125), environ: 'OTHER=ST_TEST_COMMAND_ID=abc\0' }
    }, () => { throw new Error("personality 'must set personality to get -x option'"); });
    assert.deepStrictEqual(Array.from(taggedPids('abc').keys()), [10]);
});

test('Linux discovery fails closed for inaccessible processes and tolerates exits', () => {
    const denied = Object.assign(new Error('permission denied'), { code: 'EACCES' });
    const gone = Object.assign(new Error('gone'), { code: 'ENOENT' });
    const tagged = discovery({ 10: { stat: stat(10, 10, 1), environ: gone }, 11: { stat: stat(11, 11, 2), environ: denied } }, () => {}).taggedPids;
    assert.throws(() => tagged('abc'), /command drain unproven/);
    assert.deepStrictEqual(Array.from(discovery({ 10: { stat: stat(10, 10, 1), environ: gone } }, () => {}).taggedPids('abc').keys()), []);
});

test('Linux discovery skips only processes proven older than the supervisor', () => {
    const denied = Object.assign(new Error('permission denied'), { code: 'EACCES' });
    const bound = { pid: 10, group: 10, startTicks: '9007199254740993' };
    const files = {
        1: { stat: stat(1, 1, '9007199254740992'), environ: denied },
        10: { stat: stat(10, 10, bound.startTicks), environ: 'ST_TEST_COMMAND_ID=abc\0' }
    };
    assert.deepStrictEqual(Array.from(discovery(files, () => {}).taggedPids('abc', bound).keys()), [10]);
    for (const ticks of [bound.startTicks, '9007199254740994']) {
        files[1].stat = stat(1, 1, ticks);
        assert.throws(() => discovery(files, () => {}).taggedPids('abc', bound), /command drain unproven/);
    }
    files[1].stat = denied;
    assert.throws(() => discovery(files, () => {}).taggedPids('abc', bound), /command drain unproven/);
});

test('Linux refuses a reused PID even within the same wall-clock second', () => {
    let ticks = 100;
    const { taggedPids, linuxIdentityMatches } = discovery({ 42: {
        stat: () => stat(42, 42, ticks), environ: 'ST_TEST_COMMAND_ID=abc\0'
    } }, () => {});
    const tagged = taggedPids('abc');
    const member = { pid: 42, group: 42, startTicks: tagged.get(42).startTicks };
    assert.strictEqual(linuxIdentityMatches(member, tagged), true);
    ticks++;
    assert.strictEqual(linuxIdentityMatches(member, tagged), false);
});

test('Linux rejects identity changes during environment inspection', () => {
    let ticks = 100;
    const { taggedPids } = discovery({ 42: {
        stat: () => stat(42, 42, ticks++), environ: 'ST_TEST_COMMAND_ID=abc\0'
    } }, () => {});
    assert.throws(() => taggedPids('abc'), /command drain unproven/);
});

test('Linux refuses malformed stat and tolerates disappearing processes', () => {
    const bound = { pid: 10, group: 10, startTicks: '10' };
    const gone = Object.assign(new Error('gone'), { code: 'ENOENT' });
    assert.throws(() => discovery({ 1: { stat: 'malformed' } }, () => {}).taggedPids('abc', bound), /command drain unproven/);
    assert.deepStrictEqual(Array.from(discovery({ 1: { stat: gone } }, () => {}).taggedPids('abc', bound).keys()), []);
});

test('discovery rechecks the live leader and retains its bound after verified exit', () => {
    let leaderTicks = 10;
    let mutate = false;
    const files = {
        1: { stat: stat(1, 1, 1), environ: Object.assign(new Error('denied'), { code: 'EACCES' }) },
        10: { stat: () => stat(10, 10, leaderTicks), environ: () => {
            if (mutate) leaderTicks++;
            return 'ST_TEST_COMMAND_ID=abc\0';
        } }
    };
    const group = new (discovery(files, () => {}).OwnedCommandGroup)('test');
    group.token = 'abc';
    group.group = 10;
    group.owner = { child: { exitCode: null, signalCode: null }, verify() {} };
    group.verifyLinuxLeader();
    assert.deepStrictEqual(Array.from(group.discover().keys()), [10]);
    mutate = true;
    assert.throws(() => group.discover(), /command drain unproven/);
    mutate = false;
    leaderTicks = 10;
    group.owner.child.exitCode = 0;
    delete files[10];
    assert.deepStrictEqual(Array.from(group.discover().keys()), []);
});

test('supervisor exits promptly when startup IPC is disconnected', { timeout: 5000 }, async () => {
    const child = fork(path.join(__dirname, 'ordinarySupervisor.js'), [], {
        stdio: ['ignore', 'ignore', 'pipe', 'ipc'], env: { ...process.env, BASH_ENV: '', ENV: '' }
    });
    let stderr = '';
    child.stderr.on('data', data => { stderr += data; });
    const exit = new Promise((resolve, reject) => {
        child.once('error', reject);
        child.once('exit', (code, signal) => resolve({ code, signal }));
    });
    child.disconnect();
    try {
        const result = await Promise.race([exit, new Promise((_, reject) => {
            const timer = setTimeout(() => reject(new Error('supervisor did not exit')), 3000);
            timer.unref();
        })]);
        assert.deepStrictEqual(result, { code: 0, signal: null }, stderr);
        assert.strictEqual(stderr, '');
    } finally {
        if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
    }
});

test('supervisor handles IPC EPIPE but propagates unexpected send errors', () => {
    const source = fs.readFileSync(path.join(__dirname, 'ordinarySupervisor.js'), 'utf8');
    function run(code) {
        let exited = false;
        const fakeProcess = {
            connected: true, on() {}, disconnect() { this.connected = false; },
            exit() { exited = true; }, send(message, callback) { callback(Object.assign(new Error(code), { code })); }
        };
        vm.runInNewContext(source, { require, process: fakeProcess, setInterval() {} });
        return exited && !fakeProcess.connected;
    }
    assert.strictEqual(run('EPIPE'), true);
    assert.throws(() => run('EIO'), /EIO/);
});
