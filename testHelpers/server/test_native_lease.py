"""Host-only cleanup contract tests against the frozen producer and fake device tools."""
import json
from pathlib import Path
import unittest
import nativeLease
import test_matrix_native


class LeaseCleanup(unittest.TestCase):
    setUp = test_matrix_native.RunnerControls.setUp
    run_case = test_matrix_native.RunnerControls.run_case
    prepare_matrix = test_matrix_native.RunnerControls.prepare_matrix

    def prepare(self):
        self.prepare_matrix()
        self.preflight, self.closed = self.root / 'preflight', self.root / 'closed'
        result = self.run_case(extra=['--preflight-only', '--output', str(self.preflight)])
        self.assertEqual(result.returncode, 0, result.stderr)
        result = self.run_case(extra=['--device-lease-session', str(self.preflight), '--release-lease-only', '--output', str(self.closed)])
        self.assertEqual(result.returncode, 0, result.stderr)
        pre = json.loads((self.preflight / 'native-preflight.json').read_text())
        close = json.loads((self.closed / 'native-lease-closed.json').read_text())
        commands = [json.loads(line) for line in (self.preflight / 'native-commands.jsonl').read_text().splitlines()]
        adb = next(row['argv'][0] for row in commands if row['output'] == 'native-routing-before.txt')
        return dict(binding=self.binding, preparationNonce=self.binding['preparationNonce'], startedMs=pre['startedMs'],
                    jvmStartedMs=pre['endedMs'], jvmEndedMs=close['startedMs'], endedMs=close['endedMs'],
                    nativePreflight=str(self.preflight), nativeLeaseClose=str(self.closed), nativeSerial='fake-serial',
                    nativeLeaseRoot=str(self.root / 'leases'), nativeTools={**self.binding['toolchain'], 'adb': {'path': adb}},
                    nativeBuild=self.native_build_pin)

    def test_no_native_rows_still_requires_real_producer_close(self):
        expected = self.prepare()
        nativeLease.verify(expected)
        (self.closed / 'native-lease-closed.json').unlink()
        with self.assertRaises(Exception):
            nativeLease.verify(expected)

    def test_close_cannot_precede_jvm_completion(self):
        expected = self.prepare()
        expected['jvmEndedMs'] = expected['endedMs'] + 1
        with self.assertRaisesRegex(ValueError, 'enclose JVM'):
            nativeLease.verify(expected)

    def test_reappearing_device_lease_blocks_cleanup(self):
        expected = self.prepare()
        lease = Path(expected['nativeLeaseRoot']) / nativeLease.archive.sha(expected['nativeSerial'].encode())
        lease.mkdir()
        with self.assertRaisesRegex(ValueError, 'still held'):
            nativeLease.verify(expected)

    def rewrite_close(self, filename, value):
        (self.closed / filename).write_text(value)
        summary = self.closed / 'native-lease-closed.json'
        receipt = json.loads(summary.read_text())
        receipt['files'][filename] = nativeLease.archive.sha(value.encode())
        summary.write_text(json.dumps(receipt))

    def test_rehashed_foreign_borrower_cannot_close_selection(self):
        expected = self.prepare()
        borrower = json.loads((self.closed / 'device-borrower.json').read_text())
        borrower['nativeNonce'] = '0' * 48
        self.rewrite_close('device-borrower.json', json.dumps(borrower))
        with self.assertRaisesRegex(ValueError, 'borrower mismatch'):
            nativeLease.verify(expected)

    def test_rehashed_routing_receipt_still_requires_restoration(self):
        expected = self.prepare()
        name, value = 'native-routing-after.txt', 'fake-serial tcp:18081 tcp:19999\n'
        self.rewrite_close(name, value)
        commands = [json.loads(line) for line in (self.closed / 'native-commands.jsonl').read_text().splitlines()]
        for row in commands:
            if row['output'] == name:
                row['outputSha256'] = nativeLease.archive.sha(value.encode())
        self.rewrite_close('native-commands.jsonl', ''.join(json.dumps(row) + '\n' for row in commands))
        with self.assertRaisesRegex(ValueError, 'Routing not restored'):
            nativeLease.verify(expected)

    def test_no_native_rows_cannot_use_changed_external_build_receipt(self):
        expected = self.prepare()
        receipt = Path(expected['nativeBuild']['path'])
        receipt.write_bytes(receipt.read_bytes() + b'\n')
        with self.assertRaisesRegex(ValueError, 'Trusted native build receipt changed'):
            nativeLease.verify(expected)

    def test_no_native_rows_cannot_use_changed_compiled_output(self):
        expected = self.prepare()
        output = Path(expected['nativeBuild']['path']).parent / 'candidate-test.apk'
        output.write_bytes(output.read_bytes() + b'changed output')
        with self.assertRaisesRegex(ValueError, 'APK differs from trusted compilation output'):
            nativeLease.verify(expected)


if __name__ == '__main__':
    unittest.main()
