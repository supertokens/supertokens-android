"""Synthetic observer archives test parser semantics, not a live backend."""
import json
import os
from pathlib import Path
import tempfile
import unittest
import subprocess

from native_backend import verify_backend
from native_archive import sha
from test_matrix_native import config, identity, stage


def write_backend(owned, source, stages, started, ended):
    owned.mkdir(mode=0o700)
    (owned / 'owner.json').write_text(json.dumps(dict(root=str(owned), runId=source['runId'])))
    (owned / 'dependencies.sha256').write_text(source['dependenciesSha256'])
    (owned / 'exposure-config.json').write_text(json.dumps(dict(identity=identity(source), runId=source['runId'], instance=source['instance'])))
    base = owned / 'native-evidence'
    base.mkdir(mode=0o700)
    directory = base / source['nativeNonce']
    directory.mkdir(mode=0o700)
    control = dict(schema=1, kind='native-backend-control', runId=source['runId'], instance=source['instance'],
                   nativeNonce=source['nativeNonce'], challenge=source['challenge'], finalizeKey='0123456789abcdef' * 3)
    snapshot = dict(identity=identity(source), qualifiedIdentitySha256=source['qualifiedIdentitySha256'],
                    dependenciesSha256=source['dependenciesSha256'], coreManifestSha256=source['coreManifestSha256'])
    events = []
    def event(event_type, **data):
        value = dict(schema=1, runId=source['runId'], instance=source['instance'], nativeNonce=source['nativeNonce'],
                     sequence=len(events) + 1, timeMs=started + len(events) + 1, type=event_type)
        value.update(data)
        events.append(value)
    event('row-open', **snapshot)
    login_count = 0
    operations = [(value['stage'], operation) for value in stages for operation in value['clientOperations']]
    operations += [('cleanup', 'isolated:/test/native/finalize')]
    for index, (phase, operation) in enumerate(operations):
        if operation == 'sdk.refresh':
            path = '/auth/session/refresh'
        elif operation == 'sdk.signOut':
            path = '/auth/signout'
        else:
            path = operation.split(':', 1)[1]
        method = 'GET' if path in ['/test/capabilities', '/featureFlags', '/'] else 'POST'
        rid = index + 1
        headers = {'x-st-test-run-id': source['runId'], 'x-st-native-nonce': source['nativeNonce'],
                   'x-st-native-stage': phase, 'fdi-version': source['context']['fdi']}
        if path == '/login':
            headers['x-st-native-fixture-id'] = 'synthetic-owner'
        event('request', requestId=rid, method=method, path=path, headers=headers)
        if path == '/test/native/challenge':
            event('challenge', requestId=rid, **stages[0]['challengeResponse'])
        elif path == '/test/email-change-owner':
            event('fixture-created', requestId=rid, fixtureId='synthetic-owner', userId='synthetic-user',
                  provisioningHandle='provisioned-only', creationRequestId=source['nativeNonce'] + '-owner')
        elif path == '/login':
            login_count += 1
            event('login-session', requestId=rid, fixtureId='synthetic-owner', userId='synthetic-user', handle='actual-A' if login_count == 1 else 'actual-B')
        elif path == '/test/owner-session-state':
            event('session-state', requestId=rid, fixtureId='synthetic-owner', handle='actual-A' if login_count == 1 else 'actual-B', active=False)
        elif path == '/test/cleanup-owner':
            event('owner-cleanup', requestId=rid, fixtureId='synthetic-owner', cleaned=True)
        event('response-finish', requestId=rid, statusCode=200)
        event('response-close', requestId=rid, afterFinish=True)
    event('row-complete', **snapshot)
    for item in events[-4:]:
        item['timeMs'] = ended + 1
    complete = dict(schema=1, kind='native-backend-complete', runId=source['runId'], instance=source['instance'],
                    nativeNonce=source['nativeNonce'], **snapshot)
    (directory / 'control.json').write_text(json.dumps(control))
    seal(directory, events, complete)
    for file in directory.iterdir():
        file.chmod(0o600)
    return directory


def seal(directory, events, complete=None):
    data = ('\n'.join(json.dumps(item, separators=(',', ':')) for item in events) + '\n').encode()
    if complete is None:
        complete = json.loads((directory / 'complete.json').read_text())
    complete.update(eventsSha256=sha(data), eventCount=len(events))
    (directory / 'events.jsonl').write_bytes(data)
    (directory / 'complete.json').write_text(json.dumps(complete))


class BackendArchiveControls(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='native-backend-parser-', dir=os.environ.get('TEST_TMPDIR'))
        self.addCleanup(self.temp.cleanup)
        self.owned = Path(self.temp.name) / 'owned'
        self.source = config()
        self.stages = [stage(self.source, action) for action in ['seed', 'check-initial', 'cleanup']]
        self.directory = write_backend(self.owned, self.source, self.stages, 1000, 3000)
        binding = dict(self.source, nodeVersion=self.source['plan']['nodeVersion'], coreVersion=self.source['plan']['coreVersion'])
        self.expected = dict(binding=binding, endedMs=4000)

    def verify(self):
        return verify_backend(self.owned, self.source, self.expected, self.stages, 1000)

    def test_complete_actual_shape(self):
        facts = self.verify()
        self.assertEqual(0, facts['pendingCreations'])
        self.assertTrue(facts['ownerReceiptsResolved'])

    def test_reject_semantic_mutations_even_with_updated_hashes(self):
        original = (self.directory / 'events.jsonl').read_text()
        mutations = [
            ('request', lambda x: x['headers'].update({'fdi-version': '1.16'})),
            ('request', lambda x: x.update(path='/test/protected')),
            ('login-session', lambda x: x.update(handle='provisioned-only')),
            ('login-session', lambda x: x.update(userId='someone-else')),
            ('session-state', lambda x: x.update(active=True)),
            ('session-state', lambda x: x.update(handle='unowned')),
            ('response-close', lambda x: x.update(afterFinish=False)),
            ('response-finish', lambda x: x.update(statusCode=500)),
            ('owner-cleanup', lambda x: x.update(cleaned=False)),
            ('challenge', lambda x: x.update(challenge='0' * 48)),
            ('row-complete', lambda x: x.update(dependenciesSha256='0' * 64)),
        ]
        for kind, change in mutations:
            with self.subTest(kind=kind):
                events = [json.loads(line) for line in original.splitlines()]
                change(next(item for item in events if item['type'] == kind))
                seal(self.directory, events)
                with self.assertRaises(ValueError):
                    self.verify()

    def test_missing_completion_and_late_append_rejected(self):
        with (self.directory / 'events.jsonl').open('a') as file:
            file.write('{}\n')
        with self.assertRaises(ValueError):
            self.verify()
        (self.directory / 'complete.json').unlink()
        with self.assertRaises(OSError):
            self.verify()

    def test_actual_server_observer_archives_for_both_flows(self):
        for flow in ['REFRESH', 'SIGN_OUT']:
            with self.subTest(flow=flow):
                source = config()
                source['flow'] = flow
                if flow == 'SIGN_OUT':
                    source['plan']['phases']['publishedHandoff'] = 'ABSENT'
                stages = [stage(source, action) for action in ['seed', 'check-initial', 'cleanup']]
                owned = Path(self.temp.name) / ('actual-' + flow)
                sibling = Path(self.temp.name) / ('archive-' + flow) / source['nativeNonce']
                result = subprocess.run(['node', str(Path(__file__).parent / 'tests/native_backend_fixture.js')],
                    input=json.dumps(dict(owned=str(owned), source=source, stages=stages, sibling=str(sibling))),
                    capture_output=True, text=True, timeout=30)
                self.assertEqual(0, result.returncode, result.stderr)
                actual = json.loads(result.stdout)
                binding = dict(source, nodeVersion=source['plan']['nodeVersion'], coreVersion=source['plan']['coreVersion'])
                facts = verify_backend(owned, source, dict(binding=binding, endedMs=actual['endedMs'] + 1000),
                    actual['stages'], actual['startedMs'], actual['endedMs'], sibling)
                self.assertEqual(0, facts['pendingCreations'])
                self.assertTrue(facts['ownerReceiptsResolved'])


if __name__ == '__main__':
    unittest.main()
