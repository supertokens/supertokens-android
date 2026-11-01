const crypto = require("crypto");

// Qualifies Core behaviour with fresh, unmapped users. It never links caller-owned accounts.
module.exports = async function qualifyAccountLinking({ SuperTokens, ThirdParty, AccountLinking, Session }) {
    const users = [];
    const handles = [];
    let result;
    let failure;
    try {
        for (let i = 0; i < 2; i++) {
            const providerId = "android-link-probe-" + crypto.randomBytes(16).toString("hex");
            const owned = { providerId };
            users.push(owned);
            const created = await ThirdParty.manuallyCreateOrUpdateUser("public", "google", providerId, providerId + "@example.com", true);
            if (created.status !== "OK") throw new Error("Link probe user creation failed: " + created.status);
            owned.recipeUserId = created.recipeUserId;
        }
        const primary = await AccountLinking.createPrimaryUser(users[0].recipeUserId);
        if (primary.status !== "OK") throw new Error("Link probe primary creation failed: " + primary.status);
        const session = await Session.createNewSessionWithoutRequestResponse("public", users[1].recipeUserId);
        handles.push(session.getHandle());
        const before = { userId: session.getUserId(), handle: session.getHandle(), tenant: session.getTenantId() };
        if (before.userId !== users[1].recipeUserId.getAsString() || before.userId === primary.user.id) {
            throw new Error("Link probe must start with the distinct, unmapped secondary user");
        }
        if (!await Session.getSessionInformation(before.handle)) throw new Error("Link probe session was not persisted");
        const linked = await AccountLinking.linkAccounts(users[1].recipeUserId, primary.user.id);
        if (linked.status !== "OK" || linked.accountsAlreadyLinked || linked.user.id !== primary.user.id) {
            throw new Error("Link probe did not link the fresh secondary account to its fixture primary");
        }
        const remaining = await Session.getSessionInformation(before.handle);
        result = { accountLinked: true, before, primaryUserId: primary.user.id, sessionSurvivedLink: remaining !== undefined };
        try {
            const refreshed = await Session.refreshSessionWithoutRequestResponse(session.getAllSessionTokensDangerously().refreshToken, true);
            handles.push(refreshed.getHandle());
            result.after = { userId: refreshed.getUserId(), handle: refreshed.getHandle(), tenant: refreshed.getTenantId() };
            result.supported = result.sessionSurvivedLink &&
                [before.userId, before.handle, before.tenant, result.after.userId, result.after.handle, result.after.tenant, primary.user.id]
                    .every(value => typeof value === "string" && value.length > 0) &&
                result.after.userId === primary.user.id && result.after.userId !== before.userId &&
                result.after.handle === before.handle && result.after.tenant === before.tenant;
            result.reason = result.supported ? "SAME_SESSION_UID_CHANGE_OBSERVED" : "REFRESH_IDENTITY_INVARIANT_NOT_MET";
        } catch (error) {
            if (error.type !== "UNAUTHORISED") throw error;
            result.supported = false;
            result.refreshError = error.type;
            result.reason = remaining === undefined ? "LINK_REVOKED_SESSION" : "REFRESH_REJECTED_AFTER_LINK";
        }
    } catch (error) {
        failure = error;
    }

    const cleanupFailures = [];
    for (const handle of new Set(handles)) {
        try {
            await Session.revokeSession(handle);
            if (await Session.getSessionInformation(handle)) throw new Error("Link probe session remains after cleanup");
        } catch (error) { cleanupFailures.push(error); }
    }
    // Delete the secondary recipe first, so cleanup never relies on deleting linked accounts broadly.
    for (const owned of users.reverse()) {
        try {
            if (!owned.recipeUserId) {
                const found = await SuperTokens.listUsersByAccountInfo("public", { thirdParty: { id: "google", userId: owned.providerId } });
                for (const user of found) {
                    const method = user.loginMethods.find(value => value.thirdParty && value.thirdParty.id === "google" && value.thirdParty.userId === owned.providerId);
                    if (method) owned.recipeUserId = method.recipeUserId;
                }
            }
            if (owned.recipeUserId) {
                const deleted = await SuperTokens.deleteUser(owned.recipeUserId.getAsString(), false);
                if (deleted.status !== "OK") throw new Error("Link probe user deletion failed: " + deleted.status);
                if (await SuperTokens.getUser(owned.recipeUserId.getAsString()) !== undefined) throw new Error("Link probe user remains after cleanup");
            }
        } catch (error) { cleanupFailures.push(error); }
    }
    if (cleanupFailures.length) {
        const error = new Error("Link probe cleanup failed; disposable database cleanup required: " + cleanupFailures[0].message);
        error.cause = failure;
        throw error;
    }
    if (failure) throw failure;
    result.cleanupVerified = true;
    return result;
};
