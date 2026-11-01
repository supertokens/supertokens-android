// The Core lookup is independent of the client's local logout state.
module.exports = function ownerSessionState(owners, Session, enabled) {
    return async (req, res, next) => {
        try {
            if (!enabled) return res.status(501).json({ status: "UNSUPPORTED_TEST_CAPABILITY" });
            const { fixtureId, handle } = req.body;
            if (typeof fixtureId !== "string" || !owners.ownsSession(fixtureId, handle)) {
                return res.status(404).json({ status: "UNKNOWN_FIXTURE_SESSION" });
            }
            const session = await Session.getSessionInformation(handle);
            res.json({ owned: true, active: session !== undefined });
        } catch (error) { next(error); }
    };
};
