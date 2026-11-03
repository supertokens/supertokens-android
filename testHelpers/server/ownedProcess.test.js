const assert = require('assert');
const { once } = require('events');
const { OwnedProcess } = require('./ownedProcess');

async function main() {
    const owner = new OwnedProcess('run-a');
    const child = await owner.start(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' });
    try {
        await assert.rejects(owner.stop('run-b'), /Foreign run/);
        await assert.rejects(owner.stop('run-a', process.pid), /Foreign PID/);
        assert.strictEqual(child.exitCode, null);
        await assert.rejects(owner.start(process.execPath, [], {}), /already started/);
        const inspect = owner.inspect;
        owner.inspect = () => 'reused PID';
        await assert.rejects(owner.stop('run-a'), /identity changed/);
        assert.strictEqual(child.signalCode, null);
        owner.inspect = inspect;
    } finally {
        await owner.stop('run-a', child.pid);
    }
    await owner.stop('run-a');
    await assert.rejects(owner.stop('run-a', process.pid), /Foreign PID/);
    const failed = new OwnedProcess('failed');
    await assert.rejects(failed.start('/nonexistent/owned-fixture-command', [], {}), /ENOENT/);
    await failed.stop('failed');
    const early = new OwnedProcess('early');
    const earlyChild = await early.start(process.execPath,
        ['-e', 'process.once("message", () => process.exit(23))'], { stdio: ['ignore', 'ignore', 'ignore', 'ipc'] });
    try {
        const exited = once(earlyChild, 'exit', { signal: AbortSignal.timeout(5000) });
        earlyChild.send('exit');
        const [code, signal] = await exited;
        assert.strictEqual(code, 23);
        assert.strictEqual(signal, null);
        early.inspect = () => { throw new Error('Exited process must not be inspected'); };
        earlyChild.kill = () => { throw new Error('Exited process must not be signaled'); };
    } finally {
        await early.stop('early');
    }
    assert.strictEqual(early.child, undefined);
    console.log('PASS: foreign run/PID, repeated start, identity mismatch, repeated cleanup, spawn failure, early exit');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
