const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
function inventory(root) {
    const entries = fs.readdirSync(root).filter(name => !name.startsWith('.') && /\.(js|json|html)$/.test(name) &&
        fs.statSync(path.join(root, name)).isFile()).sort().map(name => ({
        name, sha256: crypto.createHash('sha256').update(fs.readFileSync(path.join(root, name))).digest('hex')
    }));
    for (const name of ['index.js', 'refreshObservation.js']) {
        if (!entries.some(entry => entry.name === name)) throw new Error('Required backend source missing');
    }
    return { schema: 1, entries, sha256: crypto.createHash('sha256').update(JSON.stringify(entries)).digest('hex') };
}
function verify(expected, root) {
    const actual = inventory(root);
    if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error('Backend source inventory changed');
    return actual.sha256;
}
module.exports = { inventory, verify };
