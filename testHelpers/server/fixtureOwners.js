const crypto = require("crypto");
const findFixtureTenant = require("./findFixtureTenant");

// Tracks only users created by this process, never arbitrary caller-supplied user IDs.
module.exports = function fixtureOwners(SuperTokens, ThirdParty, Multitenancy, sdkVersion) {
    const owners = new Map();
    // Tombstones acknowledge only known completed cleanups after a lost response.
    const cleaned = new Map();

    async function cleanup(owner) {
        if (owner.creating || owner.nativeBusy || owner.cleaning) throw new Error("Fixture operation in progress; cleanup refused");
        owner.cleaning = true;
        owner.cleanupStarted = true;
        try { await performCleanup(owner); } finally { owner.cleaning = false; }
    }

    async function performCleanup(owner) {
        if (owner.legacy) {
            if (owner.creationAmbiguous) throw new Error('Legacy fixture ownership is ambiguous; cleanup refused');
            if (owner.creationAttempted) {
                const lookup = () => owner.transitional ? ThirdParty.getUserByThirdPartyInfo('public', 'google', owner.id) : ThirdParty.getUserByThirdPartyInfo('google', owner.id);
                const user = await lookup();
                if (user !== undefined) {
                    if (!user.thirdParty || user.thirdParty.id !== 'google' || user.thirdParty.userId !== owner.id ||
                        typeof user.id !== 'string' || (owner.recipeUserId && owner.recipeUserId !== user.id)) {
                        throw new Error('Legacy fixture lookup identity mismatch');
                    }
                    const deleted = await SuperTokens.deleteUser(user.id);
                    if (deleted.status !== 'OK' || await lookup() !== undefined) {
                        throw new Error('Legacy fixture user deletion failed');
                    }
                }
            }
            cleaned.set(owner.id, new Set([owner.handle].filter(Boolean)));
            owners.delete(owner.id);
            return;
        }
        const tenantExists = owner.tenantId === "public" || await findFixtureTenant(Multitenancy, owner.tenantId) !== undefined;
        if (owner.tenantCreationAmbiguous && tenantExists) throw new Error('Fixture tenant ownership is ambiguous; cleanup refused');
        if (owner.recipeUserId === undefined && tenantExists) {
            // Creation may have committed at Core before its response was lost.
            const users = await SuperTokens.listUsersByAccountInfo(owner.tenantId, {
                thirdParty: { id: "google", userId: owner.id }
            });
            for (const user of users) {
                const method = user.loginMethods.find(value => value.thirdParty !== undefined &&
                    value.thirdParty.id === "google" && value.thirdParty.userId === owner.id);
                if (method) owner.recipeUserId = method.recipeUserId.getAsString();
            }
        }
        if (owner.recipeUserId !== undefined) {
            const mapping = await SuperTokens.getUserIdMapping({ userId: owner.recipeUserId, userIdType: "SUPERTOKENS" });
            if (mapping.status !== "OK" && mapping.status !== "UNKNOWN_MAPPING_ERROR") {
                throw new Error("Fixture mapping lookup failed: " + mapping.status);
            }
            const userId = mapping.status === "OK" ? mapping.externalUserId : owner.recipeUserId;
            const deleted = await SuperTokens.deleteUser(userId, false);
            if (deleted.status !== "OK") throw new Error("Fixture user deletion failed: " + deleted.status);
            const removed = await SuperTokens.deleteUserIdMapping({ userId: owner.recipeUserId, userIdType: "SUPERTOKENS" });
            if (removed.status !== "OK" && removed.status !== "UNKNOWN_MAPPING_ERROR") {
                throw new Error("Fixture mapping deletion failed: " + removed.status);
            }
            const remaining = await SuperTokens.getUserIdMapping({ userId: owner.recipeUserId, userIdType: "SUPERTOKENS" });
            if (remaining.status !== "UNKNOWN_MAPPING_ERROR" || await SuperTokens.getUser(owner.recipeUserId) !== undefined) {
                throw new Error("Core fixture cleanup did not remove user and mapping");
            }
        }
        if (owner.ownsTenant && tenantExists) {
            const removed = await Multitenancy.deleteTenant(owner.tenantId);
            if (removed.status !== "OK" || await findFixtureTenant(Multitenancy, owner.tenantId) !== undefined) {
                throw new Error("Fixture tenant deletion failed");
            }
        }
        cleaned.set(owner.id, new Set([owner.handle, ...(owner.loginHandles || [])].filter(Boolean)));
        owners.delete(owner.id);
    }

    async function createLegacy(email, createSession, transitional = false) {
        const owner = { id: 'android-test-' + crypto.randomBytes(16).toString('hex'), tenantId: 'public', legacy: true, transitional, creating: true };
        owners.set(owner.id, owner);
        try {
            const lookup = () => transitional ? ThirdParty.getUserByThirdPartyInfo('public', 'google', owner.id) : ThirdParty.getUserByThirdPartyInfo('google', owner.id);
            if (await lookup() !== undefined) {
                owners.delete(owner.id);
                throw new Error('Generated fixture user already exists');
            }
            owner.creationAttempted = true;
            owner.creationAmbiguous = true;
            const created = await (transitional ? ThirdParty.manuallyCreateOrUpdateUser('public', 'google', owner.id, email) : ThirdParty.signInUp('google', owner.id, email));
            if (created.status === 'OK' && created.createdNewUser === false) {
                throw new Error('Legacy fixture user was not created');
            }
            if (created.status !== 'OK' || created.createdNewUser !== true) throw new Error('Legacy fixture creation failed');
            const user = created.user;
            if (!user || typeof user.id !== 'string' || !user.thirdParty || user.thirdParty.id !== 'google' || user.thirdParty.userId !== owner.id) {
                throw new Error('Legacy fixture creation identity mismatch');
            }
            owner.recipeUserId = user.id;
            owner.creationAmbiguous = false;
            const persisted = await lookup();
            if (!persisted || persisted.id !== user.id || !persisted.thirdParty ||
                persisted.thirdParty.id !== 'google' || persisted.thirdParty.userId !== owner.id) {
                throw new Error('Legacy fixture user not persisted');
            }
            const session = await createSession(user.id);
            owner.handle = session.getHandle();
            owner.ready = true;
            owner.creating = false;
            return { owner, session };
        } catch (error) {
            if (!owners.has(owner.id)) throw error;
            owner.creating = false;
            try { await cleanup(owner); } catch (cleanupError) {
                const failure = new Error('Fixture creation failed; cleanup also failed and remains pending: ' + cleanupError.message);
                failure.cause = error;
                throw failure;
            }
            throw error;
        }
    }

    async function create(email, createSession, nonPublicTenant = false) {
        const owner = { id: "android-test-" + crypto.randomBytes(16).toString("hex"), creating: true };
        owner.tenantId = nonPublicTenant ? owner.id : "public";
        owners.set(owner.id, owner);
        try {
            if (nonPublicTenant) {
                const match = /^(\d+)\.\d+\.\d+$/.exec(sdkVersion || '');
                const major = match && Number(match[1]);
                if (!major || major < 17 || major > 24) {
                    owners.delete(owner.id);
                    throw new Error('Unsupported fixture tenant configuration for SDK version');
                }
                if (await findFixtureTenant(Multitenancy, owner.tenantId) !== undefined) {
                    owners.delete(owner.id);
                    throw new Error("Generated fixture tenant already exists");
                }
                // A lost response cannot prove that this process created the tenant.
                owner.tenantCreationAmbiguous = true;
                const config = major >= 19 ? { firstFactors: ['thirdparty'] } : { thirdPartyEnabled: true };
                const tenant = await Multitenancy.createOrUpdateTenant(owner.tenantId, config);
                if (tenant.status === 'OK' && tenant.createdNew === false) {
                    owners.delete(owner.id);
                    throw new Error('Fixture tenant creation failed: tenant already exists');
                }
                if (tenant.status !== "OK" || tenant.createdNew !== true) throw new Error("Fixture tenant creation failed");
                owner.tenantCreationAmbiguous = false;
                owner.ownsTenant = true;
            }
            const created = await ThirdParty.manuallyCreateOrUpdateUser(owner.tenantId, "google", owner.id, email, true);
            if (created.status !== "OK") throw new Error("Core third-party creation failed: " + created.status);
            owner.recipeUserId = created.recipeUserId.getAsString();
            const mapping = await SuperTokens.createUserIdMapping({ superTokensUserId: owner.recipeUserId, externalUserId: owner.id });
            if (mapping.status !== "OK") throw new Error("Core user mapping failed: " + mapping.status);
            const persisted = await SuperTokens.getUserIdMapping({ userId: owner.id, userIdType: "EXTERNAL" });
            const user = await SuperTokens.getUser(owner.id);
            const google = user !== undefined && user.loginMethods.some(method => method.recipeId === "thirdparty" &&
                method.thirdParty !== undefined && method.thirdParty.id === "google");
            if (persisted.status !== "OK" || !google) throw new Error("Core did not persist the mapped Google recipe user");
            const session = await createSession(owner.id, owner.tenantId);
            owner.handle = session.getHandle();
            owner.ready = true;
            owner.creating = false;
            return { owner, session };
        } catch (error) {
            if (!owners.has(owner.id)) throw error;
            owner.creating = false;
            try { await cleanup(owner); } catch (cleanupError) {
                const failure = new Error("Fixture creation failed; cleanup also failed and remains pending: " + cleanupError.message);
                failure.cause = error;
                throw failure;
            }
            throw error;
        }
    }

    async function cleanupById(id) {
        const owner = owners.get(id);
        if (!owner) return cleaned.has(id);
        await cleanup(owner);
        return true;
    }

    async function cleanupByHandle(handle) {
        for (const owner of owners.values()) {
            if (owner.handle === handle || owner.loginHandles?.has(handle)) return cleanupById(owner.id);
        }
        return false;
    }

    async function cleanupAll() {
        const failures = [];
        for (const owner of Array.from(owners.values())) {
            try { await cleanup(owner); } catch (error) { failures.push(error); }
        }
        if (failures.length) throw new Error(failures.length + " fixture owner cleanups remain pending: " + failures[0].message);
    }

    function ownsSession(id, handle) {
        return typeof handle === "string" && handle.length > 0 &&
            !!(owners.get(id)?.handle === handle || owners.get(id)?.loginHandles?.has(handle) || cleaned.get(id)?.has(handle));
    }

    function assertNativeOwner(id, nonce) {
        const owner = owners.get(id);
        if (!owner || !owner.ready || owner.cleanupStarted || owner.cleaning || owner.nativeBusy || owner.nativeNonce !== nonce) {
            throw new Error("Native request requires this row's idle live mapped fixture");
        }
        return owner;
    }

    function bindNativeOwner(id, nonce) {
        const owner = owners.get(id);
        if (!owner || !owner.ready || owner.cleaning || owner.nativeBusy || owner.nativeNonce !== undefined || !/^[a-f0-9]{48}$/.test(nonce)) {
            throw new Error("Cannot bind native fixture owner");
        }
        owner.nativeNonce = nonce;
    }

    async function nativeLogin(id, userId, nonce, createSession) {
        const owner = assertNativeOwner(id, nonce);
        if (userId !== owner.id || owner.tenantId !== 'public') throw new Error("Native login user/tenant mismatch");
        owner.nativeBusy = true;
        try {
            const session = await createSession();
            const handle = session.getHandle();
            if (session.getUserId() !== owner.id || session.getTenantId() !== owner.tenantId ||
                typeof handle !== 'string' || !handle || handle === owner.handle || owner.loginHandles?.has(handle)) {
                throw new Error("Actual native login session does not match owned fixture");
            }
            if (!owner.loginHandles) owner.loginHandles = new Set();
            owner.loginHandles.add(handle);
            return session;
        } finally { owner.nativeBusy = false; }
    }

    async function nativeSessionState(id, handle, nonce, readSession) {
        const owner = assertNativeOwner(id, nonce);
        if (typeof handle !== 'string' || !owner.loginHandles?.has(handle)) throw new Error("Unknown native login session");
        owner.nativeBusy = true;
        try { return await readSession(handle); } finally { owner.nativeBusy = false; }
    }

    return { create, createLegacy, cleanupById, cleanupByHandle, cleanupAll, ownsSession,
        bindNativeOwner, assertNativeOwner, nativeLogin, nativeSessionState };
};
