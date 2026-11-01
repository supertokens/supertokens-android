const { spawn, execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

function identity(pid) {
    return execFileSync('ps', ['-ww', '-p', String(pid), '-o', 'pid=,ppid=,pgid=,lstart=,args='],
        { encoding: 'utf8', env: { ...process.env, LC_ALL: 'C' } }).trim();
}

function processGroup(pid) {
    return Number(execFileSync('ps', ['-p', String(pid), '-o', 'pgid='], { encoding: 'utf8' }).trim());
}

class OwnedProcess {
    constructor(runId, inspect = identity) {
        this.runId = runId;
        this.inspect = inspect;
        this.child = undefined;
    }

    async start(command, args, options) {
        if (this.child) throw new Error('Owned process already started; stop it first');
        const child = spawn(command, args, options);
        this.child = child;
        this.fingerprint = undefined;
        this.launch = { command, args, cwd: fs.realpathSync(options.cwd || process.cwd()) };
        try {
            await new Promise((resolve, reject) => {
                child.once('spawn', resolve);
                child.once('error', reject);
            });
        } catch (error) {
            this.child = undefined;
            throw error;
        }
        // Capture before readiness checks, including failures/timeouts.
        this.fingerprint = this.inspect(child.pid);
        return child;
    }

    verify() {
        if (!this.child || this.child.exitCode !== null || this.child.signalCode !== null ||
            !this.fingerprint || this.inspect(this.child.pid) !== this.fingerprint) {
            throw new Error('Process identity changed; refusing operation');
        }
    }

    verifyDetachedGroup() {
        this.verify();
        if (processGroup(this.child.pid) !== this.child.pid || processGroup(process.pid) === this.child.pid) {
            throw new Error('Owned child is not isolated from the caller process group');
        }
    }

    recoverIdentity(runId) {
        if (runId !== this.runId || !this.child) throw new Error('Foreign run or missing child');
        if (this.fingerprint) return this.verify();
        const metadata = require('./coreRuntimeIdentity').inspect(this.child.pid, 1);
        const parent = Number(execFileSync('ps', ['-p', String(this.child.pid), '-o', 'ppid='], { encoding: 'utf8' }).trim());
        if (parent !== process.pid || metadata.cwd !== this.launch.cwd ||
            path.basename(metadata.argv[0]) !== path.basename(this.launch.command) ||
            JSON.stringify(metadata.argv.slice(1)) !== JSON.stringify(this.launch.args)) {
            throw new Error('Retained child no longer matches launch; recovery refused');
        }
        this.fingerprint = this.inspect(this.child.pid);
    }

    async stop(runId, pid) {
        if (runId !== this.runId) throw new Error('Foreign run ID');
        const child = this.child;
        if (pid !== undefined && (!child || String(pid).trim() !== String(child.pid))) {
            throw new Error('Foreign PID');
        }
        if (!child) return;
        const exited = () => child.exitCode !== null || child.signalCode !== null;
        for (const [signal, timeout] of [['SIGTERM', 5000], ['SIGKILL', 2000]]) {
            if (exited()) break;
            if (!this.fingerprint || this.inspect(child.pid) !== this.fingerprint) {
                throw new Error('Process identity changed; refusing termination');
            }
            child.kill(signal);
            const deadline = Date.now() + timeout;
            while (!exited() && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 25));
        }
        if (!exited()) throw new Error('Owned process failed to exit');
        this.child = undefined;
    }
}

module.exports = { OwnedProcess, identity, processGroup };
