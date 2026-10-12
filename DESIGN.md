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

Dependencies point inward: UI → Domain → Data. The domain layer has no dependencies on Android frameworks or external libraries (except kotlinx for serialization).

## Data Layer

### Local Storage

#### Room Database (`RecipeDatabase`)

Recipes and meal plans do **not** live in Room; they are stored in Firestore (see Repository below). Room holds only local, device-specific data in two tables:

- `PendingImportEntity` (`PendingImportDao`): the import queue shown on the recipe list (status, name, URL, WorkManager ID, failure message). Failed imports stay in the table so the list can offer Retry/Dismiss.
- `ImportDebugEntity` (`ImportDebugDao`): raw HTML, AI input/output and errors captured when import debugging is enabled. The DAO exposes lightweight summary queries (`getAllDebugSummaries`, `getDebugSummaryById`) and chunked content reads (`getContentLength`, `getContentChunk`) so large blobs are never loaded into a single cursor window.

**Design Decision**: Large debug payloads are read in chunks because a single multi-megabyte column exceeds Android's CursorWindow limit.

#### DataStore

`SettingsDataStore` stores user preferences:
- Anthropic API key, encrypted with Tink (`TinkEncryption`) and kept out of the plain DataStore
- `ai_model` (imports) and `edit_model` (edits), plus a `thinking` toggle
- Display, theme, keep-screen-on, measurement unit preferences (recipe and grocery), start of week, import debugging

`GroceryListStateStore` is a separate DataStore holding grocery list checked state, keyed per week start date. Old weeks are pruned on save.

#### Local files

- Local image cache (`ImageDownloadService`, `ImageSyncService`): images are cached on disk and uploaded to Firebase Storage. Images saved while signed out of Google stay local until `ImageSyncService.uploadPendingLocalImages()` runs.
- `ImportInputStore`: staged import inputs under `files/imports/<importId>/` (copied source file, selection, import kind, `batch.json` for Paprika batches). Orphans are cleaned up at startup.

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

- **Models**: `claude-opus-5-5` (default for imports), `claude-sonnet-5-5` (default for edits), `claude-haiku-4-5`, `claude-fable-5-1`. `normalizeModel()` maps legacy or unknown stored IDs to the closest supported family.
- **Structured output**: requests use a JSON schema (non-recursive, derived from `RecipeParseResponse`) so the response is valid JSON; `extractJson` remains as a fallback.
- **Streaming**: single-recipe parses use `createStreaming` so large `max_tokens` values do not hit HTTP timeouts; the stream is closed on coroutine cancellation.
- **Effort/thinking per model**: Haiku 4.5 uses `budget_tokens` thinking when enabled. Opus 5.5, Sonnet 5.5 and Fable 5.1 cannot disable thinking, so the "thinking" setting maps to effort (medium when on, low when off).
- **Server-side fallback**: supported models send the server-side fallback beta so overloaded requests fall back server-side.
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

- Both repositories re-subscribe when the auth state (`AuthService.authState`) or the Firestore instance generation (`FirestoreService.generation`) changes, so a sign-in, sign-out or local-data clear swaps the listener to the right user.
- `data/repository/FirestoreFlows.kt` provides the shared listener helpers: an error-tolerant snapshot `Flow` (errors are logged and reported instead of killing the collector) and incremental decoding that only re-decodes changed documents and skips undecodable ones.
- `ImportDebugRepository` wraps `ImportDebugDao`; `PendingImportRepository` wraps `PendingImportDao`.

### Authentication and Account Lifecycle

- `AuthService` resolves auth state synchronously at startup. `LionOtterApp` injects it eagerly so state is known (and Firestore network access is disabled for guests) before any Activity or WorkManager job touches Firestore; `MainActivity` does no auth work. There is no login screen: every user starts as a local-only **guest**, and `AuthState` is `Loading`, `Guest(uid)` or `Google(uid, email)`.
- Code that needs a uid off the main path calls `awaitUid()`.
- `signInWithGoogle` followed by `completeMergeSignIn` / `restoreAfterFailedMerge` handles migrating guest data (`AccountMigrationService`). After a successful Google sign-in, `LocalImageUploadWorker` is enqueued and calls `ImageSyncService.uploadPendingLocalImages()`.
- `signOut(force)` returns `SignOutResult.Success` or `SignOutResult.PendingWrites` when changes have not reached the server; Settings warns about unsynced changes and the user may sign out anyway (`force = true`).
- `AccountDeletionService` (depends on `FirestoreService`, `ImageSyncService`, `ImportDebugRepository`, `SettingsDataStore`) first checks the server is reachable and re-authenticates, reads recipes and meal plans from the server (not the cache), deletes Storage images individually, deletes Firestore documents, clears local images, debug data and the API key, and finally deletes the Firebase Auth account.

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

Reports progress via callback for UI updates. Other use cases: `ImportPaprikaUseCase` (batch Paprika import, runs in short resumable steps), `ImportFromZipUseCase` / `ExportToZipUseCase` / `ExportSingleRecipeUseCase` (`.lorecipes` files), `RegenerateRecipeUseCase`, `EditRecipeUseCase`, `AggregateGroceryListUseCase`, `CalculateIngredientUsageUseCase`.

#### Archive handling

`ZipImportHelper.readRecipeIndex` and `PaprikaParser.parseIndex` stream the archive to list recipes without loading everything into memory. `BoundedInputStream` / `ArchiveBudget` cap per-entry and total decompressed bytes (`ResourceLimits`) to guard against zip bombs.

#### GetTagsUseCase

Uses a greedy set cover algorithm to select the top 10 tags that maximize recipe coverage. This is the kind of business logic that justifies a use case class.

Simple CRUD operations (get all recipes, get by ID, delete) are handled by injecting `RecipeRepository` directly into ViewModels, since they don't require any orchestration or business logic beyond the repository call.

## Background Work

Long-running work runs in WorkManager workers extending `BaseRecipeWorker` (foreground notification, progress): `RecipeImportWorker`, `PaprikaImportWorker`, `ZipImportWorker`, `ZipExportWorker`, `RecipeEditWorker`, `RecipeRegenerateWorker`, and `LocalImageUploadWorker`.

Imports flow through `InProgressRecipeManager` (UI state layer):

```
UI (AddRecipe / ImportSelection)
       │  startUrlImport / startPaprikaImport / startZipImport
       ▼
InProgressRecipeManager ──> PendingImportRepository (queue row)
       │                 └─> ImportInputStore (files/imports/<importId>/)
       ▼
RecipeWorkScheduler.enqueueImport(...)  ──> worker
       ▲
       └── observes WorkInfo, updates status; retryImport / cancelImport / removeInProgressRecipe
```

- Input files are copied into `ImportInputStore` so workers do not depend on a content URI staying readable.
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

External intents are turned into `AppIntentEvent`s (`ImportUrl`, `ImportFile`, `OpenRecipe`, `NoUrlInSharedText`) sent on a `Channel` in `SharedIntentViewModel` and collected by `NavGraph`, which navigates accordingly. Manifest filters: `text/plain` SEND (shared URLs), zip/octet-stream SEND and VIEW (`.lorecipes`, `.paprikarecipes`). Sharing HTML or Markdown files is not a supported import path.

### Screens

#### RecipeListScreen

- Observes recipes plus in-progress/failed imports from `InProgressRecipeManager`
- Search, tag and favorite filters are held in `SavedStateHandle` (survive process death); tag chips on the detail screen filter the list
- Swipe-to-dismiss for deletion

#### RecipeDetailScreen

- State is `Loading`, `NotFound` or `Loaded(recipe)`
- Hero image with Coil async loading (Firebase Storage fetcher)
- Metadata card (times, servings)
- Scale controls with +/- buttons
- Sectioned ingredients and instructions; cooking state (scale, measurement preference, used ingredients, highlighted step) lives in `SavedStateHandle`

#### AddRecipeScreen

- URL input with validation
- Starts the import via `InProgressRecipeManager`; progress states: Fetching → Parsing → Saving
- Errors surface on the in-progress card (Retry/Dismiss)
- Redirects to Settings if no API key

#### EditRecipeScreen

- Image, title and URL changes are staged and applied on save without the AI; body edits or AI instructions enqueue `RecipeEditWorker`
- Save preserves metadata, Save as Copy creates a new recipe

#### ImportSelectionScreen

- `ImportSelectionViewModel` parses the picked file from navigation args in `init`, lists recipes (marking duplicates), and starts the import through `InProgressRecipeManager`

#### MealPlanScreen / GroceryListScreen

- Meal plans are persisted in Firestore; the grocery list aggregates the selected week's meals and persists checked state per week in `GroceryListStateStore`

#### SettingsScreen

- API key input with visibility toggle
- Key validation (must start with `sk-ant-`)
- Import and edit model selection, thinking toggle
- Secure storage via Tink-encrypted storage
- Account section: Google sign-in/out (with unsynced-changes warning), account deletion
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

Mutations (writes, deletes, uploads) run inside `withContext(NonCancellable)` so they finish even if `viewModelScope` is cancelled; UI state updates stay outside that block.

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
- Application-level `CoroutineScope`

### DatabaseModule
- `RecipeDatabase` singleton
- `PendingImportDao`, `ImportDebugDao`

### RepositoryModule
- Binds `IRecipeRepository` / `IMealPlanRepository` to their Firestore-backed implementations

### NetworkModule
- `HttpClient` with Ktor Android engine (page and image fetching)

### WorkerModule
- `WorkManager` (`HiltWorkerFactory` is wired in `LionOtterApp`)

## Data Flow

### Recipe Import Flow

```
User enters URL
       │
       ▼
AddRecipeViewModel ──> InProgressRecipeManager.startUrlImport()
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
- Navigation tests
- Accessibility checks
