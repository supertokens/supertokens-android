const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { inventory, verify } = require('./backendSourceInventory');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'backend-inventory-'));
try {
 const src = path.join(root, 'source'), copy = path.join(root, 'copy'); fs.mkdirSync(src); fs.mkdirSync(copy);
 for (const name of ['index.js', 'refreshObservation.js']) { fs.writeFileSync(path.join(src, name), name); fs.writeFileSync(path.join(copy, name), name); }
 const initial = inventory(src); assert.equal(verify(initial, copy), initial.sha256);
 fs.writeFileSync(path.join(copy, 'refreshObservation.js'), 'changed'); assert.throws(() => verify(initial, copy));
 fs.writeFileSync(path.join(copy, 'refreshObservation.js'), 'refreshObservation.js'); fs.writeFileSync(path.join(copy, 'new.js'), 'new'); assert.throws(() => verify(initial, copy));
 fs.unlinkSync(path.join(copy, 'new.js')); fs.unlinkSync(path.join(copy, 'refreshObservation.js')); assert.throws(() => verify(initial, copy));
 fs.writeFileSync(path.join(src, 'index.js'), 'changed'); assert.throws(() => verify(initial, src));
 console.log('backend inventory host controls passed');
} finally { fs.rmSync(root, { recursive: true, force: true }); }
