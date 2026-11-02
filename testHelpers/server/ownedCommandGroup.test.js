const assert = require('assert');
const fs = require('fs');
const vm = require('vm');
const path = require('path');
const { test } = require('node:test');

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
