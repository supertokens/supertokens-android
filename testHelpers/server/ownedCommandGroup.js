const path = require('path');
const fs = require('fs');
const { execFileSync } = require('child_process');
const { OwnedProcess } = require('./ownedProcess');
const crypto = require('crypto');
const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

function processTable() {
    return execFileSync('ps', ['-axo', 'pid=,ppid=,pgid=,stat=,lstart=,args='], {
        encoding: 'utf8', env: { ...process.env, LC_ALL: 'C' }
    }).trim().split('\n').map(line => {
        const parts = line.trim().split(/\s+/);
        return { pid: Number(parts[0]), parent: Number(parts[1]), group: Number(parts[2]),
            zombie: parts[3].includes('Z'), identity: parts.slice(4).join(' ') };
    });
}

function linuxProcessIdentity(pid) {
    const stat = fs.readFileSync(`/proc/${pid}/stat`).toString('utf8');
    const close = stat.lastIndexOf(')');
    const fields = stat.slice(close + 1).trim().split(/\s+/);
    if (!stat.startsWith(`${pid} (`) || close < stat.indexOf('(') ||
        !/^\d+$/.test(fields[2]) || !/^\d+$/.test(fields[19])) {
        throw new Error(`Invalid process identity for ${pid}; command drain unproven`);
    }
    return { group: Number(fields[2]), startTicks: fields[19] };
}

function linuxIdentityMatches(member, tagged) {
    const found = tagged.get(member.pid);
    if (!found || found.group !== member.group || found.startTicks !== member.startTicks) return false;
    const current = linuxProcessIdentity(member.pid);
    return current.group === member.group && current.startTicks === member.startTicks;
}

function taggedPids(token, bound) {
    // Read-only discovery. Never persist environment output or use it as a kill command.
    if (process.platform === 'linux') {
        const tagged = new Map();
        for (const pid of fs.readdirSync('/proc').filter(name => /^\d+$/.test(name))) {
            try {
                const before = linuxProcessIdentity(pid);
                if (bound && BigInt(before.startTicks) < BigInt(bound.startTicks)) continue;
                const environment = fs.readFileSync(`/proc/${pid}/environ`);
                if (!environment.toString('utf8').split('\0').includes('ST_TEST_COMMAND_ID=' + token)) continue;
                const after = linuxProcessIdentity(pid);
                if (before.startTicks !== after.startTicks || before.group !== after.group) {
                    throw new Error(`Process ${pid} changed during inspection; command drain unproven`);
                }
                tagged.set(Number(pid), after);
            } catch (error) {
                if (error.code === 'ENOENT' || error.code === 'ESRCH') continue;
                throw new Error(`Unable to inspect process ${pid} (${error.code || 'inspection failed'}); command drain unproven`, { cause: error });
            }
        }
        return tagged;
    }
    const output = execFileSync('ps', process.platform === 'darwin'
        ? ['eww', '-axo', 'pid=,command='] : ['eww', '-axo', 'pid=,args='],
    { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
    return new Set(output.split('\n').filter(line => line.includes('ST_TEST_COMMAND_ID=' + token))
        .map(line => Number(line.trim().split(/\s+/)[0])));
}

class OwnedCommandGroup {
    constructor(runId, evidence = () => {}) {
        this.owner = new OwnedProcess(runId);
        this.evidence = evidence;
        this.token = crypto.randomBytes(24).toString('hex');
    }

    async start(command, options) {
        if (this.cancelled) throw new Error('Command startup cancelled');
        const child = await this.owner.start(process.execPath, [path.join(__dirname, 'ordinarySupervisor.js')],
            { ...options, env: { ...options.env, ST_TEST_COMMAND_ID: this.token }, detached: true,
                stdio: ['ignore', 'inherit', 'inherit', 'ipc'] });
        if (this.cancelled) throw new Error('Command startup cancelled');
        this.group = child.pid;
        this.verifyGroup();
        this.done = new Promise((resolve, reject) => {
            child.on('message', message => {
                if (message.type === 'done') resolve(message.code);
            });
            child.once('exit', () => reject(new Error('Command supervisor exited before drain')));
        });
        this.done.catch(() => {});
        const started = new Promise((resolve, reject) => {
            child.on('message', message => { if (message.type === 'started') { this.started = true; resolve(); } });
            child.once('exit', () => reject(new Error('Supervisor exited before command start')));
        });
        // Send is queued even if the supervisor has not yet installed its handler.
        child.send({ type: 'start', command });
        await started;
    }

    cancelStart() { this.cancelled = true; }

    verifyLinuxLeader() {
        this.owner.verify();
        const identity = linuxProcessIdentity(this.group);
        if (identity.group !== this.group || (this.linuxBound &&
            (this.linuxBound.pid !== this.group || this.linuxBound.group !== identity.group ||
                this.linuxBound.startTicks !== identity.startTicks))) {
            throw new Error('Owned supervisor identity changed; command drain unproven');
        }
        if (!this.linuxBound) this.linuxBound = { pid: this.group, ...identity };
    }

    discover() {
        if (process.platform !== 'linux') return taggedPids(this.token);
        if (!this.linuxBound) throw new Error('Owned supervisor identity unavailable; command drain unproven');
        const alive = () => this.owner.child.exitCode === null && this.owner.child.signalCode === null;
        if (alive()) this.verifyLinuxLeader();
        const tagged = taggedPids(this.token, this.linuxBound);
        if (alive()) this.verifyLinuxLeader();
        return tagged;
    }

    verifyGroup() {
        this.owner.verify();
        const table = processTable();
        const leader = table.find(row => row.pid === this.group);
        const caller = table.find(row => row.pid === process.pid);
        if (!leader || leader.group !== leader.pid || leader.parent !== process.pid || !caller || caller.group === this.group) {
            throw new Error('Not an isolated owned process group');
        }
        if (process.platform === 'linux') this.verifyLinuxLeader();
        const descendants = new Set([this.group]);
        for (let changed = true; changed;) {
            changed = false;
            for (const row of table) if (descendants.has(row.parent) && !descendants.has(row.pid)) {
                descendants.add(row.pid); changed = true;
            }
        }
        const tagged = this.discover();
        if (!tagged.has(this.group)) throw new Error('Owned group run identity unavailable');
        if (table.some(row => descendants.has(row.pid) && row.group !== this.group && !row.zombie && !tagged.has(row.pid))) {
            throw new Error('Unidentified escaped descendant; retain fixture for recovery');
        }
        return table.filter(row => (row.group === this.group || tagged.has(row.pid)) && !row.zombie)
            .map(row => process.platform === 'linux' && tagged.has(row.pid)
                ? { ...row, startTicks: tagged.get(row.pid).startTicks } : row);
    }

    async drain() {
        this.cancelStart();
        if (!this.owner.child) return;
        if (!this.owner.fingerprint) this.owner.recoverIdentity(this.owner.runId);
        this.group = this.owner.child.pid;
        for (const [signal, duration] of [['SIGTERM', 2000], ['SIGKILL', 2000]]) {
            const members = this.verifyGroup();
            this.evidence({ action: signal, group: this.group, members });
            // A leader with an unacknowledged start may still have a queued launch.
            // Kill that pinned group rather than enqueueing exit behind its start message.
            if (this.started && members.every(row => row.pid === this.group)) break;
            for (const member of members.filter(row => row.group !== this.group)) {
                const current = processTable().find(row => row.pid === member.pid);
                if (!current || current.zombie) continue;
                const tagged = this.discover();
                if (current.identity !== member.identity || current.group !== member.group || !tagged.has(member.pid) || member.pid === process.pid ||
                    (process.platform === 'linux' && !linuxIdentityMatches(member, tagged))) {
                    const found = tagged.get ? tagged.get(member.pid) : undefined;
                    let finalIdentity;
                    if (process.platform === 'linux') {
                        try { finalIdentity = linuxProcessIdentity(member.pid); } catch (_) { /* diagnostic only */ }
                    }
                    this.evidence({ action: 'ESCAPED_IDENTITY_MISMATCH', pid: member.pid,
                        before: { group: member.group, startTicks: member.startTicks },
                        after: { group: current.group, tagged: tagged.has(member.pid),
                            taggedGroup: found && found.group, taggedStartTicks: found && found.startTicks,
                            psIdentityMatches: current.identity === member.identity,
                            finalGroup: finalIdentity && finalIdentity.group,
                            finalStartTicks: finalIdentity && finalIdentity.startTicks } });
                    throw new Error('Escaped worker identity changed; refusing signal');
                }
                process.kill(member.pid, signal);
            }
            // The still-live leader pins this PGID; never derive a target from the caller's group.
            process.kill(-this.group, signal);
            const deadline = Date.now() + duration;
            if (signal === 'SIGKILL') {
                while (Date.now() < deadline) {
                    // Let Node observe the supervisor's exit before validating discovery.
                    await delay(25);
                    const tagged = this.discover();
                    const remaining = processTable().filter(row => (row.group === this.group || tagged.has(row.pid)) && !row.zombie);
                    if (!remaining.length) {
                        this.evidence({ action: 'DRAINED', group: this.group });
                        return;
                    }
                }
                throw new Error('Command group did not drain; Core cleanup prohibited');
            }
            while (Date.now() < deadline) {
                if (this.verifyGroup().every(row => row.pid === this.group)) break;
                await delay(25);
            }
        }
        this.verifyGroup();
        this.owner.child.send({ type: 'exit' });
        const deadline = Date.now() + 2000;
        while (Date.now() < deadline && this.owner.child.exitCode === null && this.owner.child.signalCode === null) await delay(25);
        const tagged = this.discover();
        if (processTable().some(row => (row.group === this.group || tagged.has(row.pid)) && !row.zombie)) {
            throw new Error('Command group still live; Core cleanup prohibited');
        }
        this.evidence({ action: 'DRAINED', group: this.group });
    }
}

module.exports = { OwnedCommandGroup, processTable };
