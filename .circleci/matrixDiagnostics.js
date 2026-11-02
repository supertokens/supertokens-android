const fs = require('fs');
const path = require('path');

function read(file) {
    try { return fs.readFileSync(file, 'utf8').trim(); } catch (_) { return null; }
}
function number(value) {
    return value && /^(?:[0-9]+|max)$/.test(value) ? value : null;
}
function counters(value, allowed) {
    const result = {};
    for (const line of (value || '').split('\n')) {
        const match = /^([a-z_]+) ([0-9]+)$/.exec(line);
        if (match && allowed.includes(match[1])) result[match[1]] = match[2];
    }
    return result;
}
function cgroup(proc, mounts) {
    const groups = (read(proc) || '').split('\n').map(line => line.split(':'));
    let unresolved = false;
    for (const line of (read(mounts) || '').split('\n')) {
        const parts = line.split(' ');
        const separator = parts.indexOf('-');
        if (separator < 0) continue;
        const type = parts[separator + 1];
        const controllers = (parts[separator + 3] || '').split(',');
        const group = groups.find(row => type === 'cgroup2' ? row[0] === '0' && row[1] === '' :
            type === 'cgroup' && controllers.includes('memory') && (row[1] || '').split(',').includes('memory'));
        if (!group || !group[2]) continue;
        const decode = value => value.replace(/\\([0-7]{3})/g, (_, octal) => String.fromCharCode(parseInt(octal, 8)));
        const root = decode(parts[3]);
        const mount = decode(parts[4]);
        const member = group[2];
        if (!root.startsWith('/') || !member.startsWith('/') ||
            [root, member].some(value => value.split('/').some(part => part === '.' || part === '..')) ||
            (root !== '/' && member !== root && !member.startsWith(root + '/'))) {
            unresolved = true;
            continue;
        }
        const relative = root === '/' ? member.slice(1) : member.slice(root.length).replace(/^\//, '');
        const dir = path.resolve(mount, relative);
        if (dir !== mount && !dir.startsWith(mount + '/')) continue;
        const v2 = type === 'cgroup2';
        const eventNames = ['low', 'high', 'max', 'oom', 'oom_kill', 'oom_group_kill'];
        const local = v2 ? read(path.join(dir, 'memory.events.local')) : null;
        return { version: v2 ? 2 : 1,
            limit: number(read(path.join(dir, v2 ? 'memory.max' : 'memory.limit_in_bytes'))),
            current: number(read(path.join(dir, v2 ? 'memory.current' : 'memory.usage_in_bytes'))),
            peak: number(read(path.join(dir, v2 ? 'memory.peak' : 'memory.max_usage_in_bytes'))),
            swapCurrent: v2 ? number(read(path.join(dir, 'memory.swap.current'))) : null,
            swapLimit: v2 ? number(read(path.join(dir, 'memory.swap.max'))) : null,
            events: counters(read(path.join(dir, v2 ? 'memory.events' : 'memory.oom_control')),
                v2 ? eventNames : ['oom_kill', 'under_oom']),
            localEvents: local === null ? null : counters(local, eventNames),
            failcnt: v2 ? null : number(read(path.join(dir, 'memory.failcnt'))) };
    }
    return { version: null, reason: unresolved ? 'membership-not-resolvable' : 'memory-controller-unavailable' };
}
function snapshot(phase, status, env = process.env) {
    const sdk = env.ANDROID_HOME || env.ANDROID_SDK_ROOT;
    let rootBuild = null;
    if (phase === 'after') {
        try {
            const steps = JSON.parse(fs.readFileSync(path.join(env.INSTALL_PATH, 'matrix/steps.json'), 'utf8'));
            const step = steps.filter(item => item.name === 'root-build').pop();
            if (step) rootBuild = { status: ['running', 'failed', 'passed'].includes(step.status) ? step.status : null,
                exit: Number.isInteger(step.exit) ? step.exit : null,
                signal: typeof step.signal === 'string' && /^SIG[A-Z0-9]+$/.test(step.signal) ? step.signal : null };
        } catch (_) { /* Diagnostic data is optional. */ }
    }
    return { phase, commandExit: phase === 'after' && /^\d+$/.test(status || '') ? Number(status) : null,
        cgroup: cgroup('/proc/self/cgroup', '/proc/self/mountinfo'), rootBuild,
        sdk: { configured: Boolean(sdk), platform32Installed: sdk ? fs.existsSync(path.join(sdk, 'platforms/android-32/source.properties')) : null,
            buildTools2902Installed: sdk ? fs.existsSync(path.join(sdk, 'build-tools/29.0.2/source.properties')) : null } };
}
if (require.main === module) {
    const [phase, status] = process.argv.slice(2);
    if (!['before', 'after'].includes(phase) || !process.env.INSTALL_PATH) process.exit(1);
    const directory = path.join(process.env.INSTALL_PATH, 'diagnostics');
    fs.mkdirSync(directory, { recursive: true, mode: 0o700 });
    fs.writeFileSync(path.join(directory, `matrix-${phase}.json`), JSON.stringify(snapshot(phase, status), null, 2) + '\n', { mode: 0o600 });
}
module.exports = { cgroup, counters, snapshot };
