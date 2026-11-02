const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn, execFileSync } = require('child_process');
const { test } = require('node:test');

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

for (const mode of ['startup', 'drain', 'normal', 'open-failure', 'fsync-failure']) test(`ordinary wrapper handles ${mode} cleanup`, async () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ordinary-retention-'));
    const server = path.join(root, 'server');
    const source = path.join(root, 'source');
    const evidence = path.join(root, 'evidence');
    fs.mkdirSync(server); fs.mkdirSync(source); fs.mkdirSync(evidence);
    fs.mkdirSync(path.join(server, 'node_modules'));
    fs.mkdirSync(path.join(source, 'core')); fs.mkdirSync(path.join(source, 'plugin-interface'));
    fs.writeFileSync(path.join(source, 'version.yaml'), 'version: test\n');
    for (const name of fs.readdirSync(__dirname).filter(name => name.endsWith('.js'))) {
        fs.copyFileSync(path.join(__dirname, name), path.join(server, name));
    }
    const preload = path.join(root, 'preload.js');
    fs.writeFileSync(preload, `
const Module = require('module');
const fs = require('fs');
const cp = require('child_process');
const load = Module._load;
let scan = 0;
const failAt = ${['startup', 'open-failure', 'fsync-failure'].includes(mode) ? 1 : mode === 'drain' ? 2 : 'Infinity'};
let recordFd;
const open = fs.openSync;
fs.openSync = function(name, ...args) {
    if (String(name).endsWith('/RETAINED.json')) {
        if (${mode === 'open-failure'}) throw Object.assign(new Error('fixture open failed'), {code:'EIO'});
        recordFd = open.call(this, name, ...args);
        return recordFd;
    }
    return open.call(this, name, ...args);
};
const fsync = fs.fsyncSync;
fs.fsyncSync = function(fd) {
    if (${mode === 'fsync-failure'} && fd === recordFd) throw Object.assign(new Error('fixture fsync failed'), {code:'EIO'});
    return fsync.call(this, fd);
};
const spawn = cp.spawn;
cp.spawn = function(command, args, ...options) {
    const child = spawn.call(this, command, args, ...options);
    if (args.some(arg => String(arg).endsWith('/ordinarySupervisor.js'))) {
        fs.writeFileSync(${JSON.stringify(path.join(root, 'supervisor.pid'))}, String(child.pid));
    }
    return child;
};
const readdir = fs.readdirSync;
fs.readdirSync = function(name, ...args) {
    if (name === '/proc') scan++;
    return readdir.call(this, name, ...args);
};
const read = fs.readFileSync;
fs.readFileSync = function(name, ...args) {
    if (scan >= failAt && /^\\/proc\\/\\d+\\/environ$/.test(String(name))) throw Object.assign(new Error('inspection denied'), {code:'EACCES'});
    return read.call(this, name, ...args);
};
const exec = cp.execFileSync;
cp.execFileSync = function(name, args, ...rest) {
    if (name === 'ps' && args[0] === 'eww' && ++scan >= failAt) throw new Error('inspection denied');
    return exec.call(this, name, args, ...rest);
};
const kill = process.kill;
process.kill = function(pid, signal) {
    fs.appendFileSync(${JSON.stringify(path.join(root, 'signals'))}, String(pid) + ' ' + signal + '\\n');
    return kill.call(this, pid, signal);
};
Module._load = function(name, parent, ...args) {
    if (parent && parent.filename.endsWith('/runOrdinary.js') && name === './ownedProcess') return { OwnedProcess: class {
        async start(command, args, options) {
            this.child = cp.spawn(process.execPath, ['-e', 'setInterval(()=>{},1000)', ${JSON.stringify(root)}], {stdio:'ignore'});
            fs.writeFileSync(${JSON.stringify(path.join(root, 'backend.pid'))}, String(this.child.pid));
            this.fingerprint = 'fixture'; return this.child;
        }
        verifyDetachedGroup() {}
        async stop() { fs.writeFileSync(${JSON.stringify(path.join(root, 'backend-stopped'))}, 'yes'); this.child.kill(); }
    }};
    if (parent && parent.filename.endsWith('/runOrdinary.js') && name === './ownedBackendRequest') return {ownedBackendRequest: async () => {}};
    if (parent && parent.filename.endsWith('/runOrdinary.js') && name === './ownedCore') return {unusedPort: async () => {}};
    return load.call(this, name, parent, ...args);
};
`);
    const marker = path.join(root, 'command-started');
    const finished = path.join(root, 'command-finished');
    const command = `require('fs').writeFileSync(${JSON.stringify(marker)},'yes');setTimeout(()=>require('fs').writeFileSync(${JSON.stringify(finished)},'yes'),${mode === 'normal' ? 100 : 3000})`;
    const env = { ...process.env }; delete env.BASH_ENV; delete env.ENV;
    const output = fs.openSync(path.join(root, 'output.log'), 'w');
    const child = spawn(process.execPath, ['-r', preload, path.join(server, 'runOrdinary.js'), source, evidence,
        '18081', '19001', process.execPath, '-e', command], { env, stdio: ['ignore', output, output] });
    fs.closeSync(output);
    const exited = new Promise(resolve => child.once('exit', (code, signal) => resolve({ code, signal })));
    let passed = false;
    try {
        if (mode === 'drain') {
            for (let i = 0; i < 200 && !fs.existsSync(marker); i++) await delay(10);
            assert.ok(fs.existsSync(marker), `command did not start; evidence ${root}: ${fs.readFileSync(path.join(root, 'output.log'))}`);
            child.kill('SIGINT');
        }
        const result = await Promise.race([exited, delay(4000).then(() => null)]);
        assert.ok(result, 'wrapper hung after discovery failure');
        const runs = fs.readdirSync(evidence).map(name => path.join(evidence, name));
        if (mode === 'normal') {
            assert.strictEqual(result.code, 0, String(fs.readFileSync(path.join(root, 'output.log'))));
            assert.ok(runs.some(name => fs.existsSync(path.join(name, 'lifecycle-completed.json'))));
            assert.ok(fs.existsSync(path.join(root, 'backend-stopped')));
        } else {
            assert.notStrictEqual(result.code, 0);
            const run = runs.find(name => fs.existsSync(path.join(name, 'RETAINED.json')));
            if (mode === 'open-failure' || mode === 'fsync-failure') {
                assert.match(String(fs.readFileSync(path.join(root, 'output.log'))), /retention record or diagnostic failed/);
            } else {
                assert.ok(run, `retention record missing; evidence ${root}: ${fs.readFileSync(path.join(root, 'output.log'))}`);
                assert.match(JSON.parse(fs.readFileSync(path.join(run, 'RETAINED.json'))).error, /inspection|unproven/);
            }
            assert.ok(!fs.existsSync(path.join(root, 'backend-stopped')), 'backend was signaled');
            assert.ok(!fs.existsSync(path.join(root, 'signals')), 'wrapper sent a signal');
            if (mode === 'drain') {
                await delay(3300);
                const pid = JSON.parse(fs.readFileSync(path.join(run, 'RETAINED.json'))).commandGroup;
                assert.ok(fs.existsSync(finished), 'retained command did not finish');
                assert.doesNotMatch(String(fs.readFileSync(path.join(root, 'output.log'))), /ERR_IPC_CHANNEL_CLOSED|Error \[ERR_IPC/);
                let status = '';
                try { status = execFileSync('ps', ['-p', String(pid), '-o', 'stat='], { encoding: 'utf8' }).trim(); }
                catch (error) { if (error.status !== 1) throw error; }
                assert.ok(!status || status.startsWith('Z'), 'disconnected supervisor did not exit');
            }
        }
        passed = true;
    } finally {
        if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
        await exited;
        if (fs.existsSync(path.join(root, 'supervisor.pid'))) {
            const pid = Number(fs.readFileSync(path.join(root, 'supervisor.pid'), 'utf8'));
            killFixture(pid, path.join(server, 'ordinarySupervisor.js'));
        }
        if (fs.existsSync(path.join(root, 'backend.pid'))) {
            const pid = Number(fs.readFileSync(path.join(root, 'backend.pid'), 'utf8'));
            killFixture(pid, root);
        }
        if (passed) fs.rmSync(root, { recursive: true, force: true });
    }
});

function killFixture(pid, expected) {
    let args;
    try { args = execFileSync('ps', ['-p', String(pid), '-o', 'args='], { encoding: 'utf8' }); }
    catch (error) { if (error.status === 1) return; throw error; }
    assert.ok(args.includes(expected), 'refusing to signal a different process');
    try { process.kill(pid, 'SIGKILL'); } catch (error) { if (error.code !== 'ESRCH') throw error; }
}
