const assert = require('assert');
const mode = require('./promotionMode');
assert.strictEqual(mode(['5.4', '5.5', '5.6', '5.7'], ['5.5', '5.7', '5.6']), 'payload');
assert.strictEqual(mode(['5.4', '5.5', '5.6'], ['5.4', '5.5']), 'child');
assert.strictEqual(mode(['2.21'], ['2.21']), 'child');
assert.throws(() => mode(['5.7'], ['5.5']), /No compatible CDI/);
assert.throws(() => mode(['5.bad'], ['5.bad']), /No compatible CDI/);
