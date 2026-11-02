const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

function revision(directory, ref = 'HEAD') {
    const sha = execFileSync('git', ['-C', directory, 'rev-parse', '--verify', `${ref}^{commit}`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
    if (!/^[a-f0-9]{40}$/.test(sha)) throw new Error('Invalid git revision');
    return sha;
}

function checkedOut(directory, tag) {
    const sha = revision(directory);
    if (revision(directory, `refs/tags/${tag}`) !== sha) throw new Error('Checkout does not match selected tag');
    return sha;
}

function writeManifest(args) {
    const [output, android, root, lockFile, fdi, cdi, nodeTag, nodeVersion, nodeSha,
        coreTag, coreVersion, pluginTag, pluginVersion] = args;
    if (args.length !== 13 || ![fdi, cdi, nodeTag, nodeVersion, coreTag, coreVersion, pluginTag, pluginVersion]
        .every(value => typeof value === 'string' && value !== 'null' && /^[A-Za-z0-9][A-Za-z0-9._+-]*$/.test(value)) ||
        !/^[a-f0-9]{40}$/.test(nodeSha || '')) throw new Error('Missing or invalid source selection');
    const lock = JSON.parse(fs.readFileSync(lockFile, 'utf8'));
    const installed = (lock.packages && lock.packages['node_modules/supertokens-node']) ||
        (lock.dependencies && lock.dependencies['supertokens-node']);
    const resolved = installed && (installed.resolved || installed.version);
    if (typeof resolved !== 'string' ||
        !/^git\+(?:https:\/\/github\.com|ssh:\/\/git@github\.com)\/supertokens\/supertokens-node(?:\.git)?#[a-f0-9]{40}$/.test(resolved) ||
        !resolved.endsWith(`#${nodeSha}`)) throw new Error('Installed Node revision does not match selection');
    const manifest = {
        schema: 1, fdi, selectionCdi: cdi,
        android: { sha: revision(android) },
        root: { sha: revision(root) },
        node: { tag: nodeTag, version: nodeVersion, sha: nodeSha },
        core: { tag: coreTag, version: coreVersion, sha: checkedOut(path.join(root, 'supertokens-core'), coreTag) },
        pluginInterface: { tag: pluginTag, version: pluginVersion, sha: checkedOut(path.join(root, 'supertokens-plugin-interface'), pluginTag) },
    };
    fs.writeFileSync(output, JSON.stringify(manifest, null, 2) + '\n', { flag: 'wx' });
}

module.exports = { writeManifest };
if (require.main === module) {
    try { writeManifest(process.argv.slice(2)); } catch (error) {
        console.error(`Source revision manifest: ${error.message}`);
        process.exitCode = 1;
    }
}
