"""Host protocol checks; fake tools cannot establish Android persistence."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile
from build_provenance import digest, validate
from archive_receipt import archive_receipt

RUNNER = Path(__file__).resolve().parents[2] / 'device/upgrade.sh'
PIN = (RUNNER.parents[2] / 'app/src/test/compatibility/legacy054.sha256').read_text().strip()
FAKE = r'''#!/usr/bin/env python3
import json, os, sys, time
from pathlib import Path
tool = Path(sys.argv[0]).name
a = sys.argv[1:]
mode = os.environ.get('MODE', '')
with open(os.environ['CALLS'], 'a') as log: log.write(json.dumps([tool] + a) + '\n')
if tool == 'aapt':
    label = Path(a[2]).stem
    if a[1] == 'xmltree':
        print('android:targetPackage="com.supertokens.upgrade"')
        print('androidx.test.runner.AndroidJUnitRunner')
        if mode == 'test-only' and label.endswith('-test'): print('A: android:testOnly(0x01010272)=(type 0x12)0xffffffff')
        if mode == 'bad-test-only': print('A: android:testOnly(0x01010272)=unknown')
    else:
        package = 'com.supertokens.upgrade' + ('.test' if label.endswith('-test') else '')
        if mode == 'package' and label == 'candidate': package = 'wrong'
        version = '2' if mode == 'version' and label == 'candidate' else '1'
        print("package: name='%s' versionCode='%s'" % (package, version))
elif tool == 'apksigner':
    cert = 'bb' if mode == 'signature' and Path(a[-1]).stem == 'candidate' else 'aa'
    print('Signer #1 certificate SHA-256 digest: ' + cert)
else:
    assert a[:2] == ['-s', 'fake-serial']
    a = a[2:]
    if a == ['get-state']: print('device')
    elif a[:2] == ['shell', 'getprop']: print('34')
    elif a[0] == 'install':
        assert a[1] == '-r'
        print('Failure' if mode == 'install' else 'Success')
    elif a[:3] == ['shell', 'am', 'force-stop']:
        if mode == 'stop': sys.exit(1)
    elif a[0] == 'exec-out':
        print('<broken' if mode == 'xml' else '<map><string name="secret">TOKEN_SENTINEL</string><long name="revision" value="3" /></map>')
    elif a[:3] == ['shell', 'am', 'instrument']:
        if mode == 'hang': time.sleep(60)
        for code in [1, 0]:
            print('INSTRUMENTATION_STATUS: numtests=1')
            print('INSTRUMENTATION_STATUS: class=com.supertokens.upgrade.UpgradeTest')
            print('INSTRUMENTATION_STATUS: test=' + ('wrong' if mode == 'method' else 'runStage'))
            print('INSTRUMENTATION_STATUS_CODE: ' + str(code))
        print('OK (0 tests)' if mode == 'zero' else 'OK (1 test)')
        print('INSTRUMENTATION_CODE: -1')
    else: raise AssertionError(a)
'''


class UpgradeProtocol(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='upgrade-protocol-', dir=os.environ.get('TEST_TMPDIR'))
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.calls = self.root / 'calls.jsonl'
        self.env = dict(os.environ, CALLS=str(self.calls))
        for tool in ['adb', 'aapt', 'apksigner']:
            path = self.root / tool
            path.write_text(FAKE)
            path.chmod(0o700)
            self.env[tool.upper()] = str(path)
        self.args = ['sh', str(RUNNER), '--serial', 'fake-serial', '--backend', 'http://10.0.2.2:8080',
                     '--output', str(self.root / 'results'), '--candidate-aar-sha256', 'a' * 64]
        self.receipt = dict(schema=1, aarSha256='a' * 64, source=dict(gitRevision='c' * 40, dirty=True,
                            dirtyDiffSha256='d' * 64, untrackedSha256='e' * 64), exitCode=0,
                            command=['./gradlew', ':app:assembleDebug', '--rerun-tasks'],
                            startedAt='2026-10-29T12:00:00+00:00', finishedAt='2026-10-29T12:01:00+00:00',
                            buildLogSha256='f' * 64)
        self.receipt_path = self.root / 'candidate-build.json'
        self.receipt_path.write_text(json.dumps(self.receipt))
        self.args += ['--candidate-build-provenance', str(self.receipt_path)]
        for label in ['sdk', 'sdk-test', 'candidate', 'candidate-test']:
            apk = self.root / (label + '.apk')
            with zipfile.ZipFile(apk, 'w') as archive:
                provenance = dict(
                    sdk='sdk054' if label == 'sdk' else 'candidate', aarSha256=PIN if label == 'sdk' else 'a' * 64,
                    applicationId='com.supertokens.upgrade', versionCode=1)
                if label == 'candidate': provenance['candidateBuild'] = self.receipt
                archive.writestr('assets/sdk-provenance.json', json.dumps(provenance))
            self.args += ['--' + label + '-apk', str(apk)]

    def run_case(self, mode='', extra=()):
        result = subprocess.run(self.args + list(extra), env=dict(self.env, MODE=mode),
                                capture_output=True, text=True, timeout=20)
        self.assertNotIn('TOKEN_SENTINEL', result.stdout + result.stderr)
        return result

    def commands(self):
        return [json.loads(line) for line in self.calls.read_text().splitlines()] if self.calls.exists() else []

    def test_reject_baseline_as_candidate(self):
        result = self.run_case(extra=['--candidate-aar-sha256', PIN])
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.commands(), [])

    def test_provenance_must_match_embedded_build(self):
        self.receipt['source']['dirtyDiffSha256'] = '9' * 64
        self.receipt_path.write_text(json.dumps(self.receipt))
        result = self.run_case()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(c[0] == 'adb' for c in self.commands()))

    def test_source_provenance_required(self):
        del self.receipt['source']['gitRevision']
        with self.assertRaises(ValueError): validate(self.receipt, 'a' * 64)

    def test_archived_producer_receipt_survives_runner(self):
        manifest = json.dumps({'app/src/main/Example.java': 'e' * 64})
        producer = json.dumps(dict(gitHead='c' * 40, uncommitted=True,
                                  sourceBuildManifestSha256=digest(manifest.encode()),
                                  artifacts=dict(release=dict(sha256='a' * 64))))
        archived = dict(schema=2, kind='archived-build', variant='release', aarSha256='a' * 64,
                        producerProvenanceJson=producer, sourceBuildManifestJson=manifest, producerResultMarkdown='M4',
                        source=dict(gitRevision='c' * 40, dirty=True, sourceBuildManifestSha256=digest(manifest.encode()),
                                    provenanceSha256=digest(producer.encode()), resultSha256=digest(b'M4')))
        self.receipt_path.write_text(json.dumps(archived))
        with zipfile.ZipFile(self.root / 'candidate.apk', 'w') as apk:
            apk.writestr('assets/sdk-provenance.json', json.dumps(dict(sdk='candidate', aarSha256='a' * 64,
                applicationId='com.supertokens.upgrade', versionCode=1, candidateBuild=archived)))
        result = self.run_case(extra=['--scenario', 'logout'])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads((self.root / 'results/run.json').read_text())['candidateBuild'], archived)

    def test_failed_creation_stage_retains_recovery_identifiers(self):
        result = self.run_case('zero', ['--run-id', 'recover-run', '--scenario', 'login-b'])
        self.assertNotEqual(result.returncode, 0)
        recovery = json.loads((self.root / 'results/recovery.json').read_text())
        self.assertEqual([r['requestId'] for r in recovery['requests']], ['recover-run-login-b-A', 'recover-run-login-b-B'])
        self.assertEqual(recovery['requests'][0]['email'], 'recover-run-login-b-A@upgrade.example')
        self.assertIn('pendingRequestId', result.stderr)
        self.assertIn('do not retry creation', result.stderr)
        self.assertEqual(len([c for c in self.commands() if 'instrument' in c]), 1)

    def test_test_only_requires_explicit_opt_in(self):
        result = self.run_case('test-only')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(c[0] == 'adb' for c in self.commands()))

    def test_test_only_installs_flagged_apks_only(self):
        result = self.run_case('test-only', ['--allow-test-only', '--scenario', 'logout'])
        self.assertEqual(result.returncode, 0, result.stderr)
        for command in self.commands():
            if 'install' in command:
                self.assertEqual('-t' in command, command[-1].endswith('-test.apk'))
        receipt = json.loads((self.root / 'results/run.json').read_text())
        self.assertEqual(receipt['candidateBuild'], self.receipt)
        self.assertTrue(receipt['artifacts']['candidate-test']['testOnly'])

    def test_all_stage_order_and_typed_captures(self):
        result = self.run_case()
        self.assertEqual(result.returncode, 0, result.stderr)
        commands = self.commands()
        stages = [c[c.index('stage') + 1] for c in commands if 'instrument' in c]
        self.assertEqual(stages, ['seed', 'check-initial', 'mutate', 'check-final', 'cleanup'] * 4)
        installs = [c for c in commands if 'install' in c]
        self.assertEqual(len(installs), 32)
        self.assertTrue(all(c[4] == '-r' for c in installs))
        for index, c in enumerate(commands):
            if 'instrument' in c: self.assertIn('force-stop', commands[index - 1])
            self.assertFalse(any(word in c for word in ['uninstall', 'clear']))
        captures = json.loads((self.root / 'results/captures.json').read_text())
        self.assertEqual(len(captures), 45)
        self.assertTrue(all(c['types'] == ['string', 'long'] for c in captures))

    def test_cleanup_only(self):
        result = self.run_case(extra=['--cleanup-only', '--run-id', 'original-run', '--scenario', 'cookie'])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([c[c.index('stage') + 1] for c in self.commands() if 'instrument' in c], ['cleanup'])

    def test_timeout(self):
        result = self.run_case('hang', ['--test-timeout', '0.2'])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('timed out', (self.root / 'results/login-b-seed-instrument.txt').read_text())

    def test_cancellation(self):
        process = subprocess.Popen(self.args, env=dict(self.env, MODE='hang'), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                if self.calls.exists() and any('instrument' in c for c in self.commands()): break
                time.sleep(0.02)
            else: self.fail('Instrumentation never started')
            process.send_signal(signal.SIGTERM)
            stdout, stderr = process.communicate(timeout=5)
            self.assertEqual(process.returncode, 143, stdout + stderr)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()


def rejection(mode):
    def test(self):
        extra = ['--candidate-aar-sha256', 'b' * 64] if mode == 'hash' else []
        result = self.run_case(mode, extra)
        self.assertNotEqual(result.returncode, 0)
        if mode in ['package', 'version', 'signature', 'hash']:
            self.assertFalse(any(c[0] == 'adb' for c in self.commands()))
    return test


for mode in ['package', 'version', 'signature', 'hash', 'install', 'stop', 'xml', 'method', 'zero', 'bad-test-only']:
    setattr(UpgradeProtocol, 'test_reject_' + mode, rejection(mode))


class ArchivedProvenance(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='archive-receipt-', dir=os.environ.get('TEST_TMPDIR'))
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.aar = self.root / 'release.aar'
        with zipfile.ZipFile(self.aar, 'w') as aar:
            aar.writestr('classes.jar', b'archived-classes')
        self.manifest = json.dumps({'app/src/main/Example.java': 'e' * 64})
        (self.root / 'source-build-manifest.json').write_text(self.manifest)
        (self.root / 'RESULT.md').write_text('Archived build, not current source')
        self.producer = dict(gitHead='c' * 40, uncommitted=True, sourceBuildManifestSha256=digest(self.manifest.encode()),
            artifacts=dict(release=dict(archivedPath=str(self.aar), sha256=digest(self.aar.read_bytes()),
                bytes=self.aar.stat().st_size, classesJarSha256=digest(b'archived-classes'))))
        (self.root / 'provenance.json').write_text(json.dumps(self.producer))

    def test_preserves_original_source_evidence(self):
        receipt = archive_receipt(self.root, 'release')
        self.assertEqual(receipt['source']['gitRevision'], 'c' * 40)
        self.assertNotIn('dirtyDiffSha256', receipt['source'])
        self.assertEqual(json.loads(receipt['producerProvenanceJson']), self.producer)

    def test_rejects_modified_archive(self):
        self.aar.write_bytes(b'changed artifact')
        with self.assertRaises(ValueError): archive_receipt(self.root, 'release')

    def test_rejects_replaced_source_manifest(self):
        (self.root / 'source-build-manifest.json').write_text('{}')
        with self.assertRaises(ValueError): archive_receipt(self.root, 'release')

if __name__ == '__main__':
    unittest.main()
