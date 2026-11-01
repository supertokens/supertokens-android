"""Host-only protocol adversaries; no adb, Gradle or backend access."""

import copy
import unittest
from unittest.mock import patch
import process_death as protocol


class Fake:
    def __init__(self):
        self.time = 0
        self.killed = False
        self.live = True
        self.value = dict(runId='run', scenario='refresh', stage='hold', phase=protocol.PHASE,
                          process='11111111-1111-1111-1111-111111111111', pid=123)
        self.saved = dict(runId='run', scenario='refresh', process=self.value['process'], phase='held', fixtureId='known')
        self.target_pids = [123]
        self.stop_effective = True
        self.output = ''

    def now(self): return self.time
    def pause(self): self.time += 1
    def running(self): return self.live
    def checkpoint(self): return copy.deepcopy(self.value)
    def receipt(self): return self.saved
    def pids(self): return self.target_pids
    def exit_code(self): return -9 if self.killed else None
    def hold_output(self): return self.output

    def force_stop(self):
        self.killed = True
        if self.stop_effective:
            self.target_pids = []
            self.live = False


class ProtocolTest(unittest.TestCase):
    def reject(self, fake):
        with self.assertRaises(RuntimeError):
            protocol.prove_kill(fake, 'run', 'refresh', 3)

    def test_checkpoint_target_kill_and_exit_are_required_together(self):
        fake = Fake()
        result = protocol.prove_kill(fake, 'run', 'refresh', 3)
        self.assertTrue(fake.killed)
        self.assertTrue(result['targetGone'])
        self.assertEqual('EXPECTED_DEATH_ONLY', result['result'])

    def test_arbitrary_crash_before_checkpoint_is_failure(self):
        fake = Fake()
        fake.live = False
        self.reject(fake)
        self.assertFalse(fake.killed)

    def test_missing_checkpoint_times_out_without_kill(self):
        fake = Fake()
        fake.value = None
        self.reject(fake)
        self.assertEqual(3, fake.time)
        self.assertFalse(fake.killed)

    def test_stale_or_malformed_checkpoint_never_authorizes_kill(self):
        for key, value in [('runId', 'old'), ('scenario', 'login'), ('stage', 'seed'), ('phase', 'after-adoption'),
                           ('pid', 0), ('pid', True), ('process', 'bad')]:
            with self.subTest(key=key, value=value):
                fake = Fake()
                fake.value[key] = value
                self.reject(fake)
                self.assertFalse(fake.killed)

    def test_receipt_mismatch_or_unknown_owner_never_authorizes_kill(self):
        for key, value in [('runId', 'old'), ('process', 'other'), ('phase', 'seeded'), ('fixtureId', ''), ('pendingRequestId', 'unknown')]:
            with self.subTest(key=key):
                fake = Fake()
                fake.saved[key] = value
                self.reject(fake)
                self.assertFalse(fake.killed)

    def test_pid_replacement_or_multiple_targets_rejected(self):
        for pids in [[], [124], [123, 124]]:
            fake = Fake()
            fake.target_pids = pids
            self.reject(fake)
            self.assertFalse(fake.killed)

    def test_exit_between_checkpoint_and_force_stop_rejected(self):
        fake = Fake()
        def receipt():
            fake.live = False
            return fake.saved
        fake.receipt = receipt
        self.reject(fake)
        self.assertFalse(fake.killed)

    def test_ineffective_force_stop_times_out(self):
        fake = Fake()
        fake.stop_effective = False
        self.reject(fake)
        self.assertTrue(fake.killed)
        self.assertEqual(3, fake.time)

    def test_missing_host_instrumentation_exit_is_failure(self):
        fake = Fake()
        def stop():
            fake.killed = True
            fake.target_pids = []
        fake.force_stop = stop
        self.reject(fake)

    def test_exact_normal_parser_rejects_death_and_wrong_tests(self):
        text = '\n'.join(['INSTRUMENTATION_STATUS: numtests=1',
                          'INSTRUMENTATION_STATUS: test=runStage',
                          'INSTRUMENTATION_STATUS: class=' + protocol.CLASS,
                          'INSTRUMENTATION_STATUS_CODE: 1', 'INSTRUMENTATION_STATUS_CODE: 0',
                          'OK (1 test)', 'INSTRUMENTATION_CODE: -1'])
        self.assertTrue(protocol.shared.exactly_one_pass(text, 'runStage'))
        for altered in [text + '\nProcess crashed', text.replace('numtests=1', 'numtests=2'),
                        text.replace(protocol.CLASS, 'Other'), 'Process crashed',
                        text.replace('STATUS_CODE: 0', 'STATUS_CODE: -2')]:
            self.assertFalse(protocol.shared.exactly_one_pass(altered, 'runStage'))

    def test_nonsecret_typed_receipt_parser(self):
        parsed = protocol.preferences('<map><string name="fixtureId">known</string><boolean name="cleanupDone" value="false"/></map>')
        self.assertEqual({'fixtureId': 'known', 'cleanupDone': 'false'}, parsed)

    def failure_in_stop_window(self, transcript):
        fake = Fake()
        original_stop = fake.force_stop
        def stop():
            # prove_kill has already accepted the checkpoint, receipt, PID and last
            # running() observation. Failure lands before the stop takes effect.
            fake.output = transcript
            fake.live = False
            original_stop()
        fake.force_stop = stop
        self.reject(fake)
        self.assertTrue(fake.killed)

    def test_junit_failure_after_last_liveness_probe_cannot_be_expected_death(self):
        for transcript in ['INSTRUMENTATION_STATUS_CODE: -1\n',
                           'INSTRUMENTATION_STATUS_CODE: -2\r\n', 'FAILURES!!!\n']:
            with self.subTest(transcript=transcript):
                self.failure_in_stop_window(transcript)

    def test_hold_watchdog_failure_in_stop_window_is_rejected(self):
        self.failure_in_stop_window('java.io.IOException: Hold failed\n'
                                    'Caused by: Hold watchdog expired; this is not a passing kill scenario\n'
                                    'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n')
        self.failure_in_stop_window('Hold watchdog expired; this is not a passing kill scenario\n')

    def test_successful_hold_completion_in_stop_window_is_rejected(self):
        for transcript in ['INSTRUMENTATION_STATUS_CODE: 0\n', 'OK (1 test)\n']:
            self.failure_in_stop_window(transcript)

    def test_legitimate_force_stop_process_crashed_transcript_remains_valid(self):
        fake = Fake()
        original_stop = fake.force_stop
        def stop():
            original_stop()
            fake.output = ('INSTRUMENTATION_STATUS_CODE: 1\n'
                           'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n'
                           'INSTRUMENTATION_CODE: 0\n')
        fake.force_stop = stop
        result = protocol.prove_kill(fake, 'run', 'refresh', 3)
        self.assertEqual('EXPECTED_DEATH_ONLY', result['result'])

    def test_target_only_force_stop_and_explicit_serial(self):
        from types import SimpleNamespace
        from pathlib import Path
        import tempfile
        with tempfile.TemporaryDirectory() as folder:
            runner = protocol.Runner(SimpleNamespace(serial='explicit-serial', output=Path(folder)))
            def bounded(argv, timeout, log, cancellation):
                self.assertEqual(['adb', '-s', 'explicit-serial', 'shell', 'am', 'force-stop', protocol.PACKAGE], argv)
                log.write_text('')
                return 0
            with patch.dict(protocol.os.environ, {'ADB': 'adb'}), patch.object(protocol.shared, 'bounded_command', bounded):
                runner.force_stop()


if __name__ == '__main__':
    unittest.main()
