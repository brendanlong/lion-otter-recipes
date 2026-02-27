#!/usr/bin/env bash
set -euo pipefail

# Local CI script: sets up google-services.json, then runs build, test, and lint.
# Uses a single Gradle invocation with --continue so all tasks run even if some fail.
# Output is written to a log file for inspection.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

LOG_DIR="$SCRIPT_DIR/build/ci-logs"
mkdir -p "$LOG_DIR"

LOG_FILE="$LOG_DIR/ci.log"

echo ""
echo "Running: ./gradlew assembleDebug testDebugUnitTest lintDebug --continue"
echo "  log: $LOG_FILE"
echo ""

if ./gradlew assembleDebug testDebugUnitTest lintDebug --continue > "$LOG_FILE" 2>&1; then
  exit_code=0
else
  exit_code=$?
fi

echo "Exit code: $exit_code"
echo "     log: $LOG_FILE"
echo ""

if [ "$exit_code" -ne 0 ]; then
  echo "CI checks FAILED. See log file for details."
  echo ""
  # Show the last few lines for quick diagnosis
  tail -20 "$LOG_FILE"
else
  echo "All CI checks passed."
fi

exit "$exit_code"
