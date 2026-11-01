#!/usr/bin/env python3
"""Run and archive twenty real Gradle stress invocations; reject missing/stale/incomplete evidence."""
import argparse
import json
import os
from pathlib import Path
import random
import re
import shutil
import signal
import subprocess
import xml.etree.ElementTree as ET


def verify(report, seed):
    root = ET.parse(report).getroot()
    cases = root.findall('testcase')
    expected = {'seededPublicOperationMix', 'publicRefreshCapacityIsReleasedAfterEightRetiredLeaders'}
    if {case.get('name') for case in cases} != expected or len(cases) != 2:
        raise ValueError('Wrong/missing stress methods')
    if any(next(root.iter(tag), None) is not None for tag in ('failure', 'error', 'skipped')):
        raise ValueError('Failed or skipped stress method')
    if any(int(root.get(key, '-1')) != value for key, value in [('tests', 2), ('failures', 0), ('errors', 0), ('skipped', 0)]):
        raise ValueError('Invalid JUnit totals')
    output = root.findtext('system-out', '')
    if re.findall(r'STRESS seed=(-?\d+) threads=(\d+) operationsPerThread=(\d+)', output) != [(str(seed), '16', '500')]:
        raise ValueError('Seed/operation settings were not executed')
    if re.findall(r'^STRESS violations=(.*)$', output, re.M) != ['[]']:
        raise ValueError('Missing/nonempty invariant ledger')
    return dict(seed=seed, threads=16, operationsPerThread=500, operations=8000, cleanLedger=True)


def run(command, log, timeout):
    with log.open('w') as stream:
        process = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            return process.wait(timeout=timeout)
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--seed-base', type=int, help='Optional reproducible consecutive seed range; default twenty random seeds')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    seeds = ([args.seed_base + index for index in range(20)] if args.seed_base is not None
             else random.SystemRandom().sample(range(1, 2147483647), 20))
    (args.output / 'seeds.json').write_text(json.dumps(seeds, indent=2) + '\n')
    results = []
    report = Path('app/build/test-results/testDebugUnitTest/TEST-com.supertokens.session.SessionStressTest.xml')
    def cancelled(number, frame):
        raise KeyboardInterrupt('Signal %d' % number)
    for number in (signal.SIGINT, signal.SIGTERM):
        signal.signal(number, cancelled)
    try:
        for seed in seeds:
            directory = args.output / str(seed)
            directory.mkdir()
            report.unlink(missing_ok=True)
            command = ['./gradlew', '--no-daemon', '--console=plain', '--rerun-tasks', ':app:testDebugUnitTest',
                       '--tests', 'com.supertokens.session.SessionStressTest', '-Psession.stress.seed=%d' % seed,
                       '-Psession.stress.threads=16', '-Psession.stress.operations=500', '-Psession.stress.watchdogSeconds=120']
            (directory / 'command.json').write_text(json.dumps(command, indent=2) + '\n')
            result = dict(seed=seed, success=False)
            try:
                code = run(command, directory / 'gradle.log', 900)
                result['exit'] = code
                if report.exists():
                    shutil.copy2(report, directory / report.name)
                if code != 0:
                    raise ValueError('Gradle exited %d' % code)
                result.update(verify(directory / report.name, seed))
                result['success'] = True
            except (OSError, ValueError, ET.ParseError, subprocess.TimeoutExpired) as error:
                result['error'] = str(error)
            finally:
                results.append(result)
                (args.output / 'summary.json').write_text(json.dumps(results, indent=2) + '\n')
    finally:
        print('Stress evidence:', args.output)
    if len(results) != 20 or not all(result['success'] for result in results):
        raise SystemExit('Twenty successful real stress executions required; inspect summary.json')


if __name__ == '__main__':
    main()
