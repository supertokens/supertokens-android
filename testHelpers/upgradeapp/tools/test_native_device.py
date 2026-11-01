"""Real host child-process controls only; no ADB/device/backend calls."""
import os
from pathlib import Path
import subprocess
import sys
import time
from types import SimpleNamespace
import tempfile
import unittest
from unittest import mock

import native_device
from upgrade import shared


class ReverseRoutes(unittest.TestCase):
    def test_adb_empty_list_newline_is_no_mapping(self):
        self.assertEqual([], native_device.routes('\n'))
        self.assertEqual([], native_device.routes(''))

    def test_nonempty_invalid_mapping_still_refused(self):
        with self.assertRaisesRegex(RuntimeError, 'Unrecognized adb reverse mapping'):
            native_device.routes('unexpected output\n')
        with self.assertRaisesRegex(RuntimeError, 'Ambiguous reverse mappings'):
            native_device.routes('host tcp:18085 tcp:18085\nhost tcp:18085 tcp:19005\n')


class PackageDiscovery(unittest.TestCase):
    def test_only_exact_names_from_strict_listing_establish_presence(self):
        target = 'com.supertokens.upgrade'
        self.assertNotIn(target, native_device.packages('package:' + target + '.test\npackage:prefix.' + target + '\n'))
        self.assertIn(target, native_device.packages('package:' + target + '\n'))
        self.assertEqual(set(), native_device.packages(''))
        for value in ['error: device offline', 'SecurityException: denied', 'package:', 'package:bad name',
                      ' package:android', 'package:android ', 'package:android\npackage:android', ' \n']:
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                native_device.packages(value)

    def test_present_path_must_be_nonempty_and_valid(self):
        for value in ['', '\n', 'Error: not found', 'package:', 'package:/data/app/base.apk\nPermission denied']:
            with self.subTest(value=value), self.assertRaises(RuntimeError):
                native_device.package_paths(value)
        self.assertEqual(['/system/framework/framework-res.apk'], native_device.package_paths('package:/system/framework/framework-res.apk\n'))


class CommandOwnership(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='native-host-groups-', dir=os.environ.get('TEST_TMPDIR'))
        self.addCleanup(self.temp.cleanup)
        self.log = Path(self.temp.name) / 'command.txt'

    def test_normal_exit_is_preserved_after_unreaped_group_drain(self):
        code, proof = native_device.run_command([sys.executable, '-c', 'print("done")'], 5, self.log, shared.Cancellation())
        self.assertEqual(0, code)
        self.assertEqual(0, proof['actualExit'])
        self.assertTrue(all(item['state'].startswith('Z') for item in proof['afterKill']))
        self.assertEqual('done\n', self.log.read_text())

    def test_darwin_mach_unknown_is_parsed_but_not_dead(self):
        with mock.patch.object(native_device.sys, 'platform', 'darwin'), mock.patch.object(
                native_device.subprocess, 'run', return_value=SimpleNamespace(stdout='123 123 ?Es\n')):
            rows = native_device.group_snapshot(123)
        self.assertEqual('?Es', rows[0]['state'])
        self.assertFalse(rows[0]['state'].startswith('Z'))
        for state in ['Zbogus', 'Z?', 'garbage', '?garbage']:
            with self.subTest(state=state), mock.patch.object(native_device.sys, 'platform', 'darwin'), mock.patch.object(
                    native_device.subprocess, 'run', return_value=SimpleNamespace(stdout='123 123 ' + state + '\n')):
                with self.assertRaisesRegex(RuntimeError, 'Unrecognized process state'):
                    native_device.group_snapshot(123)

    def test_transient_mach_unknown_waits_for_observed_zombie(self):
        snapshot = native_device.group_snapshot
        observations = []
        def unknown_once(pid):
            rows = snapshot(pid)
            if not observations:
                rows = [dict(row, state='?Es') for row in rows]
            observations.append(rows)
            return rows
        with mock.patch.object(native_device, 'group_snapshot', side_effect=unknown_once):
            code, proof = native_device.run_command([sys.executable, '-c', 'import time; time.sleep(.1); print("completed")'],
                5, self.log, shared.Cancellation())
        self.assertEqual(0, code)
        self.assertEqual('completed\n', self.log.read_text())
        self.assertEqual('?Es', observations[0][0]['state'])
        self.assertTrue(all(row['state'].startswith('Z') for row in proof['afterKill']))

    def test_persistent_unknown_cannot_prove_drain(self):
        def unknown(pid):
            return [dict(pid=pid, pgid=pid, state='?Es')]
        with mock.patch.object(native_device, 'group_snapshot', side_effect=unknown):
            with self.assertRaisesRegex(RuntimeError, 'did not drain'):
                native_device.run_command([sys.executable, '-c', 'import time; time.sleep(30)'], .05, self.log, shared.Cancellation())

    def test_broken_ps_still_kills_and_reaps_owned_child(self):
        children = []
        spawn = subprocess.Popen
        def tracked(*args, **kwargs):
            child = spawn(*args, **kwargs)
            children.append(child)
            return child
        try:
            with mock.patch.object(native_device.subprocess, 'Popen', side_effect=tracked), mock.patch.object(
                    native_device, 'group_snapshot', side_effect=RuntimeError('ps unavailable')):
                with self.assertRaisesRegex(RuntimeError, 'ps unavailable'):
                    native_device.run_command([sys.executable, '-c', 'import time; time.sleep(30)'], 5, self.log, shared.Cancellation())
            self.assertIsNotNone(children[0].returncode, 'Failed observation bypassed kill/wait')
        finally:
            for child in children:
                if child.poll() is None:
                    child.kill()
                    child.wait()

    def test_orphan_instrumentation_is_not_claimed(self):
        lease = native_device.DeviceLease(Path(self.temp.name), 'serial',
            dict(nativeNonce='a', runId='b', instance='c', preparationNonce='d'), self.temp.name)
        calls = []
        def adb(argv, output):
            calls.append(argv)
            return 'package:com.supertokens.upgrade.test' if argv[-1] == 'com.supertokens.upgrade.test' else ''
        with mock.patch.object(lease, 'check'):
            with self.assertRaisesRegex(RuntimeError, 'instrumentation'):
                lease.claim_fixture_app(adb)
        self.assertFalse(lease.app_owned)

    def test_unknown_instrumentation_with_recognized_app_is_not_claimed(self):
        lease = native_device.DeviceLease(Path(self.temp.name), 'serial',
            dict(nativeNonce='a', runId='b', instance='c', preparationNonce='d'), self.temp.name)
        for label in ['sdk-test', 'candidate-test']:
            (Path(self.temp.name) / (label + '.apk')).write_bytes(label.encode())
        def adb(argv, output):
            if argv[:4] == ['shell', 'pm', 'list', 'packages']:
                return 'package:' + argv[-1]
            if argv[:3] == ['shell', 'pm', 'path']:
                return 'package:/data/app/' + argv[-1] + '/base.apk'
            if argv[:2] == ['shell', 'sha256sum']:
                return '0' * 64 + '  ' + argv[-1]
            return '<map><boolean name="cleanupDone" value="true" /></map>'
        with mock.patch.object(lease, 'check'):
            with self.assertRaisesRegex(RuntimeError, 'instrumentation does not match'):
                lease.claim_fixture_app(adb)
        self.assertFalse(lease.app_owned)

    def test_failed_ps_drains_descendant_and_preserves_unrelated_process(self):
        pidfile = Path(self.temp.name) / 'child.pid'
        descendant = 'import os,time; open(' + repr(str(pidfile)) + ',"w").write(str(os.getpid())); time.sleep(30)'
        script = 'import subprocess,sys,time; subprocess.Popen([sys.executable,"-c",' + repr(descendant) + ']); time.sleep(30)'
        unrelated = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'], start_new_session=True)
        def failed_snapshot(pid):
            deadline = time.monotonic() + 5
            while not pidfile.exists() and time.monotonic() < deadline:
                time.sleep(.01)
            raise RuntimeError('ps persistently unavailable')
        try:
            with mock.patch.object(native_device, 'group_snapshot', side_effect=failed_snapshot):
                with self.assertRaisesRegex(RuntimeError, 'ps persistently unavailable'):
                    native_device.run_command([sys.executable, '-c', script], 10, self.log, shared.Cancellation())
            state = subprocess.run(['ps', '-p', pidfile.read_text(), '-o', 'stat='], capture_output=True, text=True).stdout.strip()
            self.assertTrue(not state or state.startswith('Z'))
            self.assertIsNone(unrelated.poll())
        finally:
            unrelated.terminate()
            unrelated.wait(timeout=5)

    def test_verifier_deadline_drains_signer_descendant(self):
        pidfile = Path(self.temp.name) / 'signer.pid'
        descendant = 'import os,time; open(' + repr(str(pidfile)) + ',"w").write(str(os.getpid())); time.sleep(30)'
        verifier = 'import subprocess,sys,time; subprocess.Popen([sys.executable,"-c",' + repr(descendant) + ']); time.sleep(30)'
        bridge = 'import sys; from native_verifier_process import supervise; sys.exit(supervise([sys.executable,"-c",' + repr(verifier) + '], .5))'
        result = subprocess.run([sys.executable, '-c', bridge], capture_output=True, timeout=10)
        self.assertEqual(124, result.returncode, result.stderr)
        pid = int(pidfile.read_text())
        state = subprocess.run(['ps', '-p', str(pid), '-o', 'stat='], capture_output=True, text=True).stdout.strip()
        self.assertTrue(not state or state.startswith('Z'), 'Signer survived outer verifier timeout: ' + state)

    def test_verifier_cancellation_drains_signer_descendant(self):
        pidfile = Path(self.temp.name) / 'signer.pid'
        descendant = 'import os,time; open(' + repr(str(pidfile)) + ',"w").write(str(os.getpid())); time.sleep(30)'
        verifier = 'import subprocess,sys,time; subprocess.Popen([sys.executable,"-c",' + repr(descendant) + ']); time.sleep(30)'
        bridge = 'import sys; from native_verifier_process import supervise; sys.exit(supervise([sys.executable,"-c",' + repr(verifier) + ']))'
        process = subprocess.Popen([sys.executable, '-c', bridge], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            deadline = time.monotonic() + 5
            while not pidfile.exists() and time.monotonic() < deadline:
                time.sleep(.01)
            self.assertTrue(pidfile.exists())
            process.terminate()
            process.communicate(timeout=10)
            self.assertEqual(124, process.returncode)
            state = subprocess.run(['ps', '-p', pidfile.read_text(), '-o', 'stat='], capture_output=True, text=True).stdout.strip()
            self.assertTrue(not state or state.startswith('Z'))
        finally:
            if process.poll() is None:
                process.terminate()
                process.communicate(timeout=10)

    def test_timeout_drains_owned_group(self):
        code, proof = native_device.run_command([sys.executable, '-c', 'import time; time.sleep(10)'], .1, self.log, shared.Cancellation())
        self.assertEqual(124, code)
        self.assertEqual('timeout', proof['reason'])
        self.assertTrue(all(item['state'].startswith('Z') for item in proof['afterKill']))

    def test_descendant_drained_without_signalling_unrelated_process(self):
        unrelated = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(10)'], start_new_session=True)
        try:
            script = 'import subprocess,sys; subprocess.Popen([sys.executable,"-c","import time; time.sleep(10)"])'
            code, proof = native_device.run_command([sys.executable, '-c', script], 5, self.log, shared.Cancellation())
            self.assertEqual(0, code)
            self.assertTrue(any(item['pid'] != proof['leader'] for item in proof['beforeKill']))
            self.assertTrue(all(item['state'].startswith('Z') for item in proof['afterKill']))
            self.assertIsNone(unrelated.poll())
        finally:
            unrelated.terminate()
            unrelated.wait(timeout=5)


if __name__ == '__main__':
    unittest.main()
