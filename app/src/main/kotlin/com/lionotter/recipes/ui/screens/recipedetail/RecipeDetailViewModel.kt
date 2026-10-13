package com.lionotter.recipes.ui.screens.recipedetail

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.IngredientUsageStatus
import com.lionotter.recipes.domain.model.InstructionIngredientKey
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.model.createInstructionIngredientKey
import com.lionotter.recipes.domain.port.AppSettings
import com.lionotter.recipes.domain.repository.MealPlanRepository
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.domain.usecase.CalculateIngredientUsageUseCase
import com.lionotter.recipes.domain.usecase.ExportSingleRecipeUseCase
import com.lionotter.recipes.domain.util.RecipeSerializer
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.ui.navigation.Route
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.util.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Represents the location of a highlighted instruction step.
 */
data class HighlightedInstructionStep(
    val sectionIndex: Int,
    val stepIndex: Int
)

sealed interface RecipeDetailState {
    data object Loading : RecipeDetailState
    data object NotFound : RecipeDetailState
    data class Loaded(val recipe: Recipe) : RecipeDetailState
}

sealed interface RecipeDetailEvent {
    /** The recipe was deleted; leave the screen. */
    data object Deleted : RecipeDetailEvent

    /** The recipe was exported to [uri]; offer to share it. */
    data class ShareExport(val uri: Uri) : RecipeDetailEvent

    data class ShowMessage(val message: UserMessage) : RecipeDetailEvent
}

@OptIn(FlowPreview::class)
@HiltViewModel
class RecipeDetailViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val recipeRepository: RecipeRepository,
    private val mealPlanRepository: MealPlanRepository,
    private val settings: AppSettings,
    private val calculateIngredientUsage: CalculateIngredientUsageUseCase,
    private val exportSingleRecipeUseCase: ExportSingleRecipeUseCase,
    private val recipeSerializer: RecipeSerializer,
    @param:ApplicationContext private val applicationContext: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    companion object {
        private const val TAG = "RecipeDetailViewModel"
        internal const val KEY_SCALE = "cooking_scale"
        internal const val KEY_MEASUREMENT_PREFERENCE = "cooking_measurement_preference"
        internal const val KEY_USED_INGREDIENTS = "cooking_used_ingredients"
        internal const val KEY_HIGHLIGHTED_STEP = "cooking_highlighted_step"
        internal const val KEY_CHECKED_AGGREGATE_INGREDIENTS = "cooking_checked_aggregate_ingredients"
        internal const val KEY_NOTES_DRAFT = "notes_draft"
        internal const val NOTES_SAVE_DEBOUNCE_MS = 1000L
        private const val MIN_SCALE = 0.25
        private const val MAX_SCALE = 10.0
        private const val MAX_SERVINGS_MULTIPLIER = 10
        /** Multipliers offered by +/- when the recipe doesn't say how many servings it makes. */
        internal val SCALE_PRESETS = listOf(0.25, 0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)
    }

    private val recipeId: String = savedStateHandle.toRoute<Route.RecipeDetail>().recipeId

    private val _events = Channel<RecipeDetailEvent>(Channel.BUFFERED)
    val events: Flow<RecipeDetailEvent> = _events.receiveAsFlow()

    val recipe: StateFlow<Recipe?> = recipeRepository.getRecipeById(recipeId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    private val _isDeleting = MutableStateFlow(false)

    val uiState: StateFlow<RecipeDetailState> = combine(
        recipeRepository.getRecipeById(recipeId),
        _isDeleting
    ) { recipe, deleting ->
        when {
            recipe != null -> RecipeDetailState.Loaded(recipe)
            // Avoid flashing "not found" between the delete and navigating away
            deleting -> RecipeDetailState.Loading
            else -> RecipeDetailState.NotFound
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = RecipeDetailState.Loading
    )

    val keepScreenOn: StateFlow<Boolean> = settings.keepScreenOn
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val volumeUnitSystem: StateFlow<UnitSystem> = settings.volumeUnitSystem
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UnitSystem.localeDefault()
        )

    val weightUnitSystem: StateFlow<UnitSystem> = settings.weightUnitSystem
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UnitSystem.localeDefault()
        )

    val hasApiKey: StateFlow<Boolean> = settings.anthropicApiKey
        .map { !it.isNullOrBlank() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    // Cooking progress is kept in the SavedStateHandle so it survives process death
    // (e.g. the phone locks for a while in the middle of cooking).

    private val _scale = MutableStateFlow(savedStateHandle[KEY_SCALE] ?: 1.0)
    val scale: StateFlow<Double> = _scale.asStateFlow()

    private val _measurementPreference = MutableStateFlow(
        savedStateHandle.get<String>(KEY_MEASUREMENT_PREFERENCE)
            ?.let { name -> MeasurementPreference.entries.find { it.name == name } }
            ?: MeasurementPreference.DEFAULT
    )
    val measurementPreference: StateFlow<MeasurementPreference> = _measurementPreference.asStateFlow()

    /**
     * Tracks which instruction step ingredients have been marked as used.
     * Key is InstructionIngredientKey (sectionIndex-stepIndex-ingredientIndex).
     */
    private val _usedInstructionIngredients = MutableStateFlow<Set<InstructionIngredientKey>>(
        savedStateHandle.get<ArrayList<String>>(KEY_USED_INGREDIENTS)?.toSet() ?: emptySet()
    )
    val usedInstructionIngredients: StateFlow<Set<InstructionIngredientKey>> = _usedInstructionIngredients.asStateFlow()

    /**
     * Tracks which instruction step is currently highlighted.
     */
    private val _highlightedInstructionStep = MutableStateFlow(
        savedStateHandle.get<IntArray>(KEY_HIGHLIGHTED_STEP)
            ?.takeIf { it.size == 2 }
            ?.let { HighlightedInstructionStep(it[0], it[1]) }
    )
    val highlightedInstructionStep: StateFlow<HighlightedInstructionStep?> = _highlightedInstructionStep.asStateFlow()

    /**
     * Ingredients checked off directly in the aggregated ingredient list (e.g. while gathering
     * ingredients before cooking). Keyed by "sectionName|ingredientName", lowercase.
     */
    private val _checkedAggregateIngredients = MutableStateFlow<Set<String>>(
        savedStateHandle.get<ArrayList<String>>(KEY_CHECKED_AGGREGATE_INGREDIENTS)?.toSet() ?: emptySet()
    )
    val checkedAggregateIngredients: StateFlow<Set<String>> = _checkedAggregateIngredients.asStateFlow()

    val hasCookingProgress: StateFlow<Boolean> = combine(
        _usedInstructionIngredients,
        _highlightedInstructionStep,
        _checkedAggregateIngredients
    ) { used, highlighted, checked ->
        used.isNotEmpty() || highlighted != null || checked.isNotEmpty()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    /**
     * Returns true if any ingredient in the recipe supports conversion (has density).
     * Used to determine whether to show the measurement toggle.
     */
    val supportsConversion: StateFlow<Boolean> = recipe
        .map { recipe ->
            recipe?.instructionSections?.any { section ->
                section.steps.any { step ->
                    step.ingredients.any { ingredient ->
                        ingredient.supportsConversion()
                    }
                }
            } ?: false
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    fun setScale(scale: Double) {
        updateScale(scale.coerceIn(MIN_SCALE, MAX_SCALE))
    }

    private fun updateScale(scale: Double) {
        _scale.value = scale
        savedStateHandle[KEY_SCALE] = scale
    }

    /**
     * Adds one serving if the recipe's serving count is known, otherwise steps to the
     * next multiplier in [SCALE_PRESETS].
     */
    fun incrementScale() {
        val servings = recipe.value?.servings?.takeIf { it > 0 }
        if (servings != null) {
            val current = (_scale.value * servings).roundToInt()
            val next = (current + 1).coerceAtMost(servings * MAX_SERVINGS_MULTIPLIER)
            updateScale(next.toDouble() / servings)
        } else {
            val next = SCALE_PRESETS.firstOrNull { it > _scale.value + 1e-9 } ?: SCALE_PRESETS.last()
            updateScale(next)
        }
    }

    fun decrementScale() {
        val servings = recipe.value?.servings?.takeIf { it > 0 }
        if (servings != null) {
            val current = (_scale.value * servings).roundToInt()
            val next = (current - 1).coerceAtLeast(1)
            updateScale(next.toDouble() / servings)
        } else {
            val next = SCALE_PRESETS.lastOrNull { it < _scale.value - 1e-9 } ?: SCALE_PRESETS.first()
            updateScale(next)
        }
    }

    fun setMeasurementPreference(preference: MeasurementPreference) {
        _measurementPreference.value = preference
        savedStateHandle[KEY_MEASUREMENT_PREFERENCE] = preference.name
    }

    /**
     * Toggles the used status of an instruction ingredient.
     * An ingredient and its alternates are treated as one item.
     */
    fun toggleInstructionIngredientUsed(sectionIndex: Int, stepIndex: Int, ingredientIndex: Int) {
        val key = createInstructionIngredientKey(sectionIndex, stepIndex, ingredientIndex)
        val updated = if (key in _usedInstructionIngredients.value) {
            _usedInstructionIngredients.value - key
        } else {
            _usedInstructionIngredients.value + key
        }
        _usedInstructionIngredients.value = updated
        savedStateHandle[KEY_USED_INGREDIENTS] = ArrayList(updated)
    }

    /**
     * Checks if an instruction ingredient is marked as used.
     */
    fun isInstructionIngredientUsed(sectionIndex: Int, stepIndex: Int, ingredientIndex: Int): Boolean {
        val key = createInstructionIngredientKey(sectionIndex, stepIndex, ingredientIndex)
        return key in _usedInstructionIngredients.value
    }

    fun toggleAggregateIngredientChecked(key: String) {
        val updated = if (key in _checkedAggregateIngredients.value) {
            _checkedAggregateIngredients.value - key
        } else {
            _checkedAggregateIngredients.value + key
        }
        _checkedAggregateIngredients.value = updated
        savedStateHandle[KEY_CHECKED_AGGREGATE_INGREDIENTS] = ArrayList(updated)
    }

    /**
     * Computes the usage status for all aggregated ingredients based on which instruction
     * ingredients have been marked as used.
     * Outer key: section name (null for unnamed sections)
     * Inner key: ingredient name (lowercase)
     * Value: IngredientUsageStatus with total, used, and remaining amounts
     */
    val ingredientUsageBySection: StateFlow<Map<String?, Map<String, IngredientUsageStatus>>> = combine(
        recipe,
        _usedInstructionIngredients,
        _scale,
        _measurementPreference,
        volumeUnitSystem,
        weightUnitSystem
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val recipe = args[0] as Recipe?
        @Suppress("UNCHECKED_CAST")
        val usedKeys = args[1] as Set<InstructionIngredientKey>
        val scale = args[2] as Double
        val preference = args[3] as MeasurementPreference
        val volSystem = args[4] as UnitSystem
        val wtSystem = args[5] as UnitSystem
        if (recipe == null) return@combine emptyMap()
        calculateIngredientUsage.execute(recipe, usedKeys, scale, preference, volSystem, wtSystem)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyMap()
    )

    /**
     * Resets all ingredient usage tracking.
     */
    fun resetIngredientUsage() {
        _usedInstructionIngredients.value = emptySet()
        savedStateHandle[KEY_USED_INGREDIENTS] = ArrayList<String>()
    }

    /**
     * Clears checked ingredients and the highlighted step, e.g. to cook the recipe again.
     * Scale and units are left alone since they are display preferences.
     */
    fun resetCookingProgress() {
        resetIngredientUsage()
        _checkedAggregateIngredients.value = emptySet()
        savedStateHandle[KEY_CHECKED_AGGREGATE_INGREDIENTS] = ArrayList<String>()
        _highlightedInstructionStep.value = null
        savedStateHandle.remove<IntArray>(KEY_HIGHLIGHTED_STEP)
    }

    /**
     * Toggles the highlighted instruction step.
     * If the step is already highlighted, clears the highlight.
     * Otherwise, sets this step as highlighted.
     */
    fun toggleHighlightedInstructionStep(sectionIndex: Int, stepIndex: Int) {
        val newStep = HighlightedInstructionStep(sectionIndex, stepIndex)
        val updated = if (_highlightedInstructionStep.value == newStep) null else newStep
        _highlightedInstructionStep.value = updated
        if (updated == null) {
            savedStateHandle.remove<IntArray>(KEY_HIGHLIGHTED_STEP)
        } else {
            savedStateHandle[KEY_HIGHLIGHTED_STEP] = intArrayOf(updated.sectionIndex, updated.stepIndex)
        }
    }

    /**
     * Toggles the favorite status of the current recipe. Like other repository writes, it
     * applies locally at once and a sync failure is reported app-wide.
     */
    fun toggleFavorite() {
        val currentRecipe = recipe.value ?: return
        recipeRepository.setFavorite(recipeId, !currentRecipe.isFavorite)
    }

    /**
     * Returns the number of meal plan entries that reference this recipe.
     */
    suspend fun getAffectedMealPlanCount(): Int {
        return try {
            mealPlanRepository.countMealPlansByRecipeId(recipeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to count meal plans for $recipeId", e)
            0
        }
    }

    /**
     * Deletes the current recipe and any meal plan entries that reference it,
     * then emits [RecipeDetailEvent.Deleted]. Finding the meal plans can fail; then nothing is
     * deleted.
     */
    fun deleteRecipe() {
        viewModelScope.launch {
            _isDeleting.value = true
            try {
                // Both or neither, even if the screen is left in between
                withContext(NonCancellable) {
                    mealPlanRepository.deleteMealPlansByRecipeId(recipeId)
                    recipeRepository.deleteRecipe(recipeId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to delete recipe $recipeId", e)
                _isDeleting.value = false
                _events.send(RecipeDetailEvent.ShowMessage(UserMessage(R.string.delete_recipe_failed)))
                return@launch
            }
            _events.send(RecipeDetailEvent.Deleted)
        }
    }

    // --- User Notes ---

    private val _notesEdits = MutableStateFlow<String?>(savedStateHandle[KEY_NOTES_DRAFT])

    /**
     * Text the user has typed into the notes field, or null if they haven't edited it
     * (in which case the recipe's saved notes are shown). Held here rather than in the
     * composable so it survives rotation/process death and can be flushed when the
     * screen goes away.
     */
    var notesDraft by mutableStateOf<String?>(savedStateHandle[KEY_NOTES_DRAFT])
        private set

    init {
        viewModelScope.launch {
            _notesEdits
                .filterNotNull()
                .debounce(NOTES_SAVE_DEBOUNCE_MS)
                .collect { saveNotesIfChanged(it) }
        }
    }

    fun onNotesChange(text: String) {
        notesDraft = text
        savedStateHandle[KEY_NOTES_DRAFT] = text
        _notesEdits.value = text
    }

    /** Saves any unsaved notes immediately (e.g. when the field loses focus). */
    fun flushNotes() {
        notesDraft?.let { saveNotesIfChanged(it) }
    }

    private fun saveNotesIfChanged(text: String) {
        val currentRecipe = recipe.value ?: return
        val normalizedNotes = text.takeIf { it.isNotBlank() }
        if (normalizedNotes == currentRecipe.userNotes) return
        recipeRepository.setUserNotes(recipeId, normalizedNotes)
    }

    override fun onCleared() {
        // Don't lose the last few keystrokes if the user leaves before the debounce fires.
        flushNotes()
    }

    // --- Recipe Export ---

    /**
     * Exports the current recipe to a .lorecipes file in the cache directory
     * and emits [RecipeDetailEvent.ShareExport] with its content URI.
     */
    fun exportRecipeFile() {
        viewModelScope.launch {
            val currentRecipe = recipe.value ?: return@launch
            try {
                val uri = withContext(ioDispatcher) {
                    val sharedDir = File(applicationContext.cacheDir, "shared_recipes").apply { mkdirs() }
                    val fileName = "${recipeSerializer.sanitizeFolderName(currentRecipe.name)}.lorecipes"
                    val file = File(sharedDir, fileName)

                    val result = file.outputStream().use { outputStream ->
                        exportSingleRecipeUseCase.exportRecipe(currentRecipe, outputStream)
                    }
                    if (result is ExportSingleRecipeUseCase.ExportResult.Error) {
                        throw IllegalStateException(result.message)
                    }
                    FileProvider.getUriForFile(
                        applicationContext,
                        "${applicationContext.packageName}.fileprovider",
                        file
                    )
                }
                _events.send(RecipeDetailEvent.ShareExport(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to export recipe $recipeId", e)
                _events.send(RecipeDetailEvent.ShowMessage(UserMessage(R.string.export_recipe_failed)))
            }
        }
    }
}
