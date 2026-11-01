"""Host-only R8 artifact protocol regressions; every external tool is fake."""

import contextlib
import io
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import r8_smoke


def fixture_dex(name):
    # Minimal class/string/type tables consumed by the host DEX inventory, not an installable DEX.
    data = bytearray(152)
    struct.pack_into('<II', data, 56, 1, 112)
    struct.pack_into('<II', data, 64, 1, 116)
    struct.pack_into('<II', data, 96, 1, 120)
    struct.pack_into('<I', data, 112, len(data))
    data.extend(bytes([len(name)]) + name.encode() + b'\0')
    return data


class R8Protocol(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='r8-protocol-', dir=os.environ.get('TEST_TMPDIR'))
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.calls = []
        self.debuggable = None
        self.receipt = dict(schema=1, aarSha256='a' * 64, source=dict(gitRevision='c' * 40, dirty=True,
                            dirtyDiffSha256='d' * 64, untrackedSha256='e' * 64), exitCode=0,
                            command=['./gradlew', ':app:assembleDebug', '--rerun-tasks'],
                            startedAt='2026-10-29T12:00:00+00:00', finishedAt='2026-10-29T12:01:00+00:00',
                            buildLogSha256='f' * 64)
        (self.root / 'receipt.json').write_text(json.dumps(self.receipt))
        for prefix in ['a', 'b']:
            with zipfile.ZipFile(self.root / (prefix + '.apk'), 'w') as apk:
                apk.writestr('assets/sdk-provenance.json', json.dumps(dict(
                    aarSha256=self.receipt['aarSha256'], candidateBuild=self.receipt)))
                apk.writestr('classes.dex', fixture_dex('Lr8' + prefix + '/store;'))
            (self.root / (prefix + '.map')).write_text(
                'com.supertokens.session.SuperTokensPersistentCookieStore -> r8' + prefix + '.store:\n')
        with zipfile.ZipFile(self.root / 'test.apk', 'w') as apk:
            apk.writestr('classes.dex', fixture_dex('Lcom/supertokens/upgrade/R8SmokeTest;'))

    def fake_tool(self, argv, timeout, log, cancellation):
        self.calls.append(argv)
        tool = Path(argv[0]).name
        output = ''
        if tool == 'aapt':
            test = Path(argv[3]).name == 'test.apk'
            if argv[2] == 'badging':
                output = "package: name='com.supertokens.upgrade%s' versionCode='1'\nsdkVersion:'17'\n" % ('.test' if test else '')
            elif test:
                output = 'android:targetPackage="com.supertokens.upgrade"\nandroidx.test.runner.AndroidJUnitRunner\n'
            elif self.debuggable is not None:
                output = 'A: android:debuggable(0x0101000f)=' + self.debuggable + '\n'
        elif tool == 'apksigner':
            output = 'Signer #1 certificate SHA-256 digest: aa\n'
        elif tool == 'adb':
            args = argv[3:]
            if args[0] == 'install':
                self.assertEqual('-r', args[1])
                output = 'Success\n'
            elif args[0] == 'exec-out':
                output = '<map />'
            elif args[:3] == ['shell', 'am', 'instrument']:
                for code in [1, 0]:
                    output += ('INSTRUMENTATION_STATUS: numtests=1\n'
                               'INSTRUMENTATION_STATUS: class=com.supertokens.upgrade.R8SmokeTest\n'
                               'INSTRUMENTATION_STATUS: test=runStage\n'
                               'INSTRUMENTATION_STATUS_CODE: %s\n' % code)
                output += 'OK (1 test)\nINSTRUMENTATION_CODE: -1\n'
        else:
            raise AssertionError('Unexpected tool: ' + tool)
        log.write_text(output)
        return 0

    def invoke(self, stage, prefix='a', previous=None, output=None, extra=()):
        output = output or stage
        args = ['r8_smoke.py', '--serial', 'fake-serial', '--backend', 'http://10.0.2.2:8080',
                '--run-id', 'host-r8', '--stage', stage, '--app', str(self.root / (prefix + '.apk')),
                '--test', str(self.root / 'test.apk'), '--mapping', str(self.root / (prefix + '.map')),
                '--receipt', str(self.root / 'receipt.json'), '--output', str(self.root / output)]
        if previous:
            args += ['--mapping-a-evidence', str(previous)]
        args += extra
        with patch.object(sys, 'argv', args), patch.object(r8_smoke.shared, 'bounded_command', self.fake_tool), \
                patch.object(r8_smoke.signal, 'signal'), contextlib.redirect_stdout(io.StringIO()):
            r8_smoke.main()
        return self.root / output

    def assert_no_device_calls(self):
        self.assertFalse(any(Path(argv[0]).name == 'adb' for argv in self.calls))

    def test_same_a_for_b_rejected_before_device(self):
        previous = self.invoke('mapping-a')
        self.calls.clear()
        with self.assertRaisesRegex(RuntimeError, 'cookie-store name must change'):
            self.invoke('mapping-b', 'a', previous)
        self.assert_no_device_calls()

    def test_different_mapping_bytes_same_store_rejected(self):
        previous = self.invoke('mapping-a')
        (self.root / 'a.map').write_text((self.root / 'a.map').read_text() + '# changed unrelated mapping metadata\n')
        self.calls.clear()
        with self.assertRaisesRegex(RuntimeError, 'cookie-store name must change'):
            self.invoke('mapping-b', 'a', previous)
        self.assert_no_device_calls()

    def test_debuggable_a_and_b_rejected_before_device(self):
        previous = self.invoke('mapping-a')
        for stage, prefix in [('mapping-a', 'a'), ('mapping-b', 'b')]:
            for encoding in ['(type 0x12)0xffffffff', '"true"']:
                with self.subTest(stage=stage, encoding=encoding):
                    self.debuggable = encoding
                    self.calls.clear()
                    with self.assertRaisesRegex(RuntimeError, 'debuggable'):
                        self.invoke(stage, prefix, previous if stage == 'mapping-b' else None,
                                    output=stage + '-' + str(len(list(self.root.iterdir()))))
                    self.assert_no_device_calls()

    def test_valid_archived_a_then_b_passes(self):
        self.debuggable = '(type 0x12)0x0'
        previous = self.invoke('mapping-a')
        self.debuggable = None
        result = self.invoke('mapping-b', 'b', previous)
        self.assertEqual('mapping-b\n', (result / 'PASS').read_text())
        evidence = json.loads((result / 'run.json').read_text())
        self.assertEqual('r8a.store', evidence['mappingAEvidence']['cookieStore'])
        self.assertEqual('r8b.store', evidence['cookieStore'])

    def test_unknown_debuggable_value_rejected(self):
        self.debuggable = '(type 0x1)0x7f010000'
        with self.assertRaisesRegex(RuntimeError, 'unrecognized debuggable'):
            self.invoke('mapping-a')
        self.assert_no_device_calls()

    def test_tampered_prior_mapping_rejected(self):
        previous = self.invoke('mapping-a')
        (previous / 'mapping.txt').write_text('tampered')
        self.calls.clear()
        with self.assertRaisesRegex(RuntimeError, 'artifact hash mismatch'):
            self.invoke('mapping-b', 'b', previous)
        self.assert_no_device_calls()

    def test_wrong_run_archive_rejected(self):
        previous = self.invoke('mapping-a')
        run = json.loads((previous / 'run.json').read_text())
        run['runId'] = 'another-run'
        (previous / 'run.json').write_text(json.dumps(run))
        self.calls.clear()
        with self.assertRaisesRegex(RuntimeError, 'completed stage from this run'):
            self.invoke('mapping-b', 'b', previous)
        self.assert_no_device_calls()

    def test_incomplete_archive_rejected(self):
        previous = self.invoke('mapping-a')
        (previous / 'PASS').write_text('seed\n')
        self.calls.clear()
        with self.assertRaisesRegex(RuntimeError, 'completed stage from this run'):
            self.invoke('mapping-b', 'b', previous)
        self.assert_no_device_calls()


if __name__ == '__main__':
    unittest.main()
