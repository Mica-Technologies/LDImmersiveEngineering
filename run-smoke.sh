#!/usr/bin/env bash
#
# Boots a dev server, waits for it to finish loading, and reports what went wrong.
#
# Why this exists: `./build.sh runServer` never returns on its own, and under ForgeGradle
# log4j cannot build Forge's Console appender in dev -- from that point on everything,
# including "Done (Xs)!", goes only to run/logs/latest.log while Gradle's stdout falls
# silent. A server that started perfectly looks identical to a hang. So this scans the log
# file rather than the console, and kills the server once it has an answer.
#
# It catches exactly the class of breakage unit tests cannot: registration errors, missing
# blockstate/model/recipe JSON, duplicate tile entity ids, malformed lang files.
#
# Usage: ./run-smoke.sh [timeout seconds, default 300]
#
# Exit 0 = the server reached "Done"; anything else is a failure and the reason is printed.
set -uo pipefail

cd "$(dirname "$0")"

TIMEOUT="${1:-300}"
LOG="run/logs/latest.log"
GRADLE_LOG="server-smoke.log"

echo "== server smoke test =="

# A stale log would let a previous run's "Done" satisfy this one.
mkdir -p run/logs
rm -f "$LOG"

./build.sh runServer >"$GRADLE_LOG" 2>&1 &
GRADLE_PID=$!

cleanup() {
    # Kill the whole tree: build.sh spawns gradle, which spawns the server JVM, and only
    # the leaf actually holds the world's session.lock.
    if command -v taskkill >/dev/null 2>&1; then
        taskkill //PID "$GRADLE_PID" //T //F >/dev/null 2>&1 || true
    fi
    kill -9 "$GRADLE_PID" >/dev/null 2>&1 || true
    wait "$GRADLE_PID" 2>/dev/null || true
}
trap cleanup EXIT

deadline=$((SECONDS+TIMEOUT))
result=""
while [[ $SECONDS -lt $deadline ]]; do
    if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
        result="exited"
        break
    fi
    if [[ -f "$LOG" ]]; then
        if grep -q 'Done (.*)! For help' "$LOG"; then
            result="done"
            break
        fi
        if grep -qE 'Unable to launch|A fatal error|The server has crashed' "$LOG"; then
            result="crash"
            break
        fi
    fi
    sleep 2
done

echo
case "$result" in
    done)
        echo "SERVER STARTED"
        ;;
    crash)
        echo "SERVER CRASHED -- last 40 lines of $LOG:"
        tail -40 "$LOG"
        exit 1
        ;;
    exited)
        echo "GRADLE EXITED BEFORE THE SERVER LOADED -- last 40 lines of $GRADLE_LOG:"
        tail -40 "$GRADLE_LOG"
        exit 1
        ;;
    *)
        echo "TIMED OUT after ${TIMEOUT}s -- last 40 lines of $LOG:"
        tail -40 "$LOG" 2>/dev/null || echo "(no log was written at all)"
        exit 1
        ;;
esac

# Loading "successfully" still leaves plenty of room for a broken asset: Forge logs missing
# models and bad recipes as errors and carries on with a placeholder.
echo
echo "-- registration and asset errors --"
if grep -nE 'Exception|ERROR\]|Missing model|Unable to load|Parsing error|Caught exception' "$LOG" \
        | grep -vE 'TerminalConsole|LoggerNamePatternSelector|Unable to locate appender' \
        | head -40; then
    :
fi
if ! grep -qE 'Exception|ERROR\]|Missing model|Unable to load|Parsing error' "$LOG"; then
    echo "(none)"
fi

echo
echo "-- immersiveengineering lines of note --"
grep -niE 'immersiveengineering.*(error|exception|missing|fail)' "$LOG" | head -20 || echo "(none)"

echo
echo "smoke test complete: server reached Done"
