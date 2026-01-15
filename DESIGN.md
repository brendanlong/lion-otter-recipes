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

Single table design with JSON-serialized complex fields:

```kotlin
@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sourceUrl: String?,
    val ingredientSectionsJson: String,  // Serialized List<IngredientSection>
    val instructionSectionsJson: String, // Serialized List<InstructionSection>
    val tagsJson: String,                // Serialized List<String>
    val originalHtml: String?,           // Preserved for re-parsing
    // ... other fields
)
```

**Design Decision**: We serialize nested lists to JSON rather than using Room relations because:
1. Recipe structure is always loaded as a unit
2. Avoids complex JOIN queries
3. Simplifies schema migrations
4. Original HTML preserved for potential AI re-processing

#### DataStore (`SettingsDataStore`)

Stores user preferences:
- `anthropic_api_key`: Encrypted API key for Claude
- `ai_model`: Selected model (opus-4.5, sonnet-4, haiku-3.5)

### Remote Services

#### WebScraperService

Simple HTTP client that fetches raw HTML from recipe URLs:

```kotlin
suspend fun fetchHtml(url: String): Result<String>
```

- Uses mobile User-Agent to get mobile-friendly pages
- No parsing - raw HTML passed to AI

#### AnthropicService

Calls Claude API to parse recipe HTML into structured data:

```kotlin
suspend fun parseRecipe(
    html: String,
    apiKey: String,
    model: String
): Result<RecipeParseResult>
```

**Prompt Engineering**: The system prompt instructs Claude to:
1. Extract all recipe metadata (name, times, servings)
2. Group ingredients and instructions by section
3. Parse quantities as decimals for scaling
4. Generate relevant tags
5. Return strict JSON format

**Response Handling**:
- Extracts JSON from markdown code blocks if present
- Falls back to finding raw JSON in response
- Validates against `RecipeParseResult` schema

### Repository

`RecipeRepository` is the single source of truth:

```kotlin
class RecipeRepository(
    private val recipeDao: RecipeDao,
    private val json: Json
) {
    fun getAllRecipes(): Flow<List<Recipe>>
    fun getRecipeById(id: String): Flow<Recipe?>
    suspend fun saveRecipe(recipe: Recipe, originalHtml: String?)
    suspend fun deleteRecipe(id: String)
}
```

Handles JSON serialization/deserialization between `RecipeEntity` and domain `Recipe`.

## Domain Layer

### Models

#### Recipe

Core domain model representing a complete recipe:

```kotlin
data class Recipe(
    val id: String,
    val name: String,
    val sourceUrl: String?,
    val story: String?,                           // AI-extracted summary
    val servings: Int?,
    val ingredientSections: List<IngredientSection>,
    val instructionSections: List<InstructionSection>,
    val tags: List<String>,
    // ...
)
```

#### IngredientSection / InstructionSection

Support for grouped recipes (e.g., cake with frosting):

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
    val quantity: Double?,    // Decimal for math (0.5, 0.25, etc.)
    val unit: String?,
    val notes: String?        // "room temperature", "divided"
) {
    fun format(scale: Double = 1.0): String
}
```

**Quantity Formatting**: The `format()` method converts decimals back to fractions:
- `0.5` → `1/2`
- `0.25` → `1/4`
- `2.5` → `2 1/2`

### Use Cases

#### ImportRecipeUseCase

Orchestrates the full import flow:

```
1. Validate API key exists
2. Fetch HTML from URL (WebScraperService)
3. Parse with AI (AnthropicService)
4. Create Recipe domain object
5. Save to database with original HTML
```

Reports progress via callback for UI updates.

#### GetRecipesUseCase / GetRecipeByIdUseCase

Simple data access with filtering:
- All recipes (sorted by update time)
- By tag
- By search query

## UI Layer

### Navigation

Single-Activity architecture with Compose Navigation:

```
RecipeListScreen ──┬──> RecipeDetailScreen
                   ├──> AddRecipeScreen ──> SettingsScreen
                   └──> SettingsScreen
```

### Screens

#### RecipeListScreen

- Observes `Flow<List<Recipe>>` from ViewModel
- Search bar filters locally
- Tag chips filter by category
- Swipe-to-dismiss for deletion

#### RecipeDetailScreen

- Hero image with Coil async loading
- Metadata card (times, servings)
- Scale controls with +/- buttons
- Sectioned ingredients and instructions

#### AddRecipeScreen

- URL input with validation
- Progress states: Fetching → Parsing → Saving
- Error handling with retry
- Redirects to Settings if no API key

#### SettingsScreen

- API key input with visibility toggle
- Key validation (must start with `sk-ant-`)
- Model selection dropdown
- Secure storage via DataStore

### ViewModels

All ViewModels use Hilt injection and expose:
- `StateFlow` for UI state
- Functions for user actions

Example pattern:

```kotlin
@HiltViewModel
class RecipeListViewModel @Inject constructor(
    private val getRecipesUseCase: GetRecipesUseCase
) : ViewModel() {

    val recipes: StateFlow<List<Recipe>> = getRecipesUseCase.execute()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
```

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

### DatabaseModule
- `RecipeDatabase` singleton
- `RecipeDao` from database

### NetworkModule
- `HttpClient` with Ktor Android engine
- 2-minute timeout for AI calls
- Content negotiation for JSON

## Data Flow

### Recipe Import Flow

```
User enters URL
       │
       ▼
AddRecipeViewModel.importRecipe()
       │
       ▼
ImportRecipeUseCase.execute()
       │
       ├──> SettingsDataStore.anthropicApiKey (check API key)
       │
       ├──> WebScraperService.fetchHtml() (download page)
       │
       ├──> AnthropicService.parseRecipe() (AI extraction)
       │
       └──> RecipeRepository.saveRecipe() (persist)
              │
              ▼
         RecipeDao.insertRecipe()
              │
              ▼
         Room Database
```

### Recipe Display Flow

```
RecipeListScreen renders
       │
       ▼
RecipeListViewModel.recipes (StateFlow)
       │
       ▼
GetRecipesUseCase.execute()
       │
       ▼
RecipeRepository.getAllRecipes()
       │
       ▼
RecipeDao.getAllRecipes() (Flow)
       │
       ▼
Room Database → Flow<List<RecipeEntity>>
       │
       ▼
Repository maps to Flow<List<Recipe>>
       │
       ▼
ViewModel exposes StateFlow<List<Recipe>>
       │
       ▼
Compose observes and recomposes
```

## Future Considerations

### Google Drive Sync
- Store recipes as `{name}-v{n}/recipe.json` + `original.html`
- Sync on app launch, background periodic sync
- Conflict resolution: latest timestamp wins

### Cooking Mode
- Track ingredient usage across steps via `IngredientReference`
- Pre-calculate "get out" list
- Step-by-step navigation with scaled quantities

### Recipe Sharing
- Export to JSON/Markdown
- Import from shared files
- Diff view for recipe updates

### Version History
- Store multiple versions in folder structure
- Compare versions with diff
- Rollback capability

## Testing Strategy

### Unit Tests
- `Ingredient.format()` - quantity formatting and scaling
- Repository mapping logic
- Use case business logic

### Integration Tests (Future)
- Room database operations
- DataStore preferences
- Full import flow with mock HTTP

### UI Tests (Future)
- Compose UI tests for screens
- Navigation tests
- Accessibility checks
