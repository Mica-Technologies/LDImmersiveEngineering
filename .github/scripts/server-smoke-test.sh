#!/usr/bin/env bash
#
# Boot a dedicated server via `./gradlew runServer` and assert it reaches "Done (".
#
# Why this exists: mod code that compiles fine can still be impossible to load on a
# dedicated server — a client-only class referenced from common code, a @SideOnly
# method called from common code, a registry entry built during the wrong phase.
# Forge only catches those at server startup, so `./gradlew build` is perfectly happy
# right up until the server dies. This mod also ships a coremod (IELoadingPlugin) and
# an access transformer, neither of which is exercised at all by a plain compile —
# an AT that no longer matches its target fails only when FML applies it at runtime.
#
# `runServer` never returns on success, so we background it, tail the log for a
# verdict, then shut it down.
#
# Env:
#   SMOKE_TIMEOUT   seconds to wait for startup (default 900)
#   SMOKE_LOG       log file path (default server-smoke.log)

set -uo pipefail

TIMEOUT="${SMOKE_TIMEOUT:-900}"
LOG="${SMOKE_LOG:-server-smoke.log}"

# Signals a successfully started dedicated server.
SUCCESS_RE='Done \([0-9.]+s\)!'

# Any of these mean the server is not coming up. "for invalid side" is the specific
# signature of client-only code reaching the server; the coremod/AT failures are the
# ones unique to this mod.
FAILURE_RE='Encountered an unexpected exception|MissingModsException|for invalid side|A fatal error has occurred|The state engine was in incorrect state|Failed to start the minecraft server|FML has found a problem|Coremod .* failed|Critical injection failure'

# ForgeGradle 2.3 runs the server in the directory named by `minecraft.runDir`, which
# build.gradle sets to "run". Writing the EULA is invisible locally (an accepted
# run/eula.txt lingers from earlier manual runs) and only shows up on a clean CI
# checkout as "You need to agree to the EULA in order to run the server".
mkdir -p run/logs
printf 'eula=true\n' > run/eula.txt

# The verdict has to be read from BOTH streams.
#
# Under ForgeGradle's dev launcher, log4j cannot construct Forge's Console appender
# (it logs "Error processing element TerminalConsole ... CLASS_NOT_FOUND" and then
# "Unable to locate appender 'Console'"). From that point on, everything the server
# prints — including "Done (3.985s)!" — goes ONLY to run/logs/latest.log, and the
# Gradle stdout we captured goes quiet. Watching just the Gradle log makes a server
# that started perfectly look like a hang, and the job burns its whole timeout.
SERVER_LOG="run/logs/latest.log"
# Start from a clean file so a previous run's "Done (" can't produce a false pass.
rm -f "$SERVER_LOG"

# grep across both, tolerating either being absent at any given moment.
scan() { grep -qaE "$1" "$LOG" "$SERVER_LOG" 2>/dev/null; }

echo "==> Starting dedicated server (timeout ${TIMEOUT}s)"
./gradlew runServer \
  -Dhttp.socketTimeout=60000 -Dhttp.connectionTimeout=60000 \
  -Dorg.gradle.internal.http.socketTimeout=60000 \
  -Dorg.gradle.internal.http.connectionTimeout=60000 \
  > "$LOG" 2>&1 &
GRADLE_PID=$!

verdict="timeout"
elapsed=0
while [ "$elapsed" -lt "$TIMEOUT" ]; do
  if scan "$FAILURE_RE"; then
    verdict="crash"
    break
  fi
  if scan "$SUCCESS_RE"; then
    verdict="ok"
    break
  fi
  if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
    # Gradle exited without ever printing "Done (" — build failure or early abort.
    verdict="exited"
    break
  fi
  sleep 5
  elapsed=$((elapsed + 5))
done

echo "==> Stopping server (verdict: ${verdict}, after ${elapsed}s)"
kill "$GRADLE_PID" 2>/dev/null
# The Gradle wrapper spawns the server in a child JVM; kill it too so the runner
# doesn't hang waiting on an orphan.
pkill -f 'net.minecraft.server' 2>/dev/null
pkill -f 'GradleStart' 2>/dev/null
pkill -f 'GradleWrapperMain' 2>/dev/null
wait "$GRADLE_PID" 2>/dev/null

if [ "$verdict" = "ok" ]; then
  echo "==> PASS: dedicated server reached startup"
  grep -haE "$SUCCESS_RE" "$LOG" "$SERVER_LOG" 2>/dev/null | head -1
  exit 0
fi

echo "==> FAIL: dedicated server did not start (${verdict})"
echo "----- matching failure lines -----"
grep -naE "$FAILURE_RE" "$LOG" "$SERVER_LOG" 2>/dev/null | head -20
echo "----- last 60 lines of gradle output (${LOG}) -----"
tail -60 "$LOG" 2>/dev/null
echo "----- last 120 lines of server log (${SERVER_LOG}) -----"
tail -120 "$SERVER_LOG" 2>/dev/null
exit 1
