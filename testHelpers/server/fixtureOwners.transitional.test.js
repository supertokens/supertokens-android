const assert = require('assert');
const fixtureOwners = require('./fixtureOwners');

async function main() {
    const users = new Map();
    let ambiguous = false;
    const thirdParty = {
        async getUserByThirdPartyInfo(tenant, provider, id) {
            assert.equal(tenant, 'public');
            assert.equal(provider, 'google');
            return users.get(id);
        },
        async manuallyCreateOrUpdateUser(tenant, provider, id) {
            assert.equal(tenant, 'public');
            const user = { id: 'core-' + id, thirdParty: { id: provider, userId: id } };
            users.set(id, user);
            if (ambiguous) throw new Error('lost response');
            return { status: 'OK', createdNewUser: true, user };
        }
    };
    const sdk = { async deleteUser(id) {
        for (const [key, user] of users) if (user.id === id) users.delete(key);
        return { status: 'OK' };
    } };
    const owners = fixtureOwners(sdk, thirdParty);
    const { owner } = await owners.createLegacy('fixture@example.com', async id => ({ getHandle: () => id }), true);
    assert.equal(users.size, 1);
    assert.equal(await owners.cleanupById(owner.id), true);
    assert.equal(users.size, 0);
    ambiguous = true;
    await assert.rejects(owners.createLegacy('fixture@example.com', async () => {}, true), /cleanup also failed/);
    await assert.rejects(owners.cleanupAll(), /ambiguous/);
    assert.equal(users.size, 1);
}
main().catch(error => { console.error(error); process.exitCode = 1; });
