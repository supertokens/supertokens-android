const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const vm = require('vm');
const { test } = require('node:test');
const qualification = require('./accountLinkingQualification');
const qualify = require('./qualifyAccountLinking');
const { harness } = require('./accountLinking.test');

const pair = Object.fromEntries(['nodeSdkVersion', 'sdkImplementationSha256', 'expressRuntimeId', 'coreVersion',
    'coreSha256', 'pluginInterfaceVersion', 'pluginInterfaceSha256', 'coreRuntimeId', 'effectiveClasspathSha256',
    'sdkApiInventory', 'qualifierSha256'].map(key => [key, 'host-only-' + key]));

async function withEvidence(run) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'r7-positive-host-'));
    const previous = process.env.ACCOUNT_LINKING_QUALIFICATION_FILE;
    process.env.ACCOUNT_LINKING_QUALIFICATION_FILE = path.join(directory, 'evidence.json');
    try { await run(); } finally {
        if (previous === undefined) delete process.env.ACCOUNT_LINKING_QUALIFICATION_FILE;
        else process.env.ACCOUNT_LINKING_QUALIFICATION_FILE = previous;
        fs.rmSync(directory, { recursive: true, force: true });
    }
}

// This HTTP double checks publication wiring, not backend support. Probe SDK doubles are host-only.
function gateResponse() {
    const result = qualification.classify(pair, qualification.readEvidence());
    return { status: 501, json: async () => ({ capability: 'refreshUserIdChange',
        status: result.refreshUserIdChange === true ? 'FIXTURE_UNIMPLEMENTED' : 'UNSUPPORTED_TEST_CAPABILITY',
        details: { ...result, identity: pair } }) };
}

function gate(args) {
    const messages = [];
    const processDouble = { argv: ['node', 'gate', ...args], exitCode: 0 };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'accountLinking.gate.js'), 'utf8'), {
        require: name => {
            assert.strictEqual(name, './accountLinkingQualification');
            return { ...qualification, identity: () => pair };
        }, process: processDouble, console: { log: message => messages.push(message), error: message => messages.push(message) }
    });
    return { code: processDouble.exitCode, messages: messages.join('\n') };
}

test('real probe algorithm with host SDK doubles: positive capability is distinct from R7 fixture readiness', () => withEvidence(async () => {
    const h = harness('preserved');
    const observation = await qualify(h.dependencies);
    assert.strictEqual(h.sessions.size, 0);
    assert.deepStrictEqual([...h.users.keys()], ['unrelated']);
    const result = await qualification.publishAndVerifyEvidence(pair, observation, 'http://host.invalid', async () => gateResponse(), () => pair);
    assert.strictEqual(result.refreshUserIdChange, true);
    assert.strictEqual(result.fixtureAvailable, false);
    assert.strictEqual(result.state, 'fixture_unimplemented');
    const saved = fs.readFileSync(qualification.evidencePath(), 'utf8');
    for (const privateValue of [observation.before.userId, observation.after.userId, observation.before.handle]) {
        assert.ok(!saved.includes(privateValue), 'Do not persist user IDs or session handles');
    }
    assert.strictEqual(gate(['--capability']).code, 0);
    assert.match(gate(['--capability']).messages, /SUPPORTED CAPABILITY; R7 FIXTURE UNIMPLEMENTED/);
    assert.strictEqual(gate([]).code, 1, 'Existing matrix/default gate must still block positive pairs without fixture');
    assert.strictEqual(gate(['--unknown']).code, 1);
}));

test('revoked-session exclusion retains its outcome and default gate acceptance', () => withEvidence(async () => {
    const observation = await qualify(harness('revoked').dependencies);
    const result = await qualification.publishAndVerifyEvidence(pair, observation, 'http://host.invalid', async () => gateResponse(), () => pair);
    assert.strictEqual(result.state, 'backend_proven_unsupported');
    assert.strictEqual(result.refreshUserIdChange, false);
    assert.strictEqual(gate([]).code, 0);
    assert.match(gate(['--capability']).messages, /EXCLUDED/);
}));

test('publication rejects missing cleanup, survival, promotion, handle/tenant continuity and malformed identities', () => withEvidence(async () => {
    const valid = await qualify(harness('preserved').dependencies);
    const invalid = [
        { ...valid, cleanupVerified: false }, { ...valid, sessionSurvivedLink: false },
        { ...valid, after: { ...valid.after, userId: valid.before.userId } },
        { ...valid, after: { ...valid.after, userId: 'not-primary' } },
        { ...valid, after: { ...valid.after, handle: 'changed' } },
        { ...valid, after: { ...valid.after, tenant: 'changed' } },
        { ...valid, before: undefined }, { ...valid, primaryUserId: '' },
        { ...valid, before: { ...valid.before, handle: '' }, after: { ...valid.after, handle: '' } },
        { ...valid, before: { ...valid.before, tenant: undefined }, after: { ...valid.after, tenant: undefined } }
    ];
    for (const observation of invalid) {
        qualification.saveEvidence(pair, valid);
        await assert.rejects(qualification.publishAndVerifyEvidence(pair, observation, undefined, undefined, () => pair));
        assert.strictEqual(qualification.classify(pair, qualification.readEvidence()).state, 'unqualified');
    }
}));

test('positive publication invalidates evidence on HTTP failures and runtime changes', () => withEvidence(async () => {
    const valid = await qualify(harness('preserved').dependencies);
    for (const failure of ['timeout', 'status', 'body-status', 'state', 'identity', 'fixture', 'capability', 'before-runtime', 'after-runtime']) {
        let identityReads = 0;
        const currentIdentity = () => {
            identityReads++;
            return failure === 'before-runtime' || (failure === 'after-runtime' && identityReads > 1)
                ? { ...pair, coreRuntimeId: 'replacement' } : pair;
        };
        await assert.rejects(qualification.publishAndVerifyEvidence(pair, valid, 'http://host.invalid', async () => {
            if (failure === 'timeout') throw new Error('Host injected timeout');
            const response = gateResponse();
            const body = await response.json();
            if (failure === 'status') response.status = 200;
            if (failure === 'body-status') body.status = 'UNSUPPORTED_TEST_CAPABILITY';
            if (failure === 'state') body.details.state = 'backend_proven_unsupported';
            if (failure === 'fixture') body.details.fixtureAvailable = true;
            if (failure === 'capability') body.details.refreshUserIdChange = false;
            if (failure === 'identity') body.details.identity = { ...pair, coreRuntimeId: 'replacement' };
            return { status: response.status, json: async () => body };
        }, currentIdentity));
        assert.strictEqual(qualification.classify(pair, qualification.readEvidence()).state, 'unqualified', failure);
    }
    qualification.saveEvidence(pair, valid);
    for (const key of Object.keys(pair)) {
        assert.throws(() => qualification.requireQualified({ ...pair, [key]: 'changed' }, qualification.readEvidence()), /PAIR_MISMATCH/);
    }
}));

test('probe rejects changed identity and cleanup failure instead of manufacturing positive evidence', async () => {
    for (const mode of ['changed-handle', 'changed-tenant', 'cleanup-failure', 'creation-response-lost', 'link-response-lost']) {
        const h = harness(mode);
        if (mode.includes('failure') || mode.includes('lost')) await assert.rejects(qualify(h.dependencies));
        else assert.strictEqual((await qualify(h.dependencies)).supported, false);
        assert.strictEqual(h.sessions.size, 0);
        assert.ok(h.users.has('unrelated'));
    }
});

test('owned runner opts into capability-only gate solely for qualify, never matrix or mutation', async () => {
    for (const mode of ['qualify', 'matrix', 'mutation']) {
        const commands = [];
        const env = { ST_TEST_RUN_ID: 'host-run', INSTALL_PATH: '/host-install', CORE_PROTOCOL_TESTS: '1',
            ST_TEST_BACKEND_URL: 'http://host.invalid', ST_TEST_INSTANCE_ID: 'host-instance' };
        const processDouble = { argv: ['node', 'runner', mode, '/host-repository'], env, execPath: 'node' };
        const fetchDouble = async () => ({ ok: true, json: async () => ({ qualificationProxy: false,
            mappedGoogleSession: true, nonPublicTenantSession: true }) });
        const requires = {
            fs: { realpathSync: value => value, writeFileSync() {} }, path,
            child_process: { spawnSync: (command, args) => { commands.push([command, ...args]); return { status: 0 }; } },
            '/host-install/backend/coreRuntimeIdentity': { serverIdentity() {} },
            '/host-install/backend/accountLinkingQualification': { identity: () => pair },
            '/host-install/backend/node_modules/node-fetch': fetchDouble,
            './ownedExposure': { select: () => ({}) }, './ownedMatrix': async () => {}
        };
        const context = {
            require: name => { assert.ok(name in requires, name); return requires[name]; },
            module: { exports: {} }, process: processDouble, console: { error: error => { throw error; } }
        };
        vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'runOwnedTests.js'), 'utf8'), context);
        await context.module.exports.main();
        const command = commands.find(args => args[1] === 'accountLinking.gate.js');
        assert.ok(command);
        assert.strictEqual(command.includes('--capability'), mode === 'qualify');
    }
});
