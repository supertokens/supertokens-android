const assert = require('assert');
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
    await early.start(process.execPath, ['-e', 'process.exit(23)'], { stdio: 'ignore' });
    await new Promise(resolve => setTimeout(resolve, 150));
    await early.stop('early');
    console.log('PASS: foreign run/PID, repeated start, identity mismatch, repeated cleanup, spawn failure, early exit');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
