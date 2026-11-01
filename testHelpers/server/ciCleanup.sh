#!/bin/bash

cleanup_test_backend() {
    local original_status=$?
    local cleanup_failed=0
    trap - EXIT
    set +e
    if ! cd "$server_directory"; then
        echo "Cannot enter backend cleanup directory" >&2
        cleanup_failed=1
    fi
    # The endpoint checks the launched Express PID before stopping only its recorded Core child.
    if [ "$cleanup_failed" -eq 0 ] && { ! jobs -pr | grep -qx "$pid" || ! curl --noproxy '*' --fail --silent --show-error --max-time 12 \
        -H 'Content-Type: application/json' -d "{\"serverPid\":$pid}" \
        http://127.0.0.1:8080/test/shutdown-core >/dev/null; }; then
        if ! node ./shutdownOwnedCore.js "$pid"; then
            echo "Owned Core fallback cleanup failed" >&2
            cleanup_failed=1
        fi
    fi
    if jobs -pr | grep -qx "$pid"; then
        kill -TERM "$pid" 2>/dev/null
        for attempt in {1..50}; do
            jobs -pr | grep -qx "$pid" || break
            sleep 0.1
        done
        if jobs -pr | grep -qx "$pid"; then kill -KILL "$pid" 2>/dev/null; fi
    fi
    wait "$pid" 2>/dev/null
    if [ "$original_status" -eq 0 ] && [ "$cleanup_failed" -ne 0 ]; then exit 1; fi
    exit "$original_status"
}
