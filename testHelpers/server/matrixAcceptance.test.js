const assert = require('assert');
const { support, accept } = require('./ownedMatrix');
const { assertEmpty } = require('./ownedProxyQualification');
const capabilities = { enabled: true, compatible: true, qualificationProxy: false, mappedGoogleSession: true, nonPublicTenantSession: true };
assert.deepStrictEqual(support(capabilities), { mapped: true, mutation: true });
assert.deepStrictEqual(support({ ...capabilities, nonPublicTenantSession: false }), { mapped: true, mutation: false });
assert.deepStrictEqual(support({ ...capabilities, mappedGoogleSession: false }), { mapped: false, mutation: false });
assert.throws(() => support({ ...capabilities, compatible: false }));
const names = ['Proxy.first', 'Proxy.second'];
const report = { tests: 2, passed: 2, skipped: 0, failed: 0,
    cases: names.map(value => ({ className: value.split('.')[0], name: value.split('.')[1] })) };
accept(report, names);
for (const change of [{ tests: 0 }, { skipped: 1 }, { failed: 1 }, { cases: [report.cases[0], report.cases[0]] }]) {
    assert.throws(() => accept({ ...report, ...change }, names));
}
const state = { active: 0, upstreamActive: 0, ownedFixtures: 0, ownerReservations: 0, unresolvedCreations: 0, gates: { path: { released: true } } };
const owners = { fixtures: [], unresolvedCreations: 0 };
assertEmpty(state, owners);
for (const key of ['active', 'upstreamActive', 'ownedFixtures', 'ownerReservations', 'unresolvedCreations']) {
    assert.throws(() => assertEmpty({ ...state, [key]: 1 }, owners));
}
assert.throws(() => assertEmpty({ ...state, gates: { path: { released: false } } }, owners));
assert.throws(() => assertEmpty(state, { fixtures: [{ fixtureId: 'foreign' }], unresolvedCreations: 0 }));
console.log('Matrix capability exclusions, exact two-method acceptance and leak rejection passed');
