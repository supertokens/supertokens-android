"""Parser controls only; these deliberately fabricated XMLs are not stress executions."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('nightly', Path(__file__).with_name('nightly-stress.py'))
nightly = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nightly)


class EvidenceTest(unittest.TestCase):
    def test_rejects_incomplete_or_wrong_execution(self):
        text = '''<testsuite tests="2" failures="0" errors="0" skipped="0">
        <testcase name="seededPublicOperationMix"/><testcase name="publicRefreshCapacityIsReleasedAfterEightRetiredLeaders"/>
        <system-out>STRESS seed=123 threads=16 operationsPerThread=500
STRESS violations=[]</system-out></testsuite>'''
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'report.xml'
            path.write_text(text)
            self.assertEqual(8000, nightly.verify(path, 123)['operations'])
            for broken in [text.replace('seed=123', 'seed=124'), text.replace('operationsPerThread=500', 'operationsPerThread=40'),
                           text.replace('violations=[]', 'violations=[unexpected]'), text.replace('STRESS violations=[]', ''),
                           text.replace('tests="2"', 'tests="0"'), text.replace('skipped="0"', 'skipped="1"'),
                           text.replace('seededPublicOperationMix"/>', 'seededPublicOperationMix"><failure/></testcase>'),
                           text.replace('publicRefreshCapacityIsReleasedAfterEightRetiredLeaders', 'wrongMethod')]:
                path.write_text(broken)
                with self.assertRaises(ValueError):
                    nightly.verify(path, 123)


if __name__ == '__main__':
    unittest.main()
