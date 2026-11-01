#!/usr/bin/env python3
"""Explicit restart pairs with bounded, directly owned host adb commands."""

import argparse
import math
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


PACKAGE = 'com.supertokens.session.test'
RUNNER = PACKAGE + '/androidx.test.runner.AndroidJUnitRunner'
CLASS = 'com.supertokens.session.SessionStorageRestartTest'
PAIRS = [
    ('available', 'testSeedAvailable', 'testCheckAvailableAfterProcessRestart'),
    ('cleared', 'testSeedCleared', 'testCheckClearedAfterProcessRestart'),
    ('expired-v2', 'testSeedExpiredV2', 'testCheckExpiredV2AfterProcessRestart'),
    ('cookie-front-only', 'testSeedCookieFrontOnly', 'testCheckCookieFrontOnlyAfterProcessRestart'),
]


class Cancelled(Exception):
    def __init__(self, number):
        self.code = 128 + number


class Cancellation:
    def __init__(self):
        self.number = None

    def receive(self, number, frame):
        self.number = self.number or number

    def check(self):
        if self.number:
            raise Cancelled(self.number)


def seconds(value):
    parsed = float(value)
    if not math.isfinite(parsed) or parsed <= 0:
        raise argparse.ArgumentTypeError('deadline must be positive and finite')
    return parsed


def bounded_command(command, timeout, log, cancellation):
    cancellation.check()
    with log.open('w') as output:
        child = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT,
                                 start_new_session=True)
        deadline = time.monotonic() + timeout
        while not cancellation.number and time.monotonic() < deadline:
            try:
                result = child.wait(timeout=min(0.1, max(0.001, deadline - time.monotonic())))
            except subprocess.TimeoutExpired:
                continue
            # This child is reaped. Cancellation after wait must never signal its
            # former PID/group, even if the signal arrived before wait returned.
            cancellation.check()
            return result

        reason = 'interrupted' if cancellation.number else 'timed out after %g seconds' % timeout
        print('command-deadline: %s; terminating owned command group %s: %s' %
              (reason, child.pid, command[0]), file=output, flush=True)
        # Only failed/timed-out waits reach here. The unreaped leader pins its
        # PID/group identity, including when it exits just before killpg.
        try:
            os.killpg(child.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        try:
            child.wait(timeout=2)
        except subprocess.TimeoutExpired:
            print('command-deadline: command did not reap after SIGKILL', file=output)
        cancellation.check()
        return 124


def exactly_one_pass(text, method):
    lines = text.replace('\r', '').splitlines()
    expected = {
        'INSTRUMENTATION_STATUS: numtests=': '1',
        'INSTRUMENTATION_STATUS: test=': method,
        'INSTRUMENTATION_STATUS: class=': CLASS,
    }
    for prefix, value in expected.items():
        values = [line[len(prefix):] for line in lines if line.startswith(prefix)]
        if not values or any(actual != value for actual in values):
            return False
    statuses = [line for line in lines if line.startswith('INSTRUMENTATION_STATUS_CODE:')]
    finished = [line for line in lines if line.startswith('INSTRUMENTATION_CODE:')]
    return (statuses == ['INSTRUMENTATION_STATUS_CODE: 1', 'INSTRUMENTATION_STATUS_CODE: 0']
            and finished == ['INSTRUMENTATION_CODE: -1']
            and lines.count('OK (1 test)') == 1
            and not any(marker in text for marker in [
                'FAILURES!!!', 'INSTRUMENTATION_FAILED', 'INSTRUMENTATION_ABORTED',
                'Process crashed', 'shortMsg=']))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--pair', choices=['all'] + [pair[0] for pair in PAIRS], default='all')
    parser.add_argument('--warmed-cache', action='store_true')
    parser.add_argument('--command-timeout', type=seconds, default=30)
    parser.add_argument('--install-timeout', type=seconds, default=120)
    parser.add_argument('--test-timeout', type=seconds, default=120)
    args = parser.parse_args()
    if not args.serial or (args.apk and not args.apk.is_file()):
        parser.error('serial must be nonempty and any supplied APK must exist')
    if args.output.exists():
        parser.error('output directory must not exist: ' + str(args.output))
    args.output.mkdir(parents=True)
    cancellation = Cancellation()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, cancellation.receive)
    stage = 'preflight'
    status = 1

    def adb(arguments, filename, timeout, expected=None):
        log = args.output / filename
        command = [os.environ.get('ADB', 'adb'), '-s', args.serial] + arguments
        result = bounded_command(command, timeout, log, cancellation)
        text = log.read_text(errors='replace')
        if result != 0 or (expected and expected not in text.splitlines()):
            print(text, file=sys.stderr)
            raise RuntimeError('adb command failed (exit %s); see %s' % (result, log))
        return text

    def method(name):
        nonlocal stage
        stage = name
        print('Running %s on %s' % (name, args.serial), flush=True)
        text = adb(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', CLASS + '#' + name,
                    RUNNER], name + '.txt', args.test_timeout)
        if not exactly_one_pass(text, name):
            print(text, file=sys.stderr)
            raise RuntimeError('Expected exactly one successful %s; see %s' % (name, args.output / (name + '.txt')))

    try:
        (args.output / 'run.txt').write_text(
            'serial=%s\npair=%s\nwarmed-cache=%s\napk=%s\ncommand-timeout=%s\ninstall-timeout=%s\ntest-timeout=%s\n' %
            (args.serial, args.pair, args.warmed_cache, args.apk or 'already-installed',
             args.command_timeout, args.install_timeout, args.test_timeout))
        adb(['get-state'], 'device-state.txt', args.command_timeout, 'device')
        adb(['shell', 'getprop', 'ro.build.version.sdk'], 'api.txt', args.command_timeout)
        adb(['shell', 'getprop', 'ro.build.fingerprint'], 'fingerprint.txt', args.command_timeout)
        if args.apk:
            stage = 'install'
            adb(['install', '-r', str(args.apk)], 'install.txt', args.install_timeout, 'Success')
        for name, seed, check in PAIRS:
            if args.pair not in ('all', name):
                continue
            method(seed)
            stage = 'force-stop-' + name
            adb(['shell', 'am', 'force-stop', PACKAGE], stage + '.txt', args.command_timeout)
            # The check verifies a different process UUID from the seed receipt.
            method(check)
            with (args.output / 'summary.txt').open('a') as summary:
                summary.write(name + ' PASS\n')
        if args.warmed_cache:
            method('testRealPreferencesWarmedCacheAndConcurrentSnapshots')
            with (args.output / 'summary.txt').open('a') as summary:
                summary.write('warmed-cache PASS\n')
        cancellation.check()
        print((args.output / 'summary.txt').read_text(), end='')
        print('Results: ' + str(args.output))
        status = 0
    except Cancelled as error:
        status = error.code
    except (OSError, RuntimeError) as error:
        print('restart-pairs: ' + str(error), file=sys.stderr)
    finally:
        if status:
            print('restart-pairs: stopped at %s (exit %s). Logs: %s' %
                  (stage, status, args.output), file=sys.stderr)
            print('Device/app state preserved; no cleanup, uninstall, or global process kill performed.',
                  file=sys.stderr)
    return status


if __name__ == '__main__':
    sys.exit(main())
