#!/bin/bash
set -u
diagnostic="$(cd "$(dirname "$0")" && pwd)/matrixDiagnostics.js"
collect() {
    node "$diagnostic" "$@" >/dev/null 2>&1 || :
}
finish() {
    status=$?
    trap - EXIT
    collect after "$status"
    exit "$status"
}
trap finish EXIT
collect before
"$@"
