const assert = require('assert');
const http = require('http');
const Module = require('module');
const express = require('express');
const fetch = require('node-fetch');

process.env.CORE_PROTOCOL_TESTS = '1';
const users = new Map();
const thirdParty = {
    Google: () => ({ id: 'google' }),
    init: config => { assert.equal(config.signInAndUpFeature.providers[0].id, 'google'); return 'legacy-recipe'; },
    async getUserByThirdPartyInfo(provider, id) { return users.get(id); },
    async signInUp(provider, id, email) {
        const user = { id: 'core-' + id, email, thirdParty: { id: provider, userId: id } };
        users.set(id, user);
        return { status: 'OK', createdNewUser: true, user };
    }
};
const sdk = { async deleteUser(id) {
    for (const [key, user] of users) if (user.id === id) users.delete(key);
    return { status: 'OK' };
} };
const session = { async createNewSession(req, res, userId) {
    assert.equal(typeof userId, 'string');
    res.set('st-access-token', 'access');
    return { getUserId: () => userId, getHandle: () => 'handle',
        getAllSessionTokensDangerously: () => ({ refreshToken: 'refresh' }) };
} };
const originalLoad = Module._load;
Module._load = function(name, ...args) {
    if (name === 'supertokens-node') return sdk;
    if (name === 'supertokens-node/recipe/thirdparty') return thirdParty;
    if (name === 'supertokens-node/recipe/session') return session;
    if (name === 'supertokens-node/lib/build/version') return { version: '14.1.4', cdiSupported: ['2.21'] };
    if (name === 'supertokens-node/recipe/session/framework/express') return { verifySession: () => (req, res, next) => next() };
    if (name === 'supertokens-node/recipe/accountlinking' || name === 'supertokens-node/recipe/multitenancy') {
        const error = new Error('unavailable'); error.code = 'MODULE_NOT_FOUND'; throw error;
    }
    return originalLoad.call(this, name, ...args);
};
const protocol = require('./coreProtocol');
Module._load = originalLoad;

async function main() {
    const core = http.createServer((req, res) => res.end(JSON.stringify({ versions: ['2.21'] })));
    await new Promise(resolve => core.listen(0, '127.0.0.1', resolve));
    process.env.ST_TEST_CORE_PORT = core.address().port;
    const config = protocol.configure({ recipeList: [] });
    assert.deepStrictEqual(config.recipeList, ['legacy-recipe']);
    const app = express(); app.use(express.json()); protocol.install(app);
    const server = await new Promise(resolve => { const value = app.listen(0, '127.0.0.1', () => resolve(value)); });
    const base = 'http://127.0.0.1:' + server.address().port;
    try {
        const caps = await (await fetch(base + '/test/capabilities')).json();
        assert.equal(caps.mappedGoogleSession, false);
        assert.equal(caps.legacyBaselineSession, true);
        const response = await fetch(base + '/test/email-change-owner', { method: 'POST',
            headers: { 'content-type': 'application/json' }, body: JSON.stringify({ recipe: 'google', email: 'test@example.com' }) });
        assert.equal(response.status, 200);
        assert.equal(response.headers.get('st-access-token'), 'access');
        const owner = await response.json();
        assert.equal(owner.userId, users.get(owner.fixtureId).id);
        const cleaned = await fetch(base + '/test/cleanup-owner', { method: 'POST',
            headers: { 'content-type': 'application/json' }, body: JSON.stringify({ fixtureId: owner.fixtureId }) });
        assert.equal((await cleaned.json()).cleaned, true);
        assert.equal(users.size, 0);
    } finally {
        await new Promise(resolve => server.close(resolve));
        await new Promise(resolve => core.close(resolve));
    }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
