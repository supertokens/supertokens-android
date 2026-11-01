const assert = require('node:assert/strict');
const test = require('node:test');
const { select } = require('./ownedExposure');
const { accept } = require('../testapp/exposureAcceptance');
const { names } = require('../../.circleci/upgradeEvidence');

test('historical selector accepts only header and retains supplied phase versions', () => {
    const handoffs = { LOGIN: '2', REFRESH: '5', SIGN_OUT: 'ABSENT', PAYLOAD_UPDATE: '5' };
    const options = ['HEADER', '4.1', '2', JSON.stringify(handoffs), '5', '/login-2.18'];
    const selected = select('upgrade', options, 'http://127.0.0.1:18081');
    assert.equal(selected.route, '/login-2.18');
    assert.equal(selected.exposed, false);
    assert.deepEqual(selected.properties.slice(2), ['-Pupgrade.expectedPublishedLoginVersion=2',
        '-Pupgrade.expectedPublishedHandoffVersions=' + JSON.stringify(handoffs), '-Pupgrade.expectedCandidateRefreshVersion=5',
        '-Pupgrade.loginRoute=/login-2.18']);
    assert.equal(select('upgrade', options.slice(0, 5), 'owned').route, '/login');
    for (const mode of ['COOKIE', 'EXPOSED_COOKIE']) assert.throws(() => select('upgrade', [mode, ...options.slice(1)], 'owned'));
    for (const route of ['/unknown', '', '/login-2.18/']) assert.throws(() => select('upgrade', [...options.slice(0, 5), route], 'owned'));
    assert.throws(() => select('upgrade', [...options, 'extra'], 'owned'));
    const primary = select('upgrade', ['HEADER', '4.1', '5', '5', '5'], 'owned');
    assert(primary.properties.includes('-Pupgrade.expectedPublishedHandoffVersion=5'));
    for (const value of ['5', '{"LOGIN":"2"}', JSON.stringify({ ...handoffs, EXTRA: '5' }),
        JSON.stringify({ ...handoffs, SIGN_OUT: '5', REFRESH: 'ABSENT' })]) {
        const invalid = [...options]; invalid[3] = value;
        assert.throws(() => select('upgrade', invalid, 'owned'));
    }
});

test('historical acceptance requires exact four successful historical identities', () => {
    const report = () => ({ tests: 4, passed: 4, failed: 0, skipped: 0,
        cases: names({ mode: 'HEADER', route: '/login-2.18' }).map(full => {
            const split = full.indexOf('.publishedSession');
            return { className: full.slice(0, split), name: full.slice(split + 1), result: 'SUCCESS' };
        }) });
    accept('upgrade', 'HEADER', report(), '/login-2.18');
    assert.throws(() => accept('upgrade', 'HEADER', report()));
    for (const mutate of [value => { value.cases[0] = value.cases[1]; },
        value => { value.cases[0].name = value.cases[0].name.replace('/login-2.18', '/login'); },
        value => { value.failed = 1; value.passed = 3; }, value => { value.skipped = 1; value.passed = 3; }]) {
        const value = report(); mutate(value);
        assert.throws(() => accept('upgrade', 'HEADER', value, '/login-2.18'));
    }
});
