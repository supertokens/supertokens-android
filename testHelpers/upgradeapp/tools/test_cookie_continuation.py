"""Fake-tool protocol checks only; these cannot qualify Android cookie behavior."""
import json
import subprocess
import unittest
import test_upgrade


class CookieContinuation(unittest.TestCase):
    setUp = test_upgrade.UpgradeProtocol.setUp
    run_case = test_upgrade.UpgradeProtocol.run_case

    def verify_continuation(self, scenario, labels):
        result = self.run_case(extra=['--scenario', scenario])
        self.assertEqual(0, result.returncode, result.stderr)
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
        stages = [call for call in calls if 'instrument' in call]
        self.assertEqual(['seed', 'check-initial', 'cleanup'],
                         [call[call.index('stage') + 1] for call in stages])
        self.assertEqual(['sdk054', 'candidate', 'candidate'],
                         [call[call.index('expectedFlavor') + 1] for call in stages])
        self.assertEqual(4, sum('install' in call for call in calls))
        captures = json.loads((self.root / 'results/captures.json').read_text())
        self.assertEqual(3, sum('supertokens-cookie-shared-prefs' in item['file'] for item in captures))
        requests = json.loads((self.root / 'results/recovery.json').read_text())['requests']
        self.assertEqual(labels, [item['label'] for item in requests])

    def test_cookie_refresh_continuation(self):
        self.verify_continuation('cookie-refresh', ['A'])

    def test_cookie_signout_continuation(self):
        self.verify_continuation('cookie-signout', ['A', 'B'])

    def verify_failed_scenario_recovery(self, recovery_mode):
        result = self.run_case(mode='method', extra=['--scenario', 'cookie-signout'])
        self.assertNotEqual(0, result.returncode)
        failure_path = self.root / 'results/failure.json'
        original = failure_path.read_bytes()
        failure = json.loads(original)
        self.assertEqual('cookie-signout-seed', failure['stage'])
        self.assertIn('Expected exactly one passing stage', failure['error'])
        command = failure['recoveryCommand']
        self.assertIn('--cleanup-only', command)
        self.assertEqual(failure['runId'], command[command.index('--run-id') + 1])
        self.assertEqual('cookie-signout', command[command.index('--scenario') + 1])
        self.assertIn('original run remains FAILED', result.stderr)
        self.calls.write_text('')
        recovery = subprocess.run(command, env=dict(self.env, MODE=recovery_mode),
                                  capture_output=True, text=True, timeout=20)
        self.assertEqual(original, failure_path.read_bytes())
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
        stages = [call for call in calls if 'instrument' in call]
        self.assertEqual(['cleanup'], [call[call.index('stage') + 1] for call in stages])
        self.assertFalse((self.root / 'results/summary.txt').exists())
        return recovery

    def test_recovery_success_does_not_reclassify_failed_scenario(self):
        self.assertEqual(0, self.verify_failed_scenario_recovery('').returncode)

    def test_recovery_failure_does_not_replace_original_failure(self):
        self.assertNotEqual(0, self.verify_failed_scenario_recovery('method').returncode)


if __name__ == '__main__':
    unittest.main()
