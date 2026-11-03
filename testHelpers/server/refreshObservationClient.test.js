const assert = require('assert');
const { observationHeaders } = require('./ownedBackendRequest');
const id = 'a'.repeat(48), instance = 'b'.repeat(48), generation = 'c'.repeat(48);
assert.deepStrictEqual(observationHeaders(id, instance, { action:'arm' }), { 'x-st-test-run-id': id, 'x-st-test-instance-id': instance });
assert.deepStrictEqual(observationHeaders(id, instance, { action:'consume', generation }), { 'x-st-test-run-id': id, 'x-st-test-instance-id': instance, 'x-st-test-generation': generation });
for (const input of [{action:'arm', generation}, {action:'consume'}, {action:'consume', generation:'x'}, {action:'other'}, {action:'arm', headers:{} }]) assert.throws(() => observationHeaders(id, instance, input));
console.log('observation metadata controls passed');
(async () => {
    const fs = require('fs'), os = require('os'), path = require('path'), vm = require('vm');
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'observer-client-'));
    try {
        const backend = path.join(root, 'backend'); fs.mkdirSync(backend);
        let current = 'expected-fingerprint', contacted = false, output = '';
        const mockProcess = { argv:['node','client','arm'], env:{ INSTALL_PATH:root, ST_TEST_BACKEND_CWD:backend,
            ST_TEST_BACKEND_PID:'4321', ST_TEST_BACKEND_FINGERPRINT:'expected-fingerprint', ST_TEST_RUN_ID:id,
            ST_TEST_INSTANCE_ID:instance, NODE_PORT:'4567' }, stdout:{ write: value => { output += value; } } };
        const fakeRequire = name => name === './ownedProcess' ? { identity: pid => { assert.equal(pid, 4321); return current; } }
            : name === './ownedBackendRequest' ? { ownedBackendRequest: async (owner, port, expectedInstance) => {
                assert.equal(owner.child.pid, 4321); assert.equal(expectedInstance, instance); owner.verify(); contacted = true;
                return { body: JSON.stringify({ generation, pid:9999, instance:'untrusted' }) };
            } } : require(name);
        const mod = { exports:{} }; const code = fs.readFileSync(path.join(__dirname, 'refreshObservationClient.js'), 'utf8');
        vm.runInNewContext('(function(require,module,process){' + code + '\n})', {})(fakeRequire, mod, mockProcess);
        await mod.exports.main(); assert(contacted); assert.equal(JSON.parse(output).generation, generation); assert.equal(JSON.parse(output).instance, undefined);
        contacted = false; current = 'changed'; await assert.rejects(mod.exports.main(), /identity changed/); assert.equal(contacted, false);
        console.log('client trusted wrapper metadata controls passed');
    } finally { fs.rmSync(root, { recursive:true, force:true }); }
})().catch(error => { console.error(error); process.exitCode = 1; });
