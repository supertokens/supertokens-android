async function updateWithHandle(Session, session, payload, isProtectedPropName) {
    if (typeof Session.updateJWTPayload === "function") {
        await Session.updateJWTPayload(session.getHandle(), payload);
        return session.getJWTPayload();
    }
    if (typeof Session.updateAccessTokenPayload === "function") {
        await Session.updateAccessTokenPayload(session.getHandle(), payload);
    } else {
        const clearing = {};
        for (const key of Object.keys(session.getAccessTokenPayload())) {
            if (!isProtectedPropName(key)) clearing[key] = null;
        }
        await Session.mergeIntoAccessTokenPayload(session.getHandle(), { ...clearing, ...payload });
    }
    return session.getAccessTokenPayload();
}

async function revokeAll(Session, SuperTokens, userId) {
    if (typeof Session.revokeAllSessionsForUser === "function") {
        return Session.revokeAllSessionsForUser(userId);
    }
    return SuperTokens.revokeAllSessionsForUser(userId);
}

module.exports = { updateWithHandle, revokeAll };
