const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');
const { writeManifest } = require('./sourceRevisionManifest');

const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'source-manifest-'));
function git(dir, ...args) {
    return execFileSync('git', ['-C', dir, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}
function repo(dir) {
    fs.mkdirSync(dir, { recursive: true });
    git(dir, 'init');
    git(dir, '-c', 'user.name=Test', '-c', 'user.email=test@example.com', 'commit', '--allow-empty', '-m', 'test');
    git(dir, 'tag', 'v1.0.0');
    return git(dir, 'rev-parse', 'HEAD');
}
try {
    const android = path.join(temp, 'android');
    const root = path.join(temp, 'root');
    const sha = repo(android);
    repo(root);
    const coreSha = repo(path.join(root, 'supertokens-core'));
    repo(path.join(root, 'supertokens-plugin-interface'));
    const lock = path.join(temp, 'package-lock.json');
    const output = path.join(temp, 'manifest.json');
    const args = [output, android, root, lock, '1.0', '2.0', 'v1.0.0', '1.0.0', sha,
        'v1.0.0', '1.0.0', 'v1.0.0', '1.0.0'];
    const setLock = value => fs.writeFileSync(lock, JSON.stringify({ packages: { 'node_modules/supertokens-node': { resolved: value } } }));
    setLock(`git+https://github.com/supertokens/supertokens-node.git#${sha}`);
    writeManifest(args);
    const result = JSON.parse(fs.readFileSync(output));
    assert.strictEqual(result.node.sha, sha);
    assert.strictEqual(result.selectionCdi, '2.0');
    assert.strictEqual(result.core.sha, coreSha);
    assert.throws(() => writeManifest(args), /EEXIST/);
    fs.unlinkSync(output);
    setLock(`git+https://github.com/supertokens/supertokens-node.git#${'0'.repeat(40)}`);
    assert.throws(() => writeManifest(args), /Installed Node revision/);
    assert(!fs.existsSync(output));
    setLock(`git+ssh://git@github.com/supertokens/supertokens-node.git#${sha}`);
    assert.throws(() => writeManifest(args.map((v, i) => i === 9 ? 'missing' : v)));
    assert.throws(() => writeManifest(args.map((v, i) => i === 5 ? '' : v)), /invalid source selection/);
    assert(!fs.existsSync(output));
    console.log('source revision manifest tests passed');
} finally {
    fs.rmSync(temp, { recursive: true, force: true });
}
