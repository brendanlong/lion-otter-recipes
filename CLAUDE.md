- Never silently discard exceptions. Always log exceptions with `Log.w`/`Log.e` at minimum.
  In user-facing operations, propagate errors so the UI can inform the user the action failed.
  It is better to tell the user something went wrong than to silently pretend everything is fine.
- Commit after finishing fixes or features
- Prefer to amend if fixing the current commit
- ALWAYS check the current commit before amending
- Reference @docs/architecture.d2 and keep it up to date with any changes
- Before committing, always run the CI checks locally to verify nothing is broken:
  - `./ci-local.sh` (runs assembleDebug, testDebugUnitTest, and lintDebug in parallel)
  - See `.github/workflows/android-ci.yml` for the full CI pipeline
