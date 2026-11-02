import subprocess
import sys
import unittest
import json
from pathlib import Path


class BundleIdentifierTest(unittest.TestCase):
    def test_rejects_paths_before_reading_private_evidence(self):
        script = Path(__file__).with_name('sanitize_captures.py')
        for identifier in ['/private/captures', '../captures', 'bundle name']:
            with self.subTest(identifier=identifier):
                result = subprocess.run(
                    [sys.executable, str(script), '--source', '/nonexistent', '--bundle-id', identifier],
                    capture_output=True, text=True,
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Expected a bundle identifier, not a path', result.stderr)
                self.assertNotIn('FileNotFoundError', result.stderr)

    def test_accepts_bundle_identifier_before_reading_evidence(self):
        script = Path(__file__).with_name('sanitize_captures.py')
        result = subprocess.run(
            [sys.executable, str(script), '--source', '/nonexistent', '--bundle-id', 'capture-2026_01'],
            capture_output=True, text=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('FileNotFoundError', result.stderr)
        self.assertNotIn('Expected a bundle identifier', result.stderr)

    def test_historical_provenance_retains_original_transform_digest(self):
        manifest = Path(__file__).resolve().parents[2] / 'resources/upgrade/capture-provenance.json'
        provenance = json.loads(manifest.read_text())
        self.assertEqual(provenance['sourceEvidenceRoot'],
                         'historical external evidence bundle: upgrade-activation-5oxbst6c')
        self.assertEqual(provenance['transformSha256'],
                         'e06afad8242d11fcac7116f2066b7f79b4e6b1d623dc325485a15543583f038c')


if __name__ == '__main__':
    unittest.main()
