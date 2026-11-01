const assert = require('assert');
const fs = require('fs');
const path = require('path');
const http = require('http');
const { OwnedProcess, identity } = require('./ownedProcess');
const { OwnedCommandGroup, processTable } = require('./ownedCommandGroup');
const { ownedBackendRequest } = require('./ownedBackendRequest');
const { environment, snapshotDependencies, digestTree } = require('./ordinaryEnvironment');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function main() {
    const temporary = fs.mkdtempSync(path.join(process.argv[2], 'review-controls-'));
    const retained = new OwnedProcess('run', () => { throw new Error('inspection unavailable'); });
    await assert.rejects(retained.start(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { cwd: temporary, stdio: 'ignore' }), /inspection unavailable/);
    assert.ok(retained.child);
    await assert.rejects(retained.stop('run'), /identity changed/);
    retained.inspect = identity;
    retained.recoverIdentity('run');
    await retained.stop('run');
    console.log('PASS: inspection failure retains child; explicit launch-verified recovery stops it');
    const startup = new OwnedCommandGroup('startup');
    await startup.start([process.execPath, '-e', 'setInterval(()=>{},1000)'], { cwd: temporary, env: { PATH: process.env.PATH } });
    await startup.drain();
    assert.ok(!processTable().some(row => row.group === startup.group && !row.zombie));
    const failedStart = new OwnedCommandGroup('failed-start');
    await failedStart.start(['/nonexistent/owned-command'], { cwd: temporary, env: { PATH: process.env.PATH } });
    assert.strictEqual(await failedStart.done, 1);
    await failedStart.drain();
    console.log('PASS: drain immediately after start() resolves, and command spawn failure drain before cleanup');

    for (const mode of ['normal', 'interrupt', 'resistant', 'detached']) {
        const marker = path.join(temporary, mode + '.pid');
        const worker = `require('fs').writeFileSync(${JSON.stringify(marker)},String(process.pid));` +
            (['resistant', 'detached'].includes(mode) ? `process.on('SIGTERM',()=>{});` : '') + `setInterval(()=>{},1000);`;
        const command = `require('child_process').spawn(process.execPath,['-e',${JSON.stringify(worker)}],{stdio:'ignore',detached:${mode === 'detached'}});` +
            (mode === 'normal' ? `setTimeout(()=>process.exit(0),300);` : `setInterval(()=>{},1000);`);
        const entries = [];
        const group = new OwnedCommandGroup('group-' + mode, entry => entries.push(entry));
        await group.start([process.execPath, '-e', command], { cwd: temporary, env: { PATH: process.env.PATH } });
        for (let i = 0; i < 200 && !fs.existsSync(marker); i++) await delay(20);
        assert.ok(fs.existsSync(marker), 'worker must exist before testing drain');
        const pid = Number(fs.readFileSync(marker, 'utf8'));
        if (mode === 'normal') assert.strictEqual(await group.done, 0);
        await group.drain();
        assert.ok(!processTable().some(row => row.pid === pid && !row.zombie), 'worker survived drain');
        assert.ok(entries.some(entry => entry.action === 'DRAINED'));
        if (['resistant', 'detached'].includes(mode)) assert.ok(entries.some(entry => entry.action === 'SIGKILL'));
        fs.writeFileSync(path.join(temporary, mode + '.json'), JSON.stringify(entries));
        console.log('PASS: descendant drain ' + mode);
    }

    const foreign = http.createServer((req, res) => { foreign.requests++; res.end('{}'); });
    foreign.requests = 0;
    await new Promise(resolve => foreign.listen(0, '127.0.0.1', resolve));
    const decoy = new OwnedProcess('secret');
    await decoy.start(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { cwd: temporary, stdio: 'ignore' });
    try {
        await assert.rejects(ownedBackendRequest(decoy, foreign.address().port, 'instance', '/after'), /not the listener/);
        assert.strictEqual(foreign.requests, 0, 'foreign listener received HTTP');
        // Simulate takeover after OS inspection: proof must reject before sending run guard.
        await assert.rejects(ownedBackendRequest(decoy, foreign.address().port, 'instance', '/after',
            () => ({ cwd: temporary, listening: true })), /proof mismatch/);
        assert.strictEqual(foreign.requests, 1);
    } finally { await decoy.stop('secret'); await new Promise(resolve => foreign.close(resolve)); }
    console.log('PASS: listener takeover rejected before HTTP; raced takeover gets only uncredentialed challenge');

    for (const mode of ['failure', 'close']) {
        const marker = path.join(temporary, mode + '-requests.jsonl');
        const backend = new OwnedProcess('fake-run');
        const child = await backend.start(process.execPath, [path.join(__dirname, 'fakeOwnedBackend.js'), mode, marker],
            { cwd: temporary, stdio: ['ignore', 'ignore', 'ignore', 'ipc'] });
        const port = await new Promise(resolve => child.once('message', message => resolve(message.port)));
        try {
            await assert.rejects(ownedBackendRequest(backend, port, 'fake-instance', '/after'),
                mode === 'failure' ? /not acknowledged/ : /socket closed|new listener/);
            backend.verify();
            const requests = fs.readFileSync(marker, 'utf8').trim().split('\n').map(JSON.parse);
            assert.strictEqual(requests.filter(row => row.guard).length, mode === 'failure' ? 1 : 0);
        } finally { await backend.stop('fake-run'); }
    }
    console.log('PASS: failed cleanup retains backend; proof socket closure never reconnects with guard');

    const clean = environment({ PATH: '/bin', HOME: temporary, NODE_OPTIONS: '--require evil', CORE_CONNECTION_URI: 'foreign',
        ACCOUNT_LINKING_QUALIFICATION_FILE: 'foreign', API_KEY: 'secret', TRANSFER_METHOD: 'cookie' }, temporary, 18081, 19001, 'id', 'instance');
    assert.strictEqual(clean.NODE_OPTIONS, undefined);
    assert.strictEqual(clean.API_KEY, undefined);
    assert.strictEqual(clean.TRANSFER_METHOD, undefined);
    assert.strictEqual(clean.CORE_CONNECTION_URI, 'http://127.0.0.1:19001');
    const source = path.join(temporary, 'dependencies'); fs.mkdirSync(source); fs.writeFileSync(path.join(source, 'file'), 'one');
    const target = path.join(temporary, 'snapshot'); const digest = snapshotDependencies(source, target);
    fs.writeFileSync(path.join(source, 'file'), 'two');
    assert.strictEqual(digestTree(target), digest);
    console.log('PASS: env whitelist; private dependency snapshot unaffected by source mutation');
    console.log('Evidence: ' + temporary);
}
main().catch(error => { console.error(error); process.exitCode = 1; });
