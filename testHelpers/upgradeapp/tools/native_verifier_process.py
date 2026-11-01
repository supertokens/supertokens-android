"""Synchronous bridge with a retained, unreaped verifier process-group leader."""
import os
from pathlib import Path
import signal
import sys
import tempfile

from native_device import run_command


class Cancellation:
    def __init__(self):
        self.parent = os.getppid()
        self.signal = 0

    @property
    def number(self):
        return self.signal or (signal.SIGTERM if self.parent == 1 or os.getppid() != self.parent else 0)

    def check(self):
        if self.number:
            raise RuntimeError('Verifier cancelled')

    def cancel(self, number, frame):
        self.signal = number


def supervise(argv, timeout=60):
    cancellation = Cancellation()
    for number in [signal.SIGTERM, signal.SIGINT, signal.SIGHUP]:
        signal.signal(number, cancellation.cancel)
    with tempfile.TemporaryDirectory(prefix='native-verifier-') as directory:
        log = Path(directory) / 'result'
        code, proof = run_command(argv, timeout, log, cancellation)
        if log.stat().st_size > 1024 * 1024:
            raise RuntimeError('Verifier output exceeds bridge limit')
        sys.stdout.buffer.write(log.read_bytes())
        return code


if __name__ == '__main__':
    sys.exit(supervise([sys.executable, str(Path(__file__).with_name('native_archive.py')), sys.argv[1]]))
