def factory = new GroovyShell().evaluate(new File(args[0]))
def type = 'com.supertokens.session.legacy054.LegacyRefreshOutcomeMatrixTest'
def bare = 'missingRefreshCookieBare401RetainsSessionOnBothTransports'
def removal = 'missingRefreshCookieExplicitRemovalMatchesPublishedOnBothTransports'
def rejects = { recorder ->
    try {
        recorder.verify()
        throw new AssertionError('Incomplete or invalid required outcomes were accepted')
    } catch (IllegalStateException expected) {
        assert expected.message == 'Required controlled oracle methods must each succeed exactly once'
    }
}

def complete = factory()
complete.record(type, bare, 'SUCCESS')
complete.record(type, removal, 'SUCCESS')
complete.record('unrelated.Class', bare, 'FAILURE')
complete.record(type, 'unrelatedMethod', 'SKIPPED')
complete.verify()

for (missing in [bare, removal]) {
    def recorder = factory()
    recorder.record(type, missing == bare ? removal : bare, 'SUCCESS')
    rejects(recorder)
}
for (bad in ['SKIPPED', 'FAILURE']) {
    for (method in [bare, removal]) {
        def recorder = factory()
        recorder.record(type, bare, method == bare ? bad : 'SUCCESS')
        recorder.record(type, removal, method == removal ? bad : 'SUCCESS')
        rejects(recorder)
    }
}
for (prior in ['SUCCESS', 'FAILURE']) {
    def recorder = factory()
    recorder.record(type, bare, prior)
    recorder.record(type, bare, 'SUCCESS')
    recorder.record(type, removal, 'SUCCESS')
    rejects(recorder)
}
def wrongIdentity = factory()
wrongIdentity.record('other.Class', bare, 'SUCCESS')
wrongIdentity.record(type, removal + '[0]', 'SUCCESS')
rejects(wrongIdentity)
rejects(factory())
