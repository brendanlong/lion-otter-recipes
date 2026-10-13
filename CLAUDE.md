- Never silently discard exceptions. Always log exceptions with `Log.w`/`ErrorLog.e` at minimum (`ErrorLog.e` also reports the exception to Crashlytics in release builds).
  In user-facing operations, propagate errors so the UI can inform the user the action failed.
  It is better to tell the user something went wrong than to silently pretend everything is fine.
- Wrap suspending mutations (writes, deletes, uploads, etc.) in `withContext(NonCancellable)` so they finish even if `viewModelScope` is cancelled. Keep UI state updates outside the `NonCancellable` block.
  Non-suspending repository writes (`setFavorite`, `deleteRecipe`, `saveMealPlan`, …) apply locally at once and report
  failures app-wide (see DESIGN.md "Repository"); call them directly, without try/catch or `NonCancellable`.
- Show users localized `UserMessage`s (string resources), never exception messages. Send one-off UI events through a
  per-ViewModel `Channel` + `receiveAsFlow`, collected with `CollectEvents` (see DESIGN.md "ViewModels").
- Commit after finishing fixes or features
- Prefer to amend if fixing the current commit
- ALWAYS check the current commit before amending
- DESIGN.md records cross-cutting decisions and invariants only — not code structure. Write each fact in one place
  (prefer KDoc on the class for class-specific details). Don't update DESIGN.md for routine changes; only when a
  decision or invariant changes.
- Before committing, always run the CI checks locally to verify nothing is broken:
  - `./ci-local.sh` (runs assembleDebug, testDebugUnitTest, lintDebug, and detektDebug in parallel)
  - See `.github/workflows/android-ci.yml` for the full CI pipeline
- Compiler and lint warnings fail the build. Don't add entries to the detekt baseline or the
  `LayerDependencyTest` allowlist; fix the code instead (see DESIGN.md "Guardrails").
