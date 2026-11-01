#!/usr/bin/env python3
"""API34 candidate process death, using already-installed, hash-verified upgrade APKs."""

import argparse
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile
import upgrade

shared = upgrade.shared
PACKAGE = upgrade.PACKAGE
CLASS = PACKAGE + '.ProcessDeathTest'
shared.CLASS = CLASS
PHASE = 'network-response-before-adoption'


def validate_checkpoint(value, run, scenario):
    expected = dict(runId=run, scenario=scenario, stage='hold', phase=PHASE)
    if not isinstance(value, dict) or any(value.get(k) != v for k, v in expected.items()):
        raise RuntimeError('Wrong/stale checkpoint')
    if type(value.get('pid')) is not int or value['pid'] <= 0:
        raise RuntimeError('Invalid checkpoint PID')
    if not re.fullmatch(r'[0-9a-f-]{36}', value.get('process', '')):
        raise RuntimeError('Invalid process UUID')
    return value


def validate_hold_output(text):
    statuses = re.findall(r'^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$', text, re.M)
    if (any(int(status) <= 0 for status in statuses)
            or any(marker in text for marker in ['OK (1 test)', 'FAILURES!!!',
                'Hold watchdog expired', 'Hold failed', 'Held call unexpectedly returned'])):
        raise RuntimeError('Held instrumentation reported completion or a known hold failure')


def prove_kill(io, run, scenario, timeout):
    """A terminated instrumentation invocation is never itself a passing test."""
    deadline = io.now() + timeout
    checkpoint = None
    while io.now() < deadline:
        if not io.running():
            raise RuntimeError('Instrumentation ended before checkpoint/host kill')
        value = io.checkpoint()
        if value is not None:
            checkpoint = validate_checkpoint(value, run, scenario)
            break
        io.pause()
    if checkpoint is None:
        raise RuntimeError('Checkpoint deadline exceeded')
    receipt = io.receipt()
    if any(receipt.get(k) != checkpoint[k] for k in ('runId', 'scenario', 'process')) or receipt.get('phase') != 'held':
        raise RuntimeError('Held receipt does not match checkpoint')
    if not receipt.get('fixtureId') or 'pendingRequestId' in receipt:
        raise RuntimeError('Owner must be durably tracked before kill')
    if io.pids() != [checkpoint['pid']] or not io.running():
        raise RuntimeError('Checkpoint process is not the live target')
    io.force_stop()
    deadline = io.now() + timeout
    while io.now() < deadline:
        if not io.pids() and not io.running():
            # Read the final transcript after the owned adb child exits, including failures
            # emitted in the last-liveness-observation to force-stop window.
            validate_hold_output(io.hold_output())
            return dict(checkpoint=checkpoint, targetGone=True, hostForceStop=True,
                        instrumentationExit=io.exit_code(), result='EXPECTED_DEATH_ONLY')
        io.pause()
    raise RuntimeError('Target/owned instrumentation did not terminate after force-stop')


def preferences(text):
    root = ET.fromstring(text)
    if root.tag != 'map':
        raise RuntimeError('Invalid preferences XML')
    return {item.attrib['name']: item.text if item.tag == 'string' else item.attrib.get('value') for item in root}


class Runner:
    def __init__(self, args):
        self.args = args
        self.cancellation = shared.Cancellation()
        self.sequence = 0
        self.child = None
        self.stage = 'preflight'

    def command(self, arguments, label, acceptable=(0,), timeout=15):
        self.sequence += 1
        log = self.args.output / ('%03d-%s.txt' % (self.sequence, label))
        code = shared.bounded_command([os.environ.get('ADB', 'adb'), '-s', self.args.serial] + arguments,
                                     timeout, log, self.cancellation)
        if code not in acceptable:
            raise RuntimeError('ADB failed; see ' + str(log))
        return log.read_text(errors='replace')

    def now(self):
        self.cancellation.check()
        return time.monotonic()

    def pause(self):
        self.cancellation.check()
        time.sleep(.1)

    def running(self):
        return self.child.poll() is None

    def exit_code(self):
        return self.child.poll()

    def hold_output(self):
        return (self.args.output / (self.stage + '-instrument.txt')).read_text(errors='replace')

    def checkpoint(self):
        text = self.command(['exec-out', 'run-as', PACKAGE, 'cat', 'files/death-checkpoint.json'], 'checkpoint', (0, 1))
        if 'No such file' in text:
            return None
        return json.loads(text)

    def receipt(self):
        return preferences(self.command(['exec-out', 'run-as', PACKAGE, 'cat', 'shared_prefs/death-receipt.xml'], 'receipt'))

    def pids(self):
        text = self.command(['shell', 'pidof', PACKAGE], 'pids', (0, 1)).strip()
        if text and not re.fullmatch(r'\d+(?:\s+\d+)*', text):
            raise RuntimeError('Invalid target PID response')
        return [int(value) for value in text.split()]

    def force_stop(self):
        self.command(['shell', 'am', 'force-stop', PACKAGE], 'target-force-stop')

    def invocation(self, scenario, stage):
        return ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', CLASS + '#runStage',
                '-e', 'stage', stage, '-e', 'scenario', scenario, '-e', 'runId', self.args.run_id,
                '-e', 'backend', self.args.backend, upgrade.TEST_PACKAGE + '/androidx.test.runner.AndroidJUnitRunner']

    def capture(self, scenario, stage):
        for name in ['death-receipt', 'supertokens-android-shared-preferences']:
            text = self.command(['exec-out', 'run-as', PACKAGE, 'cat', 'shared_prefs/' + name + '.xml'],
                                scenario + '-' + stage + '-' + name)
            preferences(text)

    def normal(self, scenario, stage):
        self.stage = scenario + '-' + stage
        self.force_stop()
        if self.pids():
            raise RuntimeError('Target survived stage boundary force-stop')
        text = self.command(self.invocation(scenario, stage), self.stage, timeout=90)
        if not shared.exactly_one_pass(text, 'runStage'):
            raise RuntimeError('Expected exactly one normal passing stage: ' + self.stage)
        self.capture(scenario, stage)

    def held(self, scenario):
        self.stage = scenario + '-hold'
        self.force_stop()
        if self.pids():
            raise RuntimeError('Target survived pre-hold force-stop')
        with (self.args.output / (self.stage + '-instrument.txt')).open('w') as output:
            self.child = subprocess.Popen([os.environ.get('ADB', 'adb'), '-s', self.args.serial] + self.invocation(scenario, 'hold'),
                                          stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                proof = prove_kill(self, self.args.run_id, scenario, 30)
                (self.args.output / (scenario + '-kill-proof.json')).write_text(json.dumps(proof, indent=2))
                self.capture(scenario, 'killed')
            finally:
                if self.child.poll() is None:
                    # Reap only our host command. A failed protocol never becomes an expected device death.
                    try:
                        os.killpg(self.child.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    self.child.wait(timeout=5)

    def preflight(self):
        if self.command(['get-state'], 'state').strip() != 'device':
            raise RuntimeError('Explicit device not online')
        if self.command(['shell', 'getprop', 'ro.build.version.sdk'], 'api').strip() != '34':
            raise RuntimeError('Requires API34')
        self.command(['shell', 'getprop', 'ro.build.fingerprint'], 'fingerprint')
        with zipfile.ZipFile(self.args.app_apk) as apk:
            provenance = json.loads(apk.read('assets/sdk-provenance.json'))
        if provenance.get('sdk') != 'candidate' or provenance.get('applicationId') != PACKAGE:
            raise RuntimeError('Not a candidate upgrade APK')
        upgrade.validate_build_receipt(provenance['candidateBuild'], provenance['aarSha256'])
        pin = (upgrade.HELPERS.parent / 'app/src/test/compatibility/legacy054.sha256').read_text().strip()
        if provenance['aarSha256'] == pin:
            raise RuntimeError('Published artifact is not candidate')
        for package, apk in [(PACKAGE, self.args.app_apk), (upgrade.TEST_PACKAGE, self.args.test_apk)]:
            path = self.command(['shell', 'pm', 'path', package], 'installed-path').strip()
            if not re.fullmatch(r'package:/[A-Za-z0-9_./=+~-]+\.apk', path):
                raise RuntimeError('Expected single installed APK')
            result = self.command(['shell', 'sha256sum', path[len('package:'):]], 'installed-hash')
            if result.split()[0] != upgrade.sha256(apk):
                raise RuntimeError('Installed APK differs from supplied artifact')
        (self.args.output / 'artifacts.json').write_text(json.dumps(dict(provenance=provenance,
            appSha256=upgrade.sha256(self.args.app_apk), testSha256=upgrade.sha256(self.args.test_apk)), indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--backend', required=True)
    parser.add_argument('--app-apk', required=True, type=Path)
    parser.add_argument('--test-apk', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--run-id')
    parser.add_argument('--scenario', choices=['login', 'refresh', 'all'], default='all')
    parser.add_argument('--cleanup-only', action='store_true')
    args = parser.parse_args()
    if args.cleanup_only and (not args.run_id or args.scenario == 'all'):
        parser.error('Cleanup requires original run ID and one scenario')
    args.run_id = args.run_id or uuid.uuid4().hex
    if (not re.fullmatch(r'[A-Za-z0-9-]{1,80}', args.run_id) or not args.serial
            or not re.fullmatch(r'https?://[A-Za-z0-9.-]+(?::[0-9]+)?', args.backend)):
        parser.error('Invalid run ID, serial or backend origin')
    if args.output.exists():
        parser.error('Output must be new')
    os.umask(0o077)
    args.output.mkdir(parents=True)
    runner = Runner(args)
    for number in (signal.SIGINT, signal.SIGTERM):
        signal.signal(number, runner.cancellation.receive)
    (args.output / 'run.json').write_text(json.dumps(vars(args), default=str, indent=2))
    try:
        runner.preflight()
        for scenario in ['login', 'refresh'] if args.scenario == 'all' else [args.scenario]:
            if not args.cleanup_only:
                runner.normal(scenario, 'seed')
                runner.held(scenario)
                runner.normal(scenario, 'check')
            runner.normal(scenario, 'cleanup')
            with (args.output / 'summary.txt').open('a') as summary:
                summary.write(scenario + (' CLEANUP_PASS\n' if args.cleanup_only else ' DEATH_AND_RESTART_PASS\n'))
        return 0
    except shared.Cancelled as error:
        return error.code
    except (OSError, ValueError, RuntimeError, KeyError, ET.ParseError, subprocess.TimeoutExpired) as error:
        (args.output / 'failure.txt').write_text(runner.stage + ': ' + str(error))
        print('FAILED at ' + runner.stage + '; private evidence: ' + str(args.output))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
