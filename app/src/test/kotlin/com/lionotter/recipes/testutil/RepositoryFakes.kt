package com.lionotter.recipes.testutil

import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.model.ModelRole
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.RecipeIdAndName
import com.lionotter.recipes.domain.model.Settings
import com.lionotter.recipes.domain.model.StartOfWeek
import com.lionotter.recipes.domain.model.ThemeMode
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.port.AppSettings
import com.lionotter.recipes.domain.port.TagPreferences
import com.lionotter.recipes.domain.model.TagBarSize
import com.lionotter.recipes.domain.port.GroceryListStateStore
import com.lionotter.recipes.domain.port.SavedGroceryListState
import com.lionotter.recipes.domain.repository.MealPlanRepository
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.util.CurrentDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.datetime.LocalDate

/** In-memory fakes of the repositories and settings, backed by [MutableStateFlow]s. */

class FakeRecipeRepository(vararg initial: Recipe) : RecipeRepository {
    val recipes = MutableStateFlow(initial.associateBy { it.id })
    val saved = mutableListOf<Recipe>()
    val deleted = mutableListOf<String>()
    val favoriteUpdates = mutableListOf<Pair<String, Boolean>>()
    val notesUpdates = mutableListOf<Pair<String, String?>>()
    val imageUrlUpdates = mutableListOf<Pair<String, String?>>()
    val tagUpdates = mutableListOf<Pair<String, List<String>>>()

    /** When set, [getAllRecipeTags] fails with it. */
    var tagsError: Throwable? = null

    /** Runs at the start of [getAllRecipeTags], e.g. to hold it until a test is ready. */
    var beforeTagsRead: suspend () -> Unit = {}

    /** Overrides the count, to test the recipe limit without creating that many recipes. */
    var countOverride: Int? = null

    /** How many collectors [getAllRecipes] currently has. */
    var allRecipesSubscribers = 0
        private set

    override fun getAllRecipes(): Flow<List<Recipe>> = recipes.map { it.values.toList() }
        .onStart { allRecipesSubscribers++ }
        .onCompletion { allRecipesSubscribers-- }
    override fun getRecipeById(id: String): Flow<Recipe?> = recipes.map { it[id] }
    override suspend fun getRecipeByIdOnce(id: String): Recipe? = recipes.value[id]

    override fun saveRecipe(recipe: Recipe) {
        saved += recipe
        recipes.value += recipe.id to recipe
    }

    override fun deleteRecipe(id: String) {
        deleted += id
        recipes.value -= id
    }

    override fun setFavorite(id: String, isFavorite: Boolean) {
        favoriteUpdates += id to isFavorite
        update(id) { it.copy(isFavorite = isFavorite) }
    }

    override fun setUserNotes(id: String, userNotes: String?) {
        notesUpdates += id to userNotes
        update(id) { it.copy(userNotes = userNotes) }
    }

    override fun setImagePrompt(id: String, imagePrompt: String?) {
        update(id) { it.copy(imagePrompt = imagePrompt) }
    }

    override fun setImageUrl(id: String, imageUrl: String?) {
        imageUrlUpdates += id to imageUrl
        update(id) { it.copy(imageUrl = imageUrl) }
    }

    override fun updateTitleAndUrl(id: String, name: String, sourceUrl: String?) =
        update(id) { it.copy(name = name, sourceUrl = sourceUrl) }

    override fun setTags(id: String, tags: List<String>) {
        tagUpdates += id to tags
        update(id) { it.copy(tags = tags) }
    }

    override suspend fun getAllRecipeIdsAndNames(): List<RecipeIdAndName> =
        recipes.value.values.map { RecipeIdAndName(it.id, it.name) }

    override suspend fun getAllRecipeTags(): Map<String, List<String>> {
        beforeTagsRead()
        tagsError?.let { throw it }
        return recipes.value.mapValues { it.value.tags }
    }

    override suspend fun getRecipeCount(): Int = countOverride ?: recipes.value.size

    /** When set, the image queries ([isImageReferenced], [getImageUrlsByRecipeId], [replaceImageUrl]) fail with it. */
    var imageQueryError: Throwable? = null

    /** Runs inside [replaceImageUrl] before it compares, e.g. to change the image concurrently. */
    var beforeReplaceImageUrl: (id: String) -> Unit = {}

    override suspend fun isImageReferenced(imageUrl: String, excludingRecipeId: String?): Boolean {
        imageQueryError?.let { throw it }
        return recipes.value.values.any { it.imageUrl == imageUrl && it.id != excludingRecipeId }
    }

    override suspend fun getImageUrl(id: String): String? {
        imageQueryError?.let { throw it }
        return recipes.value[id]?.imageUrl
    }

    override suspend fun getImageUrlsByRecipeId(localOnly: Boolean): Map<String, String> {
        imageQueryError?.let { throw it }
        return recipes.value.values.mapNotNull { recipe -> recipe.imageUrl?.let { recipe.id to it } }.toMap()
    }

    override suspend fun replaceImageUrl(id: String, expected: String, replacement: String): Boolean {
        imageQueryError?.let { throw it }
        beforeReplaceImageUrl(id)
        if (recipes.value[id]?.imageUrl != expected) return false
        update(id) { it.copy(imageUrl = replacement) }
        return true
    }

    private fun update(id: String, change: (Recipe) -> Recipe) {
        recipes.value[id]?.let { recipes.value += id to change(it) }
    }
}

class FakeMealPlanRepository(vararg initial: MealPlanEntry) : MealPlanRepository {
    val entries = MutableStateFlow(initial.associateBy { it.id })

    /** When set, [getMealPlansForDateRange] fails with it. */
    var loadError: Throwable? = null

    /** When set, [deleteMealPlansByRecipeId] fails with it. */
    var deleteByRecipeError: Throwable? = null

    override fun getMealPlansForDateRange(startDate: LocalDate, endDate: LocalDate): Flow<List<MealPlanEntry>> {
        return flow {
            loadError?.let { throw it }
            emitAll(entries.map { all -> all.values.filter { it.date in startDate..endDate } })
        }
    }

    override fun saveMealPlan(entry: MealPlanEntry) {
        entries.value += entry.id to entry
    }

    override fun updateMealPlan(entry: MealPlanEntry) {
        entries.value += entry.id to entry
    }

    override fun deleteMealPlan(id: String) {
        entries.value -= id
    }

    val deletedForRecipes = mutableListOf<String>()

    override suspend fun deleteMealPlansByRecipeId(recipeId: String) {
        deleteByRecipeError?.let { throw it }
        deletedForRecipes += recipeId
        entries.value = entries.value.filterValues { it.recipeId != recipeId }
    }

    override suspend fun countMealPlansByRecipeId(recipeId: String): Int =
        entries.value.values.count { it.recipeId == recipeId }

    override suspend fun countMealPlansByRecipeIds(recipeIds: List<String>): Int =
        entries.value.values.count { it.recipeId in recipeIds }

    override suspend fun getAllMealPlansOnce(): List<MealPlanEntry> = entries.value.values.toList()

    /** Runs at the start of [getMealPlanCount], e.g. to hold it until a test is ready. */
    var beforeCount: suspend () -> Unit = {}
    var countCalls = 0

    override suspend fun getMealPlanCount(): Int {
        countCalls++
        beforeCount()
        return entries.value.size
    }

    override suspend fun replaceRecipeImageUrl(oldUrl: String, newUrl: String) {
        entries.value = entries.value.mapValues { (_, entry) ->
            if (entry.recipeImageUrl == oldUrl) entry.copy(recipeImageUrl = newUrl) else entry
        }
    }
}

/** Settings in memory. Set [writeError] to make every write fail. */
class FakeTagPreferences(favorites: Set<String> = emptySet()) : TagPreferences {
    val favoriteTagsFlow = MutableStateFlow(favorites)
    val tagBarSizeFlow = MutableStateFlow(TagBarSize.DEFAULT)

    var writeError: Throwable? = null

    override val favoriteTags: Flow<Set<String>> = favoriteTagsFlow
    override val tagBarSize: Flow<Int> = tagBarSizeFlow

    override suspend fun updateFavoriteTags(transform: (Set<String>) -> Set<String>) {
        writeError?.let { throw it }
        favoriteTagsFlow.value = transform(favoriteTagsFlow.value)
    }

    override suspend fun setTagBarSize(size: Int) {
        writeError?.let { throw it }
        tagBarSizeFlow.value = TagBarSize.coerce(size)
    }
}

/** [apiKeyFlow] is the Anthropic key; the model flows are the user's picks (null for the defaults). */
class FakeAppSettings(
    apiKey: String? = "sk-ant-test",
    unitSystem: UnitSystem = UnitSystem.METRIC,
    val tagPreferences: FakeTagPreferences = FakeTagPreferences()
) : AppSettings, TagPreferences by tagPreferences {
    val apiKeyFlow = MutableStateFlow(apiKey)
    val openAiApiKeyFlow = MutableStateFlow<String?>(null)
    val aiModelFlow = MutableStateFlow<String?>(null)
    val editModelFlow = MutableStateFlow<String?>(null)
    val imageModelFlow = MutableStateFlow<String?>(null)
    val thinkingFlow = MutableStateFlow(AiProviders.DEFAULT_THINKING_ENABLED)
    val importDebuggingFlow = MutableStateFlow(false)
    val keepScreenOnFlow = MutableStateFlow(true)
    val volumeKeysScrollFlow = MutableStateFlow(false)
    val themeModeFlow = MutableStateFlow(ThemeMode.AUTO)
    val volumeUnitSystemFlow = MutableStateFlow(unitSystem)
    val weightUnitSystemFlow = MutableStateFlow(unitSystem)

    /** Null means "not set", so the recipe setting is used, as in the real store. */
    val groceryVolumeUnitSystemFlow = MutableStateFlow<UnitSystem?>(null)
    val groceryWeightUnitSystemFlow = MutableStateFlow<UnitSystem?>(null)
    val startOfWeekFlow = MutableStateFlow(StartOfWeek.LOCALE_DEFAULT)
    val imageGenerationFlow = MutableStateFlow(true)

    var writeError: Throwable? = null

    private fun keyFlow(providerId: String) = when (providerId) {
        AiProviders.ANTHROPIC_ID -> apiKeyFlow
        AiProviders.OPENAI_ID -> openAiApiKeyFlow
        else -> error("Unknown provider $providerId")
    }

    private fun modelFlow(role: ModelRole) = when (role) {
        ModelRole.IMPORT -> aiModelFlow
        ModelRole.EDIT -> editModelFlow
        ModelRole.IMAGE -> imageModelFlow
    }

    override val apiKeys: Flow<Map<String, String>> = combine(apiKeyFlow, openAiApiKeyFlow, ::fakeApiKeys)
    override fun selectedModel(role: ModelRole): Flow<String?> = modelFlow(role)
    override val thinkingEnabled: Flow<Boolean> = thinkingFlow
    override val importDebuggingEnabled: Flow<Boolean> = importDebuggingFlow
    override val keepScreenOn: Flow<Boolean> = keepScreenOnFlow
    override val volumeKeysScroll: Flow<Boolean> = volumeKeysScrollFlow
    override val themeMode: Flow<ThemeMode> = themeModeFlow
    override val volumeUnitSystem: Flow<UnitSystem> = volumeUnitSystemFlow
    override val weightUnitSystem: Flow<UnitSystem> = weightUnitSystemFlow
    override val groceryVolumeUnitSystem: Flow<UnitSystem> =
        combine(groceryVolumeUnitSystemFlow, volumeUnitSystemFlow) { grocery, recipe ->
            grocery ?: recipe
        }
    override val groceryWeightUnitSystem: Flow<UnitSystem> =
        combine(groceryWeightUnitSystemFlow, weightUnitSystemFlow) { grocery, recipe ->
            grocery ?: recipe
        }
    override val startOfWeek: Flow<StartOfWeek> = startOfWeekFlow
    override val imageGenerationEnabled: Flow<Boolean> = imageGenerationFlow

    override val settings: Flow<Settings> = combine(
        listOf<Flow<Any?>>(
            apiKeys, aiModelFlow, editModelFlow, imageModelFlow, thinkingFlow, keepScreenOnFlow, themeModeFlow,
            volumeUnitSystem, weightUnitSystem, groceryVolumeUnitSystem, groceryWeightUnitSystem,
            startOfWeekFlow, importDebuggingFlow, volumeKeysScrollFlow, tagPreferences.tagBarSizeFlow,
            imageGenerationFlow
        )
    ) { values ->
        @Suppress("UNCHECKED_CAST") // the first value is apiKeys
        val keys = values[0] as Map<String, String>
        val selected = listOf(ModelRole.IMPORT, ModelRole.EDIT, ModelRole.IMAGE).zip(values.slice(1..3)).toMap()
        Settings(
            apiKeys = keys,
            models = ModelRole.entries.mapNotNull { role ->
                AiProviders.resolve(role, selected[role] as String?, keys)?.let { role to it.model }
            }.toMap(),
            thinkingEnabled = values[4] as Boolean,
            keepScreenOn = values[5] as Boolean,
            themeMode = values[6] as ThemeMode,
            volumeUnitSystem = values[7] as UnitSystem,
            weightUnitSystem = values[8] as UnitSystem,
            groceryVolumeUnitSystem = values[9] as UnitSystem,
            groceryWeightUnitSystem = values[10] as UnitSystem,
            startOfWeek = values[11] as StartOfWeek,
            importDebuggingEnabled = values[12] as Boolean,
            volumeKeysScroll = values[13] as Boolean,
            tagBarSize = values[14] as Int,
            imageGenerationEnabled = values[15] as Boolean
        )
    }

    private fun <T> write(flow: MutableStateFlow<T>, value: T) {
        writeError?.let { throw it }
        flow.value = value
    }

    override suspend fun setApiKey(providerId: String, apiKey: String) = write(keyFlow(providerId), apiKey)

    /** Like the real store, also forgets the picks of the provider's models. */
    override suspend fun clearApiKey(providerId: String) {
        write(keyFlow(providerId), null)
        for (role in ModelRole.entries) {
            val flow = modelFlow(role)
            if (AiProviders.findModel(flow.value)?.providerId == providerId) flow.value = null
        }
    }

    override suspend fun setImageGenerationEnabled(enabled: Boolean) = write(imageGenerationFlow, enabled)
    override suspend fun setSelectedModel(role: ModelRole, modelId: String) = write(modelFlow(role), modelId)
    override suspend fun setThinkingEnabled(enabled: Boolean) = write(thinkingFlow, enabled)
    override suspend fun setKeepScreenOn(enabled: Boolean) = write(keepScreenOnFlow, enabled)
    override suspend fun setVolumeKeysScroll(enabled: Boolean) = write(volumeKeysScrollFlow, enabled)
    override suspend fun setThemeMode(mode: ThemeMode) = write(themeModeFlow, mode)
    override suspend fun setVolumeUnitSystem(system: UnitSystem) = write(volumeUnitSystemFlow, system)
    override suspend fun setWeightUnitSystem(system: UnitSystem) = write(weightUnitSystemFlow, system)
    override suspend fun setGroceryVolumeUnitSystem(system: UnitSystem) = write(groceryVolumeUnitSystemFlow, system)
    override suspend fun setGroceryWeightUnitSystem(system: UnitSystem) = write(groceryWeightUnitSystemFlow, system)
    override suspend fun setStartOfWeek(startOfWeek: StartOfWeek) = write(startOfWeekFlow, startOfWeek)
    override suspend fun setImportDebuggingEnabled(enabled: Boolean) = write(importDebuggingFlow, enabled)
}

class FakeGroceryListStateStore : GroceryListStateStore {
    val states = mutableMapOf<LocalDate, SavedGroceryListState>()

    /** Every state saved, in order. */
    val saves = mutableListOf<Pair<LocalDate, SavedGroceryListState>>()

    override suspend fun load(weekStart: LocalDate): SavedGroceryListState? = states[weekStart]

    override suspend fun save(weekStart: LocalDate, state: SavedGroceryListState) {
        saves += weekStart to state
        states[weekStart] = state
    }
}

/** A date that only changes when a test sets [date]. */
class FakeCurrentDate(initial: LocalDate) : CurrentDate {
    val date = MutableStateFlow(initial)
    override fun today(): LocalDate = date.value
    override val dates: Flow<LocalDate> = date
}
