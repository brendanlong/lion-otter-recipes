# Lion+Otter Recipes - Design Document

This document describes the internal architecture and design decisions of the Lion+Otter Recipes Android app.

## Architecture Overview

The app follows **Clean Architecture** with three distinct layers:

```
┌─────────────────────────────────────────┐
│             UI Layer                     │
│   (Compose Screens, ViewModels)          │
├─────────────────────────────────────────┤
│           Domain Layer                   │
│      (Use Cases, Domain Models)          │
├─────────────────────────────────────────┤
│            Data Layer                    │
│  (Repository, Database, API Services)    │
└─────────────────────────────────────────┘
```

### Dependency Rule

Dependencies point inward: UI → Domain → Data. The goal is a domain layer with no dependencies on Android frameworks or external libraries (except kotlinx for serialization). This is not true yet: use cases still use some data-layer services, `android.util.Log` and Compose stability annotations. `LayerDependencyTest` enforces the rules and lists the current exceptions (see Guardrails).

## Data Layer

### Local Storage

#### Room Database (`RecipeDatabase`)

Recipes and meal plans do **not** live in Room; they are stored in Firestore (see Repository below). Room holds only local, device-specific data in two tables:

- `PendingImportEntity` (`PendingImportDao`): the import queue shown on the recipe list (status, name, URL, WorkManager ID, failure message). `status` is the `PendingImportStatus` enum, stored through `PendingImportStatusConverter` as the same lowercase strings older versions wrote. Failed imports stay in the table so the list can offer Retry/Dismiss.
- `ImportDebugEntity` (`ImportDebugDao`): raw HTML, AI input/output and errors captured when import debugging is enabled. The DAO exposes lightweight summary queries (`getAllDebugSummaries`, `getDebugSummaryById`) and chunked content reads (`getContentLength`, `getContentChunk`) so large blobs are never loaded into a single cursor window.

**Design Decision**: Large debug payloads are read in chunks because a single multi-megabyte column exceeds Android's CursorWindow limit.

Migrations live in `RecipeDatabaseMigrations` (10→11 drops the Firestore-era tables, 11→12 adds `import_debug.batchMode`); versions before 10 are recreated destructively.

#### DataStore

`SettingsDataStore` stores user preferences:
- Anthropic API key, encrypted with Tink (`TinkEncryption`) and kept out of the plain DataStore
- `ai_model` (imports) and `edit_model` (edits), plus a `thinking` toggle
- Display, theme, keep-screen-on, measurement unit preferences (recipe and grocery), start of week, import debugging

`GroceryListStateStore` is a separate DataStore holding grocery list checked state, keyed per week start date. Old weeks are pruned on save.

#### Local files

- Local image cache (`ImageDownloadService`, `ImageSyncService`): images are cached on disk and uploaded to Firebase Storage. Images saved while signed out of Google stay local until `ImageSyncService.uploadPendingLocalImages()` runs.
- `ImportInputStore`: staged import inputs under `files/imports/<importId>/` (copied source file, selection, `ImportKind`, `batch.json` for Paprika batches). Orphans are cleaned up at startup.

### Remote Services

#### WebScraperService

Simple HTTP client that fetches raw HTML from recipe URLs:

```kotlin
suspend fun fetchHtml(url: String): Result<String>
```

- Uses mobile User-Agent to get mobile-friendly pages
- No parsing in the scraper itself; `RecipeHtmlExtractor` (domain/util) prepares the page for the AI

#### AnthropicService

Calls the Claude API (official SDK) to parse recipe text into structured data:

- **Models**: `claude-sonnet-5-5` (default for imports), `claude-haiku-5-5` (default for edits), `claude-opus-5-5`, `claude-fable-5-1`. `normalizeModel()` maps legacy or unknown stored IDs to the closest supported family.
- **Structured output**: requests use a JSON schema (non-recursive, derived from `RecipeParseResponse`) so the response is valid JSON; `extractJson` remains as a fallback.
- **Streaming**: single-recipe parses use `createStreaming` so large `max_tokens` values do not hit HTTP timeouts; the stream is closed on coroutine cancellation.
- **Effort/thinking per model**: every supported model takes adaptive thinking plus an effort level, and none accepts a fixed `budget_tokens` budget. The "thinking" setting maps to effort (medium when on, low when off), with adaptive thinking requested when on (except Fable 5.1, which always thinks and rejects an explicit config).
- **Server-side fallback**: single-recipe parses go through the beta Messages API with `fallbacks: "default"` (all supported models), so a safety-classifier decline is retried on a fallback model in the same stream. `fallback` content blocks mark the switch; text before a mid-stream boundary is continuation context, so the joined text is parsed first, then the text after the last boundary.
- **Batches**: `createRecipeBatch`, `retrieveBatch`, `cancelBatch`, `forEachBatchResult` (streams results) back the Paprika import.

**Prompt Engineering**: The system prompt instructs Claude to:
1. Extract all recipe metadata (name, times, servings)
2. Group ingredients and instructions by section/step
3. Parse quantities as decimals for scaling
4. Generate relevant tags
5. Prefer schema.org JSON-LD data when present alongside the page text

### Repository

`RecipeRepository` and `MealPlanRepository` (behind `IRecipeRepository` / `IMealPlanRepository`) are the sources of truth, backed by Firestore (`FirestoreService`) at `users/{uid}/recipes/{recipeId}` and `users/{uid}/mealPlans/{id}`. Firestore's offline persistence provides the local copy and write queue.

```kotlin
interface IRecipeRepository {
    fun getAllRecipes(): Flow<List<Recipe>>
    fun getRecipeById(id: String): Flow<Recipe?>
    suspend fun getRecipeByIdOnce(id: String): Recipe?
    fun saveRecipe(recipe: Recipe)
    fun deleteRecipe(id: String)
    fun setFavorite(id: String, isFavorite: Boolean)
    // ... notes, image URL, title/URL updates, counts
}
```

- **Write contract.** The non-suspending writes (`saveRecipe`, `deleteRecipe`, `setFavorite`, `setUserNotes`, `setImageUrl`, `updateTitleAndUrl`, `saveMealPlan`, `updateMealPlan`, `deleteMealPlan`) apply to the local cache immediately, so flows show the change at once (also offline), and never throw. They sync in the background; a failure (or a failure to start the write) is reported asynchronously as a typed `DataFailure(op: DataOp, entityId)` through `FirestoreService.failures`. Callers need no try/catch, `NonCancellable` or error state for them. The suspending functions (reads, counts, `deleteMealPlansByRecipeId`, which must query first) do throw. Writes waiting for an auth transition and the shared listeners run in the injected `@ApplicationScope` (with its logging exception handler); repositories and `SettingsDataStore` don't create scopes of their own.
- `FirestoreService.failures` is a `Channel` (up to 32 queued, oldest dropped) read with `receiveAsFlow`: each failure is delivered once, and failures while the app is in the background wait until `MainActivity` collects again (it collects while STARTED and toasts `DataFailure.toUserMessage()`). Snapshot listener errors arrive the same way (`LOAD_RECIPES` / `LOAD_MEAL_PLANS`), as does a failure to clear the previous account's local data (`CLEAR_PREVIOUS_ACCOUNT`). `deleteMealPlansByRecipeId` commits its batch through `FirestoreService.batch()`.
- Both repositories re-subscribe when the auth state (`AuthService.authState`) or the Firestore instance generation (`FirestoreService.generation`) changes, so a sign-in, sign-out or local-data clear swaps the listener to the right user.
- `data/repository/FirestoreFlows.kt` provides the shared helpers: an error-tolerant snapshot `Flow` (errors are reported as a `DataFailure` instead of killing the collector), incremental decoding that only re-decodes changed documents and skips undecodable ones, and `writeForCurrentUser` / `reportFailureTo` for the write contract.
- `ImportDebugRepository` wraps `ImportDebugDao`; `PendingImportRepository` wraps `PendingImportDao`.
- `RecipeDto` / `MealPlanDto` map the domain models to Firestore documents by hand. Field names used in queries and partial updates come from `RecipeFields` / `MealPlanFields` next to the DTOs, never string literals. A recipe whose `instructionSectionsJson` can't be parsed throws `CorruptRecipeException`: listings skip it (logged) and single reads fail, so it is never loaded with empty instructions that a later full save would write back. The JSON uses the shared `AppJson` configuration (also provided by `AppModule`).

### Authentication and Account Lifecycle

- `AuthService` resolves auth state synchronously at startup. `LionOtterApp` injects it eagerly so state is known (and Firestore network access is disabled for guests) before any Activity or WorkManager job touches Firestore; `MainActivity` does no auth work. There is no login screen: every user starts as a local-only **guest**, and `AuthState` is `Loading`, `Guest(uid)` or `Google(uid, email)`.
- Code that needs a uid off the main path calls `awaitUid()`, which waits (without timeout) while the state is `Loading`. Fire-and-forget repository writes made while `Loading` likewise wait and then go to the resulting account.
- `signInWithGoogle` gets a credential; `mergeGuestIntoGoogle { migrate }` then holds the auth transition lock with the state `Loading` while `AccountMigrationService` copies guest data (server calls have timeouts) and signs in, clears the guest cache, and switches to `Google` (or restores the guest on failure). After a successful Google sign-in, `SettingsViewModel` schedules an upload through `ImageUploadScheduler` (`LocalImageUploadWorker`), which calls `ImageSyncService.uploadPendingLocalImages()`.
- `ImageDownloadService.storeImage` keeps the local file and schedules `LocalImageUploadWorker` (through the `ImageUploadScheduler` interface, bound in `SchedulerModule`, so the data layer doesn't depend on workers) when a Google user's upload fails (the worker retries while any upload fails, and only replaces a recipe's image if it is still that local file). Images are only deleted from Storage when no other recipe (e.g. a "Save as copy") references them, and changing a recipe's image also updates its meal plans' `recipeImageUrl`.
- `signOut(force)` returns `SignOutResult.Success` or `SignOutResult.PendingWrites` when changes have not reached the server or recipe images are still only on the device; Settings warns about unsynced changes and the user may sign out anyway (`force = true`).
- `AccountDeletionService` (depends on `FirestoreService`, `ImageSyncService`, `ImportDebugRepository`, `SettingsDataStore`) first checks the server is reachable and re-authenticates, reads recipes and meal plans from the server (not the cache), deletes Firestore documents, then deletes Storage images individually (failures leave orphaned images, never recipes pointing at deleted images), clears local images, debug data and the API key, and finally deletes the Firebase Auth account.

### Error Reporting

- Errors are logged with `ErrorLog.e(tag, message, throwable)` (in `util/`) rather than `Log.e`. It logs to logcat and, in release builds, records the throwable as a Crashlytics non-fatal (`LionOtterApp` installs the recorder). Only the throwable is sent, not the message, since messages often include recipe names or URLs.
- **User-facing errors are never exception messages.** Failures of imports, edits, regeneration and archives are a sealed `RecipeError` (domain): `NoApiKey`, `NoSourceUrl`, `RecipeNotFound`, `LimitReached(count, limit)`, `PageFetch(PageFetchFailure, httpStatus)`, `Ai(AiFailure, httpStatus)`, `NotARecipe(reason)`, `Archive(ArchiveFailure)`, `Interrupted`, `CannotRetry`, `Unexpected` and `Legacy(message)` (an English message saved by an older version, shown as-is). `retryable` comes from the failure kind and drives worker retries. Data-layer exceptions that know their meaning implement `RecipeErrorSource` (`PageFetchException`, `AnthropicRequestException`, `RecipeParseException`, `RecipeErrorException`), so the domain maps them with `Throwable.toRecipeError()` without depending on those classes; anything else is `Unexpected` and its details only go to `ErrorLog`. `NotARecipe.reason` is the AI's own explanation and is shown.
- The `messages` package turns errors into text: `UserMessage` (a string or plural resource plus arguments, resolved with `resolve(resources)` or, in Compose, `text()`), `RecipeError.toUserMessage()` (exhaustive) and `DataFailure.toUserMessage()`. ViewModels and workers (failure notifications) only produce `UserMessage`s; screens resolve them. `UserMessageTest` resolves every error against the real resources.
- Uncaught crashes are reported by Crashlytics automatically. Collection is disabled in debug and benchmark builds via the `firebase_crashlytics_collection_enabled` manifest placeholder, and R8 mapping files are uploaded only when building in CI.

## Domain Layer

### Models

#### Recipe

Core domain model representing a complete recipe. Ingredients live inside instruction steps (so each step knows what it uses); ingredient lists are derived by aggregation:

```kotlin
data class Recipe(
    val id: String,
    val name: String,
    val sourceUrl: String?,
    val story: String?,                           // AI-extracted summary
    val servings: Int?,
    val instructionSections: List<InstructionSection>,
    val tags: List<String>,
    val isFavorite: Boolean,
    // times, equipment, imageUrl, userNotes, timestamps ...
) {
    fun aggregateIngredients(): List<IngredientSection>   // per instruction section
    fun aggregateAllIngredients(): List<Ingredient>       // across all sections
}
```

#### InstructionSection / IngredientSection

Support for grouped recipes (e.g., cake with frosting). `InstructionSection` holds named steps, each with its own `ingredients` and a `yields` multiplier; `IngredientSection` is the aggregated view:

```kotlin
data class IngredientSection(
    val name: String?,        // "For the frosting" or null for single-section
    val ingredients: List<Ingredient>
)
```

#### Ingredient

Parsed ingredient with scaling support:

```kotlin
data class Ingredient(
    val name: String,
    val notes: String?,       // "room temperature", "divided"
    val alternates: List<Ingredient>,
    val amount: Amount?,      // value (decimal) + unit
    val density: Double?,     // enables volume <-> weight conversion
    val optional: Boolean
) {
    fun format(scale: Double = 1.0): String
}
```

**Quantity Formatting**: The `format()` method converts decimals back to fractions:
- `0.5` → `1/2`
- `0.25` → `1/4`
- `2.5` → `2 1/2`

#### Units and Aggregation

- `Units.kt` defines unit categories and conversions to base units (weight, volume, count).
- `IngredientAggregation.kt` combines amounts: `AmountAccumulator` sums amounts in base units, `aggregateIngredientOccurrences` merges repeated ingredients (respecting step yields), `sumIngredientAmounts` and `formatIngredientTotal` produce totals for display. Grocery lists and the recipe detail "all ingredients" view share this code.

#### Markdown layouts

`RecipeMarkdownFormatter` has two layouts: the share layout (`format`, readable text for ACTION_SEND) and the edit layout (`formatBody`, used by the edit screen and parsed back by the AI, including density hints).

### Use Cases

#### ImportRecipeUseCase

Orchestrates the full import flow:

```
1. Validate API key exists
2. Fetch HTML from URL (WebScraperService)
3. Extract JSON-LD + structured text (RecipeHtmlExtractor)
4. Parse with AI (AnthropicService via ParseHtmlUseCase)
5. Create Recipe domain object
6. Save to Firestore (and download the image)
```

Reports progress via callback for UI updates and returns `Outcome<Recipe>` (`Success(value)` or `Failure(RecipeError)`); `ParseHtmlUseCase`, `EditRecipeUseCase`, `RegenerateRecipeUseCase`, `ImportPaprikaUseCase` (`Outcome<ImportSummary>`), `ImportFromZipUseCase` and `ExportToZipUseCase` do the same. Other use cases: `ImportPaprikaUseCase` (batch Paprika import, runs in short resumable steps), `ImportFromZipUseCase` / `ExportToZipUseCase` / `ExportSingleRecipeUseCase` (`.lorecipes` files), `RegenerateRecipeUseCase`, `EditRecipeUseCase`, `AggregateGroceryListUseCase`, `CalculateIngredientUsageUseCase`.

#### Archive handling

`ZipImportHelper.readRecipeIndex` and `PaprikaParser.parseIndex` stream the archive to list recipes without loading everything into memory. `BoundedInputStream` / `ArchiveBudget` cap per-entry and total decompressed bytes (`ResourceLimits`) to guard against zip bombs.

#### GetTagsUseCase

Selects the top 10 tags that maximize recipe coverage (an ILP, with a greedy set cover fallback). This is the kind of business logic that justifies a use case class. Tags are compared with `tagKey` (trimmed, lowercase; `domain/model/Tags.kt`), which the recipe list and meal plan filters also use through `Recipe.hasTag` / `Recipe.matchesSearch`.

#### Edit and regenerate

`EditRecipeUseCase` (in place) and `RegenerateRecipeUseCase` apply the AI result with `Recipe.withRegeneratedContent(parsed, now)`, which copies only AI-owned fields (name, story, servings, times, instructions, equipment, tags). Every other field, including any added later, is preserved.

Simple CRUD operations (get all recipes, get by ID, delete) are handled by injecting `RecipeRepository` directly into ViewModels, since they don't require any orchestration or business logic beyond the repository call.

## Background Work

Long-running work runs in WorkManager workers extending `BaseRecipeWorker` (foreground notification, progress): `RecipeImportWorker`, `PaprikaImportWorker`, `ZipImportWorker`, `ZipExportWorker`, `RecipeEditWorker`, `RecipeRegenerateWorker`, and `LocalImageUploadWorker`.

### Work contracts

Each worker has a contract object next to it (`RecipeImportWork`, `PaprikaImportWork`, `ZipImportWork`, `ZipExportWork`, `RecipeEditWork`, `RecipeRegenerateWork`) with sealed `Progress` and `Outcome` types. Workers report through `toData()` and observers decode with the same contract (`decodeProgress` / `decodeOutcome`, or `WorkInfo.toWorkStatus(contract)` → `WorkStatus` `Missing | Queued | Running(progress) | Finished(outcome) | Cancelled`), so `when` over progress and outcomes is exhaustive and keys can't drift. Key and value strings are persisted by WorkManager and must not change (`WorkContractTest` pins the values earlier versions wrote). Failures are one `Failed(error: RecipeError, …)` outcome per worker (`BaseRecipeWorker.errorResult(FailureOutcome)`), written as the encoded error under `error` (`RecipeError.encode`, JSON with stable `@SerialName`s) plus the `result_type` older versions wrote (`no_api_key`, `no_source_url`, `error`). Output from older versions, which only has `result_type` and an English `error_message`, decodes to `NoApiKey` / `NoSourceUrl` / `Legacy(message)`. The import queue stores the same encoding in `pending_imports.errorMessage` (`RecipeErrorConverter`; old English messages read back as `Legacy`), so no schema change was needed.

Screens follow their own work with `WorkTracker`: the work ID lives in `SavedStateHandle` (so the screen shows the work again after process death; a restored ID whose work no longer exists is dropped) and the status is observed by ID with `getWorkInfoByIdFlow` + `flatMapLatest`, so work that finishes before anyone collects is still seen. `RecipeWorkScheduler.prepareEdit / prepareRegenerate / prepareExport` return a `PendingWork` whose ID is known before it is enqueued; `WorkTracker.start` records the ID first. UI state is derived (`combine` of local state and the tracked status) rather than written from observers; the one exception is finished work, which a collector reports once as an event and then stops tracking (see ViewModels). ViewModels see only decoded types, never `WorkInfo` or `WorkManager`.

### Import queue (`imports` package)

```
UI (AddRecipe / ImportSelection / RecipeList)
       │  startUrlImport / startPaprikaImport / startZipImport / retryImport / cancelImport
       ▼
ImportQueue ──> PendingImportRepository (queue row)
       │    └─> ImportInputStore (files/imports/<importId>/)
       ▼
RecipeWorkScheduler.enqueueImport(...)  ──> worker

ImportMonitor ── combine(pending rows, import WorkInfos) ──> inProgressRecipes / importState(importId)
       └── writes durable bits back to the rows (name/image, Paprika waiting state, failures; removes finished rows)
```

- `ImportQueue` starts, retries, cancels and dismisses imports. Starts run in `NonCancellable` and are serialized by `startMutex` (URL dedupe is check-then-insert). On startup it reconciles each in-progress row against WorkManager by request ID; rows whose work is missing (pruned, or never enqueued) are marked failed so they can be retried. URL dedupe only reuses an import whose work is still queued/running.
- `ImportMonitor` derives each import's `ImportState` (`Running(ImportStage)`, `Failed(message, reason)`, `Succeeded(recipeId)`, `Cancelled`) from its row plus its WorkInfo (`ImportWorkDecoder`). Live stages come from WorkInfo progress instead of being written to Room on every tick; the row only gets what must outlive a worker run: the page title / recipe name and image once known, `WAITING_FOR_BATCH` between Paprika polls, and terminal failures. `importState(importId)` follows the import's unique work, so `AddRecipeViewModel` still sees `Succeeded(recipeId)` after the row is removed, and shows a running import again after process death.
- `ImportSelectionViewModel` copies the picked/shared file into `ImportInputStore` when the screen opens (the import ID is kept in `SavedStateHandle`), so the screen and the workers never depend on a content URI grant surviving process death. The queue row is inserted only after all inputs are written.
- Cancelling an import cancels its unique work, then waits (up to 30 s) for the worker to release the import's in-process lock before cancelling any Paprika batch and deleting files.
- Paprika batch submission and `batch.json` are written in one `NonCancellable` block; batch results are saved under IDs derived from import ID + custom_id so a resumed run overwrites rather than duplicates.
- `PaprikaImportWorker` runs as a chain of short, self-rescheduling steps (`ImportPaprikaUseCase.runStep`). Batch state (custom_id to recipe mapping, batch ID) is saved in `batch.json` so a restarted worker resumes.
- Failed imports stay visible in the recipe list with Retry and Dismiss actions.
- `RecipeNotificationHelper` owns two channels: `recipe_import` (low-importance progress) and `recipe_import_results` (completed/failed imports). Tapping a notification delivers a `recipe_id` to `MainActivity.onNewIntent`.

## UI Layer

### Navigation

Single-Activity architecture with Compose Navigation. The start destination is always the recipe list:

```
RecipeListScreen ──┬──> RecipeDetailScreen ──> EditRecipeScreen
                   ├──> AddRecipeScreen ──> SettingsScreen / ImportSelectionScreen
                   ├──> MealPlanScreen ──> GroceryListScreen
                   └──> SettingsScreen ──> ImportDebugListScreen ──> ImportDebugDetailScreen
```

Destinations are type-safe routes (Navigation 2.10): `@Serializable` objects/data classes in `ui/navigation/Routes.kt` under `sealed interface Route` (`RecipeList`, `RecipeDetail(recipeId)`, `EditRecipe(recipeId)`, `AddRecipe(url?)`, `Settings`, `MealPlan`, `GroceryList(weekStart)` as an ISO date string with a `weekStartDate` accessor, `ImportDebugList`, `ImportDebugDetail(debugEntryId)`, `ImportSelection(importType: ImportType, uri)`). `NavGraph` registers them with `composable<Route.X>` and navigates with `navigate(Route.X(...))`; ViewModels read their arguments with `savedStateHandle.toRoute<Route.X>()`, and route comparisons use `destination.hasRoute<Route.X>()`. Navigation encodes arguments itself, so shared URLs with `%`, `#`, `?`, `&` or spaces round-trip exactly, and the back stack with its arguments is restored after process death (`RouteTest`). Results handed to a screen lower in the back stack go through typed `NavResult` keys stored in that entry's SavedStateHandle (`NavResult.EditSaved` for the edit confirmation on recipe detail, `NavResult.FilterByTag` for a tag tapped on recipe detail). A back stack entry's own SavedStateHandle is not the one Hilt gives its ViewModels, so the destination reads the result, passes it to the screen, and the screen hands it to its ViewModel and consumes it. ViewModel tests build handles with navigation-testing's `SavedStateHandle(route = ...)`, which needs Robolectric.

External intents are turned into `AppIntentEvent`s (`ImportUrl`, `ImportFile`, `OpenRecipe`, `NoUrlInSharedText`) sent on a `Channel` in `SharedIntentViewModel` and collected by `NavGraph`, which navigates accordingly. Manifest filters: `text/plain` SEND (shared URLs), zip/octet-stream SEND and VIEW (`.lorecipes`, `.paprikarecipes`). Sharing HTML or Markdown files is not a supported import path.

### Screens

#### RecipeListScreen

- Observes recipes plus in-progress/failed imports from `ImportMonitor`; Retry/Dismiss go to `ImportQueue`
- Search, tag and favorite filters are held in `SavedStateHandle` (survive process death); tag chips on the detail screen filter the list (via `NavResult.FilterByTag` and `RecipeListViewModel.showOnlyTag`)
- Swipe-to-dismiss for deletion

#### RecipeDetailScreen

- State is `Loading`, `NotFound` or `Loaded(recipe)`
- Hero image with Coil async loading (Firebase Storage fetcher)
- Metadata card (times, servings)
- Scale controls with +/- buttons
- Sectioned ingredients and instructions; cooking state (scale, measurement preference, used ingredients, highlighted step) lives in `SavedStateHandle`

#### AddRecipeScreen

- URL input with validation
- Starts the import via `ImportQueue` and shows `ImportMonitor.importState(importId)` (the import ID is kept in `SavedStateHandle`); stages: Queued → Starting → Fetching → Analyzing → Saving
- Success is the one-off `AddRecipeEvent.RecipeAdded(recipeId)` (also for an import that finished while the screen was gone); errors are shown as state and on the in-progress card (Retry/Dismiss)
- Redirects to Settings if no API key

#### EditRecipeScreen

- Image, title and URL changes are staged and applied on save without the AI; body edits or AI instructions enqueue `RecipeEditWorker`
- The edit and regenerate work are followed with `WorkTracker`s and folded into one `editState` (`Loading(@StringRes progress)`, `Error(UserMessage)`; a missing API key shows `EditUiState.NoApiKey`). A successful save is the one-off `EditRecipeEvent.Saved(newRecipeId)`, sent once and the work then untracked
- Save preserves metadata, Save as Copy creates a new recipe

#### ImportSelectionScreen

- `ImportSelectionViewModel` copies the file from navigation args into app storage, detects Paprika vs .lorecipes from the ZIP contents (anything else shows "This file isn't a Lion+Otter or Paprika recipe export."), lists recipes (marking duplicates), and starts the import through `ImportQueue`. Results are delivered as one-shot events collected by the screen, not callbacks.

#### MealPlanScreen / GroceryListScreen

- Meal plans are persisted in Firestore; the grocery list aggregates the selected week's meals and persists checked state per week in `GroceryListStateStore`
- A meal plan's `servings` is a scale multiplier, not a serving count. It and the recipe detail scale are displayed with `formatScaleMultiplier` (`ui/format`), e.g. "1½×", "0.33×"

#### SettingsScreen

- API key input with visibility toggle
- Key validation (must start with `sk-ant-`)
- Import and edit model selection, thinking toggle
- Secure storage via Tink-encrypted storage
- Account section: Google sign-in/out (with unsynced-changes warning), account deletion. One account operation runs at a time (`busyOp: AccountOp?` = `SIGNING_IN | SIGNING_OUT | DELETING_ACCOUNT`, claimed with `beginGoogleSignIn` / `beginDeleteAccount`). The screen requests the Google credential in its own scope (so the Activity context never reaches a ViewModel coroutine) and always hands back a `CredentialResult` (`Obtained | Cancelled | Failed`), `Cancelled` if its scope is cancelled
- An invalid or unsaved API key is `apiKeyError: UserMessage?` state under the field; toasts (setting write failures, sign-in/out and deletion outcomes) are events
- Backup/restore, unit and display preferences, import debugging

### ViewModels

All ViewModels use Hilt injection and expose:
- `StateFlow` for UI state
- Functions for user actions

Example pattern:

```kotlin
@HiltViewModel
class RecipeListViewModel @Inject constructor(
    private val recipeRepository: IRecipeRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val recipes: StateFlow<List<Recipe>> = recipeRepository.getAllRecipes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
```

Suspending mutations (DataStore writes, uploads, starting work, the meal-plan query before a recipe delete) run inside `withContext(NonCancellable)` so they finish even if `viewModelScope` is cancelled; UI state updates stay outside that block. Fire-and-forget repository writes (see the write contract under Repository) are just called: they complete on their own and report failures app-wide, so wrapping them in `NonCancellable` or try/catch is dead code.

One-off events (navigation, snackbars, toasts, undo offers) go through one `Channel<Event>(Channel.BUFFERED)` per ViewModel exposed as `events: Flow<Event> = channel.receiveAsFlow()`, never `MutableSharedFlow` + `tryEmit` (drops events with no collector) or state plus a `reset…()` callback. Screens collect with `CollectEvents(viewModel.events) { … }` (`ui/components/UserMessages.kt`), which only collects while the screen is STARTED, so events sent in the background are handled on return. Conditions that persist until something changes (loading, a validation error under a field, an import's failure) stay state. A snackbar shown for an event (e.g. the meal plan's Undo) is not restored after a configuration change. Work that finishes while the screen is gone is still reported once: the ViewModel collects its tracked status and sends the event, then stops tracking the work.

### Theme

Custom Material 3 theme with Lion+Otter branding:

- **Primary (Otter)**: Teal `#2A7B9B`
- **Secondary (Lion)**: Gold `#D4A03C`
- **Surfaces**: Warm off-whites
- **Dark mode**: Inverted with appropriate contrast

## Dependency Injection

Hilt modules provide:

### AppModule
- `Json` instance with lenient parsing
- `@ApplicationScope` `CoroutineScope` (SupervisorJob, Default dispatcher, an exception handler that logs with `ErrorLog`), used for app-lifetime work: repository listeners and pending writes, `SettingsDataStore`'s key loading, the import queue

### DatabaseModule
- `RecipeDatabase` singleton
- `PendingImportDao`, `ImportDebugDao`

### RepositoryModule
- Binds `IRecipeRepository` / `IMealPlanRepository` to their Firestore-backed implementations

### NetworkModule
- `HttpClient` with Ktor Android engine (page and image fetching)

### WorkerModule
- `WorkManager` (`HiltWorkerFactory` is wired in `LionOtterApp`)

### SchedulerModule
- Binds `ImageUploadScheduler` (data layer port) to `WorkManagerImageUploadScheduler`

## Data Flow

### Recipe Import Flow

```
User enters URL
       │
       ▼
AddRecipeViewModel ──> ImportQueue.startUrlImport()
       │
       ▼
RecipeWorkScheduler.enqueueImport() ──> RecipeImportWorker
       │
       ▼
ImportRecipeUseCase.execute()
       │
       ├──> SettingsDataStore.anthropicApiKey (check API key)
       │
       ├──> WebScraperService.fetchHtml() (download page)
       │
       ├──> RecipeHtmlExtractor.extract() (JSON-LD + structured text)
       │
       ├──> AnthropicService.parseRecipe() (AI extraction)
       │
       └──> RecipeRepository.saveRecipe() (persist)
              │
              ▼
         FirestoreService (users/{uid}/recipes/{id})
              │
              ▼
         Firestore local cache → sync to server
```

### Recipe Display Flow

```
RecipeListScreen renders
       │
       ▼
RecipeListViewModel.recipes (StateFlow)
       │
       ▼
RecipeRepository.getAllRecipes()
       │
       ▼
FirestoreFlows snapshot listener (re-subscribes on auth state / generation change)
       │
       ▼
Incremental decoding: RecipeDto → domain Recipe
       │
       ▼
ViewModel exposes StateFlow<List<Recipe>>
       │
       ▼
Compose observes and recomposes
```

## Future Considerations

### Firebase Cloud Sync (Implemented)
- Recipes and meal plans are stored directly in Firestore (`users/{userId}/recipes/{recipeId}`, `users/{userId}/mealPlans/{mealPlanId}`); Firestore's own offline persistence and listeners replace the former custom sync use case and periodic worker
- Everyone starts as a local-only guest (anonymous uid, Firestore network disabled); signing in with Google enables network sync and migrates guest data
- Signing out clears local Firestore data and returns to a fresh guest; the repositories follow via the Firestore `generation`
- Local images are uploaded to Firebase Storage after Google sign-in (`LocalImageUploadWorker`)

### Cooking Mode (partially implemented)
- Ingredient usage tracking and highlighted step exist on the detail screen (`CalculateIngredientUsageUseCase`)
- Track ingredient usage across steps via `IngredientReference`
- Pre-calculate "get out" list
- Step-by-step navigation with scaled quantities

### Recipe Sharing (implemented except diff view)
- Export as Markdown text or `.lorecipes` files; import from shared `.lorecipes` / Paprika files
- Diff view for recipe updates

### Version History
- Store multiple versions in folder structure
- Compare versions with diff
- Rollback capability

## Guardrails

Static checks and tests that catch whole classes of bugs. All run in `./ci-local.sh` and CI.

- **Compiler and lint warnings are errors** (`allWarningsAsErrors` in both modules, lint `warningsAsErrors`; lint's dependency-freshness checks are disabled so new releases don't break the build). Fix the warning; suppress only with a comment saying why.
- **Compose lint** (`com.slack.lint.compose:compose-lint-checks`): composables take a `modifier` parameter, follow the parameter order, and get ViewModels via default `hiltViewModel()` parameters. `GroceryListScreen` and `MealPlanScreen` still forward their ViewModel (suppressed until their state is hoisted).
- **detekt** (`./gradlew detektDebug`, with type resolution) runs a targeted rule set in `config/detekt/detekt.yml`: `SuspendFunSwallowedCancellation`, `InjectDispatcher`, `SwallowedException` (logging the exception counts as handling it; `catch (_: ...)` does not), `TooGenericExceptionThrown`, `UnsafeCallOnNullableType`. Existing findings are in `config/detekt/baseline-debug.xml`: hard-coded dispatchers, `!!` in `Units.kt`/`IngredientAggregation.kt`, and catch blocks the cancellation rule flags although they are fine (cleanup then rethrow, or log-and-continue inside `NonCancellable`). Don't add to it; regenerate it with `./gradlew :app:detektBaselineDebug` only after fixing entries. Use `detektDebug`; the plain `detekt` task (part of `check`) runs without type resolution or this baseline. detekt excludes generated sources, so `detektDebug` gets the compiled `BuildConfig` on its classpath (`detektBuildConfigClasses` in `app/build.gradle.kts`); its analysis also doesn't run the kotlinx.serialization compiler plugin, so call the reified `json.decodeFromString<T>()`/`encodeToString(value)` rather than the plugin-generated `T.serializer()`, or detekt reports "compiler errors found during analysis" and loses type information there.
- **Layer rules** (`LayerDependencyTest`): scans imports under `app/src/main/kotlin`. Domain may not use `android.*`, `androidx.*` (except `androidx.annotation`), `com.anthropic`, Firebase or `..data..`; UI may not use WorkManager or `..data.local..`; data may not use `..ui..`, `..worker..` or `..imports..`; `imports` and `worker` may not use `..ui..` (and `worker` not `..imports..`). `KNOWN_VIOLATIONS` lists today's exceptions; the test fails on new violations and on entries that are no longer needed, so the list only shrinks.
- **DTO drift** (`DtoFieldDriftTest`, `DomainFixturesTest`, `RecipeDtoRoundTripIntegrationTest`): every `Recipe` / `MealPlanEntry` field needs a DTO field, the `RecipeFields` / `MealPlanFields` constants must be keys Firestore actually writes, and the round-trip fixtures in `testutil/DomainFixtures.kt` must set every field (including nested ones) to a non-default value.
- **AI schema** (`RecipeResponseSchemaTest`): property names and required fields in `AnthropicService.RECIPE_RESPONSE_SCHEMA` must match the serializers of `RecipeParseResponse` and the classes it contains.
- **Edit/regenerate preservation** (`RecipeRegeneratedContentTest`): every non-AI field of `Recipe` survives `withRegeneratedContent`.
- **Persisted work data** (`WorkContractTest`, `PendingImportStatusTest`): every worker Progress/Outcome variant round-trips through its contract, and data and statuses written by earlier versions (pinned as literal strings) still decode.
- **Room migrations** (`RecipeDatabaseMigrationTest`): `MigrationTestHelper` runs `RecipeDatabaseMigrations` against the exported schemas in `app/schemas`. Add a step for every new migration.

## Testing Strategy

### Unit Tests
- `Ingredient.format()` - quantity formatting and scaling
- Repository mapping logic
- Ingredient aggregation and unit conversion
- Use case business logic

### Integration Tests (Future)
- Room database operations
- DataStore preferences
- Full import flow with mock HTTP

### UI Tests (Future)
- Compose UI tests for screens
- Accessibility checks
