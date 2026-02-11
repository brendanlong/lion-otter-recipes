#!/usr/bin/env bash
set -euo pipefail

# Local CI script: sets up google-services.json, then runs build, test, and lint.
# Uses a single Gradle invocation with --continue so all tasks run even if some fail.
# Output is written to a log file for inspection.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

LOG_DIR="$SCRIPT_DIR/build/ci-logs"
mkdir -p "$LOG_DIR"

# Ensure google-services.json exists
GSERVICES="$SCRIPT_DIR/app/google-services.json"
if [ ! -f "$GSERVICES" ]; then
  echo "Creating dummy google-services.json..."
  cat > "$GSERVICES" << 'GSEOF'
{"project_info":{"project_number":"0","project_id":"dummy","storage_bucket":"dummy.appspot.com"},"client":[{"client_info":{"mobilesdk_app_id":"1:0:android:0","android_client_info":{"package_name":"com.lionotter.recipes"}},"oauth_client":[],"api_key":[{"current_key":"dummy"}],"services":{"appinvite_service":{"other_platform_oauth_client":[]}}}],"configuration_version":"1"}
GSEOF
else
  echo "google-services.json already exists, skipping creation."
fi

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
