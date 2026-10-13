# Lion+Otter Recipes - Design Decisions

This document records cross-cutting decisions and invariants that aren't obvious from the code. It deliberately
doesn't describe code structure (packages, classes, screens, DI modules): read the code and its KDoc for that.
Each fact is written in one place; class-specific details belong in KDoc on the class. Update this file only when a
decision or invariant changes, not for routine changes.

## Architecture

Clean Architecture in one Gradle module (`app`), packages under `com.lionotter.recipes`:

- `domain/`: plain-Kotlin models, use cases, repository interfaces and ports.
- `data/`: Firestore repositories, Room (import queue and debug data only), DataStore, the AI transport, images.
- `ui/` (Compose + ViewModels), `imports/` (the app-scoped import queue), `worker/` (WorkManager), `messages/` (user-facing text),
  `util/` (plain-Kotlin helpers), `di/` (Hilt).

Recipes and meal plans live in Firestore (offline persistence is the local copy and write queue), not Room.

### Dependency Rule

Dependencies point inward: UI → Domain ← Data. The domain may only use `kotlin.*`, `kotlinx.*`, `java.*`, `javax.inject`,
its own packages, `util/` (same rule) and `org.ojalgo`; no Android, Compose, Firebase, Anthropic SDK or data types.

- **Ports**: when a use case needs something the data layer provides, the domain owns an interface (`domain/port`), the
  data layer implements it, and `PortModule` binds it. Ports return `Outcome` with a typed `RecipeError` instead of
  throwing or returning SDK types; implementations log the underlying exception.
- Logging goes through `ErrorLog` (a `LogSink` installed by `LionOtterApp`), so domain tests need no `mockkStatic(Log)`.
- Domain models carry no `@Immutable`; `app/compose-stability.conf` lists them instead.
- Dispatchers are injected (`@IoDispatcher` / `@DefaultDispatcher`); only `AppModule` names `Dispatchers.IO`/`Default`.
  App-lifetime work (repository listeners, pending writes, the import queue) runs in the injected `@ApplicationScope`
  (it has a logging exception handler); classes don't create their own scopes.

## Guardrails

All run in `./ci-local.sh` and CI. Fix the code; don't grow the allowlists.

- **Warnings are errors**: `allWarningsAsErrors` and lint `warningsAsErrors`. Suppress only with a comment saying why.
- **Compose lint** (slack compose-lint-checks): `modifier` parameter, parameter order, default `hiltViewModel()` params.
- **detekt** (`./gradlew detektDebug`, type resolution; the plain `detekt` task lacks both): `SuspendFunSwallowedCancellation`,
  `InjectDispatcher`, `SwallowedException` (logging counts as handling; `catch (_: ...)` doesn't), `TooGenericExceptionThrown`,
  `UnsafeCallOnNullableType`. **Never add to `config/detekt/baseline-debug.xml`**; regenerate it only after fixing entries.
  detekt's analysis doesn't run the serialization plugin, so use reified `json.decodeFromString<T>()`, not `T.serializer()`.
- **Layer rules** (`LayerDependencyTest`): the Dependency Rule above, plus: UI may not use WorkManager or `data.local`;
  data may not use `ui`, `worker` or `imports`; `imports`/`worker` may not use `ui` (and `worker` not `imports`).
  `KNOWN_VIOLATIONS` fails on new violations and on stale entries, so it only shrinks. **Never add entries.**
- **Persisted-format tests**: `DtoFieldDriftTest` / `DomainFixturesTest` (every model field has a DTO field, fixtures set
  every field), `RecipeResponseSchemaTest` (AI schema matches serializers and stays within limits),
  `RecipeRegeneratedContentTest` (non-AI fields survive regeneration), `WorkContractTest` / `PendingImportStatusTest`
  (values older versions wrote still decode), `RecipeDatabaseMigrationTest` (add a step for every Room migration).
- Tests prefer in-memory fakes (`testutil/RepositoryFakes.kt`, `testutil/UseCaseFakes.kt`) over mocks.
  `LiveAnthropicTest` hits the real API and runs only when `LIVE_ANTHROPIC_KEY` is set.

## Repository

- **Write contract**: non-suspending writes (`saveRecipe`, `deleteRecipe`, `setFavorite`, `setUserNotes`, `setTags`,
  `saveMealPlan`, …) apply to the local cache immediately (flows update at once, also offline) and never throw. They
  sync in the background; a failure is reported asynchronously as a typed `DataFailure(op, entityId)` on
  `FirestoreService.failures`, which `RecipesActivity` toasts. Callers need no try/catch, `NonCancellable` or error state.
  Suspending repository functions (reads, counts, query-then-write operations, transactions) do throw.
- `failures` is a `Channel` (32, oldest dropped): each failure is delivered once, and ones raised in the background wait
  for the activity. Snapshot listener errors arrive the same way; listeners never die on an error.
- Repositories re-subscribe when the auth state or `FirestoreService.generation` changes, so account switches and
  local-data clears swap the listener to the right user.
- `deleteRecipe` deletes only the document; use `DeleteRecipeUseCase` (meal plans, then recipe, then image release).

### Firestore storage

- Documents live at `users/{uid}/recipes/{id}` and `users/{uid}/mealPlans/{id}`. The security rules allow only those two
  collections, so other per-user data (e.g. favorite tags) can't go to Firestore without a rules change.
- A recipe's instructions (with their ingredients) are stored as one JSON string, `instructionSectionsJson`, not as
  nested Firestore maps; the DTO field name differs from the model's on purpose. A document whose JSON can't be parsed throws `CorruptRecipeException`: listings skip it and single reads
  fail, so it is never loaded with empty instructions that a later full save would write back.
- Field names in queries and partial updates come from `RecipeFields` / `MealPlanFields`, never string literals.
  Persisted field names are never renamed. JSON uses the shared `AppJson` configuration.

## Error model and user messages

- **Users never see exception messages.** Import/edit/regenerate/archive failures are a sealed `RecipeError`;
  `retryable` comes from the failure kind and drives worker retries. Exceptions that know their meaning implement
  `RecipeErrorSource`; anything else is `Unexpected` and its details only go to `ErrorLog`.
- `RecipeError` is persisted (worker output, `pending_imports.errorMessage`) as JSON with stable `@SerialName`s: never
  rename them. Old English messages from earlier versions decode to `Legacy(message)` and are shown as-is.
- ViewModels and workers produce `UserMessage`s (string/plural resource + args); screens resolve them.
  `UserMessageTest` resolves every error against the real resources.
- `ErrorLog.e` sends only the throwable to Crashlytics (release builds), never the message, which may hold recipe names
  or URLs.

## ViewModels

- Suspending mutations run in `withContext(NonCancellable)`; UI state updates stay outside it. Repository writes under
  the write contract are just called.
- **One-off events** (navigation, snackbars, toasts, undo) go through one `Channel<Event>(BUFFERED)` per ViewModel,
  exposed as `receiveAsFlow()` and collected with `CollectEvents` (only while STARTED). Never `MutableSharedFlow` +
  `tryEmit` (drops events with no collector) or state plus a `reset…()` callback. Conditions that persist (loading,
  a field's validation error, an import's failure) are state.
- UI state is derived (`combine` of sources) rather than written from observers. Screens follow their own work with
  `WorkTracker` (work ID in `SavedStateHandle`, observed by ID), so work that finished while the screen was gone, or
  across process death, is still reported once; ViewModels never see `WorkInfo` or `WorkManager`.
- State that must survive process death lives in `SavedStateHandle` (`getStateFlow` / `getMutableStateFlow`), as the
  only copy rather than mirrored by hand.
- Actions that set a value take the target value from the UI (e.g. `setFavorite(id, isFavorite)`), so double taps can't flip it back.

## UI

- State is never shown by color alone (e-ink and grayscale screens can't show it): favorites are filled vs outlined
  stars, selected chips get a check, failed imports a warning icon.
- The E-ink theme gets contrast from borders, not fills: components that rely on a surface color add an edge when
  `LocalEinkTheme` is set (`einkBorder()`, `Modifier.einkEdge`), and all Compose animations are off.

## Navigation

- Type-safe `@Serializable` routes (`ui/navigation/Routes.kt`), read with `savedStateHandle.toRoute<Route.X>()`. Navigation
  encodes arguments, so URLs with `%`, `#`, `?`, `&` round-trip (`RouteTest`). Enum route arguments need `@Keep`
  (their serializer is looked up reflectively; R8 would strip it).
- Results for a screen lower in the back stack go through typed `NavResult` keys in that entry's `SavedStateHandle`,
  and only small values (e.g. a file path, never page HTML). A back-stack entry's `SavedStateHandle` is **not** the one Hilt
  gives its ViewModels: the destination reads the result, passes it to the screen, which hands it to the ViewModel.
- ViewModel tests build handles with `SavedStateHandle(route = ...)`, which needs Robolectric.

## Accounts

Every user starts as a local-only guest (Firestore network disabled); there is no login screen. `AuthState` is
`Loading`, `Guest(uid)` or `Google(uid, email)`, resolved synchronously at startup (`LionOtterApp` injects `AuthService`
eagerly so no Activity or worker touches Firestore first). Transitions are serialized and the state is `Loading`
throughout each one.

- **Guest → Google (merge)**: guest data is migrated to the Google account; writes and `awaitUid()` callers during
  the merge wait and then go to the resulting account (or back to the guest if migration fails). Background work keeps
  running and local data now belongs to the account. Local `file://` images are uploaded afterwards.
- **Sign-out and account deletion (leave)**: nothing meant for the leaving account may end up in the next one.
  Background work is stopped while the account is still signed in (recipe work is cancelled and awaited up to 30 s).
  Writes made during the transition are dropped (logged, not shown), and `awaitUid()` throws `AccountLeftException`
  for any caller that started before or during it; workers treat that like cancellation. Local stores are cleared only
  after that. The one bounded exception is a worker stuck in a `NonCancellable` save past the 30 s wait.
- Sign-out without `force` changes nothing and returns `PendingWrites` while writes haven't synced or images exist only
  on the device. Deletion deletes server data (read from the server, not the cache) first, so a failure leaves the user
  signed in with data intact; Storage images go after documents (orphaned images, never dangling references).
- **Per-user local data registry**: every store of a user's data on the device implements `PerUserLocalData` with a
  scope (`ACCOUNT`: cleared on sign-out and deletion; `DEVICE`, e.g. the API keys and import debug data: deletion only)
  and is bound in `LocalUserDataModule`. `LocalUserDataRegistryTest` fails when a source file uses a persistence API
  without being registered or listed with a reason. Device preferences (settings, theme, favorite tags) survive
  sign-out and are cleared by account deletion. A store that fails to clear is logged and reported; the rest still clear.
- Favorite tags are per device in DataStore, not Firestore (rules, and no guest-to-Google migration needed).

## Images

A recipe's image URL is `file://` (on this device only: guests, or a failed upload) or `gs://` (Firebase Storage,
Google users). Local files live only in `files/recipe_images/`.

- An image is deleted only when no other recipe references it (a recipe and its "Save as copy" share one); if that
  can't be determined it is kept. Only files inside `recipe_images/` are ever deleted.
- Replacing an image releases the old one only after the new one is stored. A failed upload keeps the local file
  and the old image, and schedules an upload pass.
- The upload pass points a recipe at its upload only if the recipe still has that local file (a transaction);
  otherwise the upload is deleted.
- `importFromUrl` never reads `content://` URLs (they could come from imported files); picker images use
  `importPickedImage`. Files appear atomically (temp file, then rename).

## Background work

- Long-running work runs in WorkManager workers. **Inputs that can be large go to files via `ImportInputStore`**,
  never WorkManager `Data` (10 KB limit): `Data` carries IDs and paths. Picked or shared files are copied into app storage
  at once, since content-URI grants don't survive process death; the queue row is inserted only after all inputs exist.
- Each worker has a contract object with sealed `Progress` / `Outcome` types. **Key and value strings are persisted by
  WorkManager and must not change**; new keys must be optional so work enqueued by older versions still runs
  (`WorkContractTest` pins old values). Same for `PendingImportStatus` strings in Room.
- Live import progress comes from WorkInfo; Room keeps only what must outlive a run (name/image, Paprika waiting
  state, terminal failures). Failed imports stay listed with Retry/Dismiss; Retry reuses the staged inputs.
- Cancelling an import cancels its unique work and waits (up to 30 s) for the worker's in-process lock before
  cancelling any AI batch and deleting files.
- **Paprika import** is a resumable state machine whose state is derived from files alone. Invariants (tested by
  restarting with fresh instances, `PaprikaImportStepsTest`): the batch is created and `batch.json` written in one
  `NonCancellable` block, and submission never runs once `batch.json` exists, so a stopped worker never submits twice;
  an unreadable `batch.json` fails rather than resubmits. Results are saved under IDs derived from import ID +
  custom_id, so a resumed run overwrites rather than duplicates. While waiting on the batch the worker schedules a
  delayed copy of itself and exits (no foreground service for hours). The file layout and `PaprikaBatch` field names
  must stay readable by newer versions.
- **Untrusted input is size-capped before it is read into memory**: archive entries and totals (`ArchiveBudget` /
  `BoundedInputStream`, against zip bombs), downloaded and captured pages, images (25 MB local; 5 MB uploads, matching
  the Storage rules), recipe files (text/Markdown 200 KB, HTML 5 MB, PDF 10 MB and 20 pages, up to 5 photos of 25 MB
  each). Limits shared with the server rules (document
  sizes, counts) live in `ResourceLimits`. Recipe files are decoded by byte order mark, then an HTML `<meta charset>`,
  then UTF-8.

## AI requests

- Interactive URL imports capture the page in the in-app browser rather than downloading it, because many recipe sites
  answer non-browser fetches with a 403 bot check.
- **Structured output**: the response schema (`RecipeOutputSchema`) must keep **every property required** (absent
  values are `null`) and stay within the API's limit of **16 union-typed (nullable) parameters; it uses 14**
  (`RecipeResponseSchemaTest` caps it). Prefer a required string with `""` for none (e.g. ingredient `modifier`) over a
  new nullable field. `extractJson` remains a fallback parser.
- **Prompt caching**: the system prompt must be identical for every request; `RecipePromptTest` pins its hash.
  Per-request context (the user's existing tags, instructions, density overrides) goes in the user message, never the
  system prompt. Photos and PDFs go in the user message too, as image/document blocks before its text,
  which says what they are. Photos are scaled to 1568 px on the long side (JPEG, under the API's 5 MB per image) before
  sending; PDFs go as is. The size caps above keep a request well under the API's 32 MB limit, and bound memory (the
  worker holds a PDF about four times over: bytes, base64, request body, network buffer). The tag hint is the user's 100 most-used tags read from the local cache; a read failure only drops it.
- Single-recipe parses stream (large `max_tokens` would hit HTTP timeouts) and use server-side `fallbacks: "default"`,
  so a safety decline is retried on a fallback model in the same stream; text after the last `fallback` boundary wins.
- Every supported model takes adaptive thinking plus an effort level (no fixed `budget_tokens`); Fable always thinks
  and rejects an explicit thinking config. Unknown stored model IDs are normalized by `AiModels.normalizeModel()`.
- Ingredient fields: `name` is what you buy, `modifier` is preparation ("peeled", "melted"), `notes` everything else.
  The grocery list merges by name and ignores the modifier; the recipe's "all ingredients" view keeps modifiers apart.
  Recipes parsed before modifiers existed keep preparation in `name`/`notes` and are not migrated.
- Regeneration and AI edits copy only AI-owned fields (`Recipe.withRegeneratedContent`); every other field is preserved.
- **Generated images**: Claude can't draw, so recipe images come from OpenAI's image API with a separate OpenAI key
  (optional; generation for new imports defaults to on once a key is set). Every parse also writes an `imagePrompt`
  (a required schema string, `""` for none), which is saved on the recipe and is AI-owned like its other content.
  A recipe without a prompt gets one from the edit model when an image is needed. All image work runs one at a time in
  a single WorkManager chain (OpenAI's image rate limits are low), and a failed item still succeeds so the chain goes
  on. A generated image is attached only if the recipe's image hasn't changed meanwhile (checked before and after
  the upload). Paprika imports never generate images automatically.
- **Edit screen**: title, source URL and tags are edited outside the Markdown and saved directly; only a Markdown
  change or AI instructions run the AI. The AI still gets all three in the Markdown it is sent, but an AI edit
  saves the screen's tags, not the AI's (also for "Save as copy"): only the tag editor changes them, so the AI
  can't bring back a tag the user removed. (Regeneration from the source still takes the AI's tags.)
