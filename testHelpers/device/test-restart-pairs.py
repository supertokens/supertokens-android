#!/usr/bin/env python3
"""Deterministic host-only checks; never calls a real adb executable."""

import json
import os
import signal
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import types
import unittest
from unittest import mock


implementation = Path(__file__).with_name('restart-pairs.py')
runner_module = types.ModuleType('restart_pairs')
exec(compile(implementation.read_text(), str(implementation), 'exec'), runner_module.__dict__)


FAKE_ADB = r'''
import json, os, signal, subprocess, sys, time
args = sys.argv[1:]
assert args[:2] == ['-s', 'fake-device']
args = args[2:]
with open(os.environ['CALLS'], 'a') as log:
    log.write(json.dumps(args) + '\n')
stage = ('preflight' if args == ['get-state'] else
         'property' if args[:2] == ['shell', 'getprop'] else
         'install' if args[:2] == ['install', '-r'] else
         'force-stop' if args[:3] == ['shell', 'am', 'force-stop'] else 'instrument')
if os.environ['HANG'] == stage:
    grandchild = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
    with open(os.environ['PIDS'], 'w') as f:
        f.write('%s %s %s' % (os.getpid(), grandchild.pid, os.getppid()))
    print('partial output before hang', flush=True)
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    time.sleep(60)
if stage == 'preflight': print('device')
elif stage == 'property': print('34')
elif stage == 'install': print('Success')
elif stage == 'instrument':
    clazz, method = args[args.index('class') + 1].split('#')
    for code in [1, 0]:
        print('INSTRUMENTATION_STATUS: class=' + clazz)
        print('INSTRUMENTATION_STATUS: test=' + method)
        print('INSTRUMENTATION_STATUS: numtests=1')
        print('INSTRUMENTATION_STATUS_CODE: ' + str(code))
    print('OK (1 test)\nINSTRUMENTATION_CODE: -1')
'''


class DeadlineTest(unittest.TestCase):
    def test_cancellation_after_reap_never_signals_old_identity(self):
        original_popen = subprocess.Popen
        for sig in [signal.SIGTERM, signal.SIGINT]:
            with self.subTest(signal=sig), tempfile.TemporaryDirectory(prefix='restart-reaped-') as temporary:
                cancellation = runner_module.Cancellation()
                previous = signal.signal(sig, cancellation.receive)
                children = []

                def spawn(*args, **kwargs):
                    child = original_popen(*args, **kwargs)
                    children.append(child)
                    real_wait = child.wait

                    def wait_then_signal(*args, **kwargs):
                        result = real_wait(*args, **kwargs)
                        self.assertIsNotNone(child.returncode)
                        # Deliver the signal precisely after reap, before the
                        # runner processes wait's successful return value.
                        os.kill(os.getpid(), sig)
                        return result

                    child.wait = wait_then_signal
                    return child

                try:
                    with mock.patch.object(runner_module.subprocess, 'Popen', side_effect=spawn) as popen, \
                            mock.patch.object(runner_module.os, 'killpg') as killpg:
                        with self.assertRaises(runner_module.Cancelled):
                            runner_module.bounded_command([sys.executable, '-c', 'pass'], 5,
                                                          Path(temporary) / 'completed.txt', cancellation)
                        self.assertEqual(children[0].returncode, 0)
                        killpg.assert_not_called()
                        with self.assertRaises(runner_module.Cancelled):
                            runner_module.bounded_command([sys.executable, '-c', 'pass'], 5,
                                                          Path(temporary) / 'next.txt', cancellation)
                        self.assertEqual(popen.call_count, 1, 'Cancellation must prevent another spawn')
                        killpg.assert_not_called()
                finally:
                    signal.signal(sig, previous)

    def test_exact_instrumentation_protocol(self):
        name = 'testSeedAvailable'
        valid = '\n'.join([
            'INSTRUMENTATION_STATUS: class=' + runner_module.CLASS,
            'INSTRUMENTATION_STATUS: test=' + name,
            'INSTRUMENTATION_STATUS: numtests=1',
            'INSTRUMENTATION_STATUS_CODE: 1',
            'INSTRUMENTATION_STATUS_CODE: 0',
            'OK (1 test)', 'INSTRUMENTATION_CODE: -1'])
        self.assertTrue(runner_module.exactly_one_pass(valid, name))
        self.assertTrue(runner_module.exactly_one_pass(valid.replace('\n', '\r\n'), name))
        rejected = [
            valid.replace('numtests=1', 'numtests=0'),
            valid.replace(name, 'wrongMethod'),
            valid.replace(runner_module.CLASS, 'wrong.Class'),
            valid.replace('STATUS_CODE: 0', 'STATUS_CODE: -3'),
            valid.replace('OK (1 test)', 'OK (0 tests)'),
            valid + '\nOK (1 test)',
            valid.replace('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_CODE: 0'),
            valid + '\nINSTRUMENTATION_RESULT: shortMsg=Process crashed',
            valid + '\nFAILURES!!!',
            valid.replace('INSTRUMENTATION_STATUS_CODE: 1\n', ''),
        ]
        for text in rejected:
            self.assertFalse(runner_module.exactly_one_pass(text, name), text)

    def test_runner_only_signals_cancel_long_deadline(self):
        runner = Path(__file__).with_name('restart-pairs.sh')
        with tempfile.TemporaryDirectory(prefix='restart-cancellation-') as temporary:
            root = Path(temporary)
            fake = root / 'adb'
            fake.write_text('#!' + sys.executable + '\n' + FAKE_ADB)
            fake.chmod(0o755)
            unrelated = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'],
                                         start_new_session=True)
            try:
                for sig in [signal.SIGTERM, signal.SIGINT]:
                    with self.subTest(signal=sig):
                        output = root / str(sig)
                        pids = root / (str(sig) + '.pids')
                        calls = root / (str(sig) + '.calls')
                        env = dict(os.environ, ADB=str(fake), PYTHON=sys.executable,
                                   HANG='preflight', PIDS=str(pids), CALLS=str(calls))
                        process = subprocess.Popen([
                            'sh', str(runner), '--serial', 'fake-device', '--output', str(output),
                            '--command-timeout', '60'], env=env, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True, start_new_session=True)
                        try:
                            deadline = time.monotonic() + 5
                            while (not pids.exists() or not pids.read_text().strip()) and time.monotonic() < deadline:
                                time.sleep(0.02)
                            self.assertTrue(pids.exists(), 'Fake adb did not start')
                            started = time.monotonic()
                            os.kill(process.pid, sig)
                            stdout, stderr = process.communicate(timeout=5)
                            self.assertLess(time.monotonic() - started, 4)
                            self.assertEqual(process.returncode, 128 + sig, stdout + stderr)
                            self.assertIn('Device/app state preserved', stderr)
                            log = (output / 'device-state.txt').read_text()
                            self.assertIn('partial output before hang', log)
                            self.assertIn('interrupted', log)
                            self.assertIsNone(unrelated.poll())
                            for pid in pids.read_text().split():
                                status = subprocess.run(['ps', '-o', 'stat=', '-p', pid],
                                                        capture_output=True, text=True, timeout=2)
                                self.assertTrue(not status.stdout.strip() or
                                                status.stdout.strip().startswith('Z'), status.stdout)
                            self.assertEqual(len(calls.read_text().splitlines()), 1)
                        finally:
                            if process.poll() is None:
                                process.kill()
                                process.communicate(timeout=3)
            finally:
                unrelated.terminate()
                unrelated.wait(timeout=3)

    def test_success_and_bounded_failures(self):
        runner = Path(__file__).with_name('restart-pairs.sh')
        with tempfile.TemporaryDirectory(prefix='restart-deadlines-') as temporary:
            root = Path(temporary)
            fake = root / 'adb'
            fake.write_text('#!' + sys.executable + '\n' + FAKE_ADB)
            fake.chmod(0o755)
            apk = root / 'test.apk'
            apk.write_bytes(b'fake')
            unrelated = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'],
                                         start_new_session=True)
            try:
                for stage in ['none', 'preflight', 'property', 'install', 'instrument', 'force-stop']:
                    with self.subTest(stage=stage):
                        output = root / stage
                        pids = root / (stage + '.pids')
                        calls = root / (stage + '.calls')
                        env = dict(os.environ, ADB=str(fake), PYTHON=sys.executable,
                                   HANG=stage, PIDS=str(pids), CALLS=str(calls))
                        started = time.monotonic()
                        selection = ['--pair', 'available'] if stage != 'none' else ['--warmed-cache']
                        result = subprocess.run([
                            'sh', str(runner), '--serial', 'fake-device', '--output', str(output),
                            '--apk', str(apk), '--command-timeout', '0.5',
                            '--install-timeout', '0.5', '--test-timeout', '0.5'] + selection,
                            env=env, capture_output=True, text=True, timeout=8)
                        self.assertLess(time.monotonic() - started, 6)
                        self.assertIsNone(unrelated.poll(), 'Unrelated process was terminated')
                        commands = [json.loads(line) for line in calls.read_text().splitlines()]
                        self.assertFalse(any('kill-server' in c or 'uninstall' in c or 'clear' in c
                                             for c in commands))
                        if stage == 'none':
                            self.assertEqual(result.returncode, 0, result.stderr)
                            self.assertEqual((output / 'summary.txt').read_text(),
                                             'available PASS\ncleared PASS\nexpired-v2 PASS\ncookie-front-only PASS\nwarmed-cache PASS\n')
                            self.assertEqual(sum(c[:2] == ['install', '-r'] for c in commands), 1)
                            actions = [c for c in commands if c[:2] == ['shell', 'am']]
                            self.assertEqual(len(actions), 13)
                            for index, (_, seed, check) in enumerate(runner_module.PAIRS):
                                self.assertIn('#' + seed, actions[index * 3][-2])
                                self.assertEqual(actions[index * 3 + 1],
                                                 ['shell', 'am', 'force-stop', runner_module.PACKAGE])
                                self.assertIn('#' + check, actions[index * 3 + 2][-2])
                            continue
                        self.assertNotEqual(result.returncode, 0)
                        self.assertIn('Device/app state preserved', result.stderr)
                        logs = '\n'.join(p.read_text() for p in output.glob('*.txt'))
                        self.assertIn('partial output before hang', logs)
                        self.assertIn('timed out after 0.5 seconds', logs)
                        for pid in pids.read_text().split():
                            # An orphan may briefly remain a zombie until init reaps it.
                            status = subprocess.run(['ps', '-o', 'stat=', '-p', pid],
                                                    capture_output=True, text=True, timeout=2)
                            self.assertTrue(not status.stdout.strip() or
                                            status.stdout.strip().startswith('Z'), status.stdout)
                        self.assertFalse((output / 'summary.txt').exists())
            finally:
                unrelated.terminate()
                unrelated.wait(timeout=3)


if __name__ == '__main__':
    unittest.main()
