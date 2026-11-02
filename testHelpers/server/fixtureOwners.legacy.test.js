const test = require('node:test');
const assert = require('node:assert/strict');
const fixtureOwners = require('./fixtureOwners');

function harness() {
    const users = new Map();
    const faults = {};
    const thirdParty = {
        async getUserByThirdPartyInfo(provider, id) {
            assert.equal(provider, 'google');
            if (faults.lookup) throw new Error('lookup unavailable');
            return users.get(id);
        },
        async signInUp(provider, id, email) {
            const user = { id: 'core-' + id, email, thirdParty: { id: provider, userId: id } };
            users.set(id, user);
            if (faults.lost) throw new Error('creation response lost');
            if (faults.retry) return { status: 'OK', createdNewUser: false, user };
            return { status: 'OK', createdNewUser: true, user };
        }
    };
    const supertokens = {
        async deleteUser(id) {
            if (faults.delete) throw new Error('delete unavailable');
            for (const [key, user] of users) if (user.id === id) users.delete(key);
            return { status: 'OK' };
        }
    };
    return { owners: fixtureOwners(supertokens, thirdParty), users, faults };
}

test('legacy owner creates a real recipe user and cleans it by handle', async () => {
    const h = harness();
    const { owner, session } = await h.owners.createLegacy('a@example.com', async id => ({
        getUserId: () => id, getHandle: () => 'handle'
    }));
    assert.equal(session.getUserId(), h.users.get(owner.id).id);
    assert.equal(await h.owners.cleanupByHandle('handle'), true);
    assert.equal(h.users.size, 0);
    assert.equal(await h.owners.cleanupById(owner.id), true);
});

test('legacy creation response loss retains ambiguous user instead of deleting it', async () => {
    const h = harness();
    h.faults.lost = true;
    await assert.rejects(h.owners.createLegacy('a@example.com', async () => { throw Error('unexpected session'); }), /cleanup also failed/);
    assert.equal(h.users.size, 1);
    await assert.rejects(h.owners.cleanupAll(), /ambiguous/);
    assert.equal(h.users.size, 1);
});

test('legacy SDK retry returning an existing user retains ambiguous ownership', async () => {
    const h = harness();
    h.faults.retry = true;
    await assert.rejects(h.owners.createLegacy('a@example.com', async () => {}), /cleanup also failed/);
    assert.equal(h.users.size, 1);
    await assert.rejects(h.owners.cleanupAll(), /ambiguous/);
    assert.equal(h.users.size, 1);
});

test('legacy failed cleanup of confirmed owner is retained and retried', async () => {
    const h = harness();
    const { owner } = await h.owners.createLegacy('a@example.com', async () => ({ getHandle: () => 'handle' }));
    h.faults.delete = true;
    await assert.rejects(h.owners.cleanupById(owner.id), /delete unavailable/);
    assert.equal(h.users.size, 1);
    h.faults.delete = false;
    await h.owners.cleanupAll();
    assert.equal(h.users.size, 0);
});

test('legacy lost response after a concurrent identity collision never deletes the other user', async () => {
    const h = harness();
    h.faults.lost = true;
    await assert.rejects(h.owners.createLegacy('a@example.com', async () => {}), /cleanup also failed/);
    const [[id]] = h.users;
    h.users.set(id, { id: 'concurrent-user', thirdParty: { id: 'google', userId: id } });
    await assert.rejects(h.owners.cleanupAll(), /ambiguous/);
    assert.equal(h.users.get(id).id, 'concurrent-user');
});

test('legacy lookup failure retains cleanup for retry', async () => {
    const h = harness();
    const { owner } = await h.owners.createLegacy('a@example.com', async () => ({ getHandle: () => 'handle' }));
    h.faults.lookup = true;
    await assert.rejects(h.owners.cleanupById(owner.id), /lookup unavailable/);
    assert.equal(h.users.size, 1);
    h.faults.lookup = false;
    await h.owners.cleanupAll();
    assert.equal(h.users.size, 0);
});

test('legacy generated identity collision never deletes an existing user', async () => {
    const h = harness();
    const crypto = require('crypto');
    const original = crypto.randomBytes;
    const id = 'android-test-' + Buffer.alloc(16, 1).toString('hex');
    h.users.set(id, { id: 'existing', thirdParty: { id: 'google', userId: id } });
    crypto.randomBytes = () => Buffer.alloc(16, 1);
    try {
        await assert.rejects(h.owners.createLegacy('a@example.com', async () => {}), /already exists/);
        await h.owners.cleanupAll();
        assert.equal(h.users.get(id).id, 'existing');
    } finally { crypto.randomBytes = original; }
});
