return { ->
    def expectedClass = 'com.supertokens.session.legacy054.LegacyRefreshOutcomeMatrixTest'
    def observed = [
        missingRefreshCookieBare401RetainsSessionOnBothTransports: [],
        missingRefreshCookieExplicitRemovalMatchesPublishedOnBothTransports: []
    ]
    [record: { String className, String method, String outcome ->
        synchronized (observed) {
            if (className == expectedClass && observed.containsKey(method)) observed[method] << outcome
        }
    }, verify: {
        synchronized (observed) {
            if (!observed.values().every { it == ['SUCCESS'] }) {
                throw new IllegalStateException('Required controlled oracle methods must each succeed exactly once')
            }
        }
    }]
}
