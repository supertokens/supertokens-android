#!/bin/sh
set -eu
directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
python=${PYTHON:-python3}
command -v "$python" >/dev/null 2>&1 || { echo 'Python 3 is required (set PYTHON if needed)' >&2; exit 1; }
exec "$python" "$directory/restart-pairs.py" "$@"
