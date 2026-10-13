package com.lionotter.recipes.testutil

import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.RecipeIdAndName
import com.lionotter.recipes.domain.model.Settings
import com.lionotter.recipes.domain.model.StartOfWeek
import com.lionotter.recipes.domain.model.ThemeMode
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.port.AppSettings
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
class FakeAppSettings(
    apiKey: String? = "sk-ant-test",
    unitSystem: UnitSystem = UnitSystem.METRIC
) : AppSettings {
    val apiKeyFlow = MutableStateFlow(apiKey)
    val aiModelFlow = MutableStateFlow(AiModels.DEFAULT_MODEL)
    val editModelFlow = MutableStateFlow(AiModels.DEFAULT_EDIT_MODEL)
    val thinkingFlow = MutableStateFlow(AiModels.DEFAULT_THINKING_ENABLED)
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

    var writeError: Throwable? = null

    override val anthropicApiKey: Flow<String?> = apiKeyFlow
    override val aiModel: Flow<String> = aiModelFlow
    override val thinkingEnabled: Flow<Boolean> = thinkingFlow
    override val importDebuggingEnabled: Flow<Boolean> = importDebuggingFlow
    override val editModel: Flow<String> = editModelFlow
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

    override val settings: Flow<Settings> = combine(
        listOf<Flow<Any?>>(
            apiKeyFlow, aiModelFlow, editModelFlow, thinkingFlow, keepScreenOnFlow, themeModeFlow,
            volumeUnitSystem, weightUnitSystem, groceryVolumeUnitSystem, groceryWeightUnitSystem,
            startOfWeekFlow, importDebuggingFlow, volumeKeysScrollFlow
        )
    ) { values ->
        Settings(
            anthropicApiKey = values[0] as String?,
            aiModel = values[1] as String,
            editModel = values[2] as String,
            thinkingEnabled = values[3] as Boolean,
            keepScreenOn = values[4] as Boolean,
            themeMode = values[5] as ThemeMode,
            volumeUnitSystem = values[6] as UnitSystem,
            weightUnitSystem = values[7] as UnitSystem,
            groceryVolumeUnitSystem = values[8] as UnitSystem,
            groceryWeightUnitSystem = values[9] as UnitSystem,
            startOfWeek = values[10] as StartOfWeek,
            importDebuggingEnabled = values[11] as Boolean,
            volumeKeysScroll = values[12] as Boolean
        )
    }

    private fun <T> write(flow: MutableStateFlow<T>, value: T) {
        writeError?.let { throw it }
        flow.value = value
    }

    override suspend fun setAnthropicApiKey(apiKey: String) = write(apiKeyFlow, apiKey)
    override suspend fun clearAnthropicApiKey() = write(apiKeyFlow, null)
    override suspend fun setAiModel(model: String) = write(aiModelFlow, model)
    override suspend fun setEditModel(model: String) = write(editModelFlow, model)
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
