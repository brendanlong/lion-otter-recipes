package com.lionotter.recipes.ui.screens.recipedetail

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.IngredientUsageStatus
import com.lionotter.recipes.domain.model.InstructionIngredientKey
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.ModelRole
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.model.createInstructionIngredientKey
import com.lionotter.recipes.domain.port.AppSettings
import com.lionotter.recipes.domain.port.model
import com.lionotter.recipes.domain.repository.MealPlanRepository
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.domain.usecase.CalculateIngredientUsageUseCase
import com.lionotter.recipes.domain.usecase.DeleteRecipeUseCase
import com.lionotter.recipes.domain.usecase.ExportSingleRecipeUseCase
import com.lionotter.recipes.domain.util.RecipeSerializer
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.ui.navigation.Route
import com.lionotter.recipes.util.AppJson
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.util.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Represents the location of a highlighted instruction step.
 */
@Serializable
data class HighlightedInstructionStep(
    val sectionIndex: Int,
    val stepIndex: Int
)

/**
 * Where the user is in cooking a recipe. Kept in the SavedStateHandle so it survives process
 * death (e.g. the phone locks for a while in the middle of cooking).
 *
 * @param usedInstructionIngredients instruction step ingredients marked as used
 * @param checkedAggregateIngredients ingredients checked off directly in the aggregated list
 *   (e.g. while gathering ingredients before cooking), keyed by "sectionName|ingredientName", lowercase
 */
@Serializable
data class CookingProgress(
    val scale: Double = 1.0,
    val measurementPreference: MeasurementPreference = MeasurementPreference.DEFAULT,
    val usedInstructionIngredients: Set<InstructionIngredientKey> = emptySet(),
    val highlightedStep: HighlightedInstructionStep? = null,
    val checkedAggregateIngredients: Set<String> = emptySet()
) {
    /** Anything a "reset cooking progress" would clear (scale and units are preferences). */
    val hasProgress: Boolean
        get() = usedInstructionIngredients.isNotEmpty() || highlightedStep != null || checkedAggregateIngredients.isNotEmpty()
}

sealed interface RecipeDetailState {
    data object Loading : RecipeDetailState
    data object NotFound : RecipeDetailState
    data class Loaded(val recipe: Recipe) : RecipeDetailState
}

sealed interface RecipeDetailEvent {
    /** The recipe was deleted; leave the screen. */
    data object Deleted : RecipeDetailEvent

    /** The recipe [recipeName] was exported to [uri]; offer to share it. */
    data class ShareExport(val uri: Uri, val recipeName: String) : RecipeDetailEvent

    data class ShowMessage(val message: UserMessage) : RecipeDetailEvent
}

@OptIn(FlowPreview::class)
@HiltViewModel
class RecipeDetailViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val recipeRepository: RecipeRepository,
    private val mealPlanRepository: MealPlanRepository,
    private val deleteRecipeUseCase: DeleteRecipeUseCase,
    settings: AppSettings,
    private val calculateIngredientUsage: CalculateIngredientUsageUseCase,
    private val exportSingleRecipeUseCase: ExportSingleRecipeUseCase,
    private val recipeSerializer: RecipeSerializer,
    @param:ApplicationContext private val applicationContext: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    companion object {
        private const val TAG = "RecipeDetailViewModel"
        internal const val KEY_COOKING_PROGRESS = "cooking_progress"
        internal const val KEY_NOTES_DRAFT = "notes_draft"
        internal const val NOTES_SAVE_DEBOUNCE_MS = 1000L
        private const val MIN_SCALE = 0.25
        private const val MAX_SCALE = 10.0
        private const val MAX_SERVINGS_MULTIPLIER = 10
        /** Multipliers offered by +/- when the recipe doesn't say how many servings it makes. */
        internal val SCALE_PRESETS = listOf(0.25, 0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)

        private fun decodeProgress(json: String?): CookingProgress = json?.let {
            try {
                AppJson.decodeFromString<CookingProgress>(it)
            } catch (e: SerializationException) {
                ErrorLog.w(TAG, "Discarding unreadable cooking progress", e)
                null
            }
        } ?: CookingProgress()
    }

    private val recipeId: String = savedStateHandle.toRoute<Route.RecipeDetail>().recipeId

    private val _events = Channel<RecipeDetailEvent>(Channel.BUFFERED)
    val events: Flow<RecipeDetailEvent> = _events.receiveAsFlow()

    private val _isDeleting = MutableStateFlow(false)

    /** The one subscription to the recipe; everything else derives from it. */
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

    private val currentRecipe: Recipe? get() = (uiState.value as? RecipeDetailState.Loaded)?.recipe

    private val recipe: Flow<Recipe?> = uiState.map { (it as? RecipeDetailState.Loaded)?.recipe }.distinctUntilChanged()

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

    val hasApiKey: StateFlow<Boolean> = settings.model(ModelRole.EDIT)
        .map { it != null }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    /** [CookingProgress] as JSON: the saved state is its only copy. */
    private val cookingProgressJson: StateFlow<String?> = savedStateHandle.getStateFlow(KEY_COOKING_PROGRESS, null)

    val cookingProgress: StateFlow<CookingProgress> = cookingProgressJson
        .map(::decodeProgress)
        .stateIn(viewModelScope, SharingStarted.Eagerly, decodeProgress(cookingProgressJson.value))

    private fun updateProgress(change: (CookingProgress) -> CookingProgress) {
        // Read the saved state itself rather than [cookingProgress], which updates asynchronously
        val updated = change(decodeProgress(cookingProgressJson.value))
        savedStateHandle[KEY_COOKING_PROGRESS] = AppJson.encodeToString(updated)
    }

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
        updateProgress { it.copy(scale = scale.coerceIn(MIN_SCALE, MAX_SCALE)) }
    }

    /**
     * Adds one serving if the recipe's serving count is known, otherwise steps to the
     * next multiplier in [SCALE_PRESETS].
     */
    fun incrementScale() {
        val servings = currentRecipe?.servings?.takeIf { it > 0 }
        updateProgress { progress ->
            val next = if (servings != null) {
                val current = (progress.scale * servings).roundToInt()
                (current + 1).coerceAtMost(servings * MAX_SERVINGS_MULTIPLIER).toDouble() / servings
            } else {
                SCALE_PRESETS.firstOrNull { it > progress.scale + 1e-9 } ?: SCALE_PRESETS.last()
            }
            progress.copy(scale = next)
        }
    }

    fun decrementScale() {
        val servings = currentRecipe?.servings?.takeIf { it > 0 }
        updateProgress { progress ->
            val next = if (servings != null) {
                val current = (progress.scale * servings).roundToInt()
                (current - 1).coerceAtLeast(1).toDouble() / servings
            } else {
                SCALE_PRESETS.lastOrNull { it < progress.scale - 1e-9 } ?: SCALE_PRESETS.first()
            }
            progress.copy(scale = next)
        }
    }

    fun setMeasurementPreference(preference: MeasurementPreference) {
        updateProgress { it.copy(measurementPreference = preference) }
    }

    /**
     * Toggles the used status of an instruction ingredient.
     * An ingredient and its alternates are treated as one item.
     */
    fun toggleInstructionIngredientUsed(sectionIndex: Int, stepIndex: Int, ingredientIndex: Int) {
        val key = createInstructionIngredientKey(sectionIndex, stepIndex, ingredientIndex)
        updateProgress { progress ->
            val used = progress.usedInstructionIngredients
            progress.copy(usedInstructionIngredients = if (key in used) used - key else used + key)
        }
    }

    fun toggleAggregateIngredientChecked(key: String) {
        updateProgress { progress ->
            val checked = progress.checkedAggregateIngredients
            progress.copy(checkedAggregateIngredients = if (key in checked) checked - key else checked + key)
        }
    }

    /**
     * Computes the usage status for all aggregated ingredients based on which instruction
     * ingredients have been marked as used.
     * Outer key: section name (null for unnamed sections)
     * Inner key: Ingredient.aggregationKey (name and modifier, lowercase)
     * Value: IngredientUsageStatus with total, used, and remaining amounts
     */
    val ingredientUsageBySection: StateFlow<Map<String?, Map<String, IngredientUsageStatus>>> = combine(
        recipe,
        cookingProgress,
        volumeUnitSystem,
        weightUnitSystem
    ) { recipe, progress, volumeSystem, weightSystem ->
        if (recipe == null) return@combine emptyMap()
        calculateIngredientUsage.execute(
            recipe,
            progress.usedInstructionIngredients,
            progress.scale,
            progress.measurementPreference,
            volumeSystem,
            weightSystem
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyMap()
    )

    /**
     * Resets all ingredient usage tracking.
     */
    fun resetIngredientUsage() {
        updateProgress { it.copy(usedInstructionIngredients = emptySet()) }
    }

    /**
     * Clears checked ingredients and the highlighted step, e.g. to cook the recipe again.
     * Scale and units are left alone since they are display preferences.
     */
    fun resetCookingProgress() {
        updateProgress {
            it.copy(usedInstructionIngredients = emptySet(), checkedAggregateIngredients = emptySet(), highlightedStep = null)
        }
    }

    /**
     * Toggles the highlighted instruction step.
     * If the step is already highlighted, clears the highlight.
     * Otherwise, sets this step as highlighted.
     */
    fun toggleHighlightedInstructionStep(sectionIndex: Int, stepIndex: Int) {
        val step = HighlightedInstructionStep(sectionIndex, stepIndex)
        updateProgress { it.copy(highlightedStep = if (it.highlightedStep == step) null else step) }
    }

    /**
     * Marks the recipe as a favorite or not. Like other repository writes, it applies locally
     * at once and a sync failure is reported app-wide.
     */
    fun setFavorite(isFavorite: Boolean) {
        recipeRepository.setFavorite(recipeId, isFavorite)
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
            ErrorLog.w(TAG, "Failed to count meal plans for $recipeId", e)
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
                deleteRecipeUseCase(recipeId)
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

    /**
     * Text the user has typed into the notes field and that the recipe doesn't show yet, or null
     * (then the recipe's saved notes are shown). Lives here, backed by the saved state, so it
     * survives rotation/process death and can be flushed when the screen goes away. Once the
     * recipe shows the saved text the draft is dropped, so later edits from elsewhere aren't
     * hidden behind it.
     */
    private val _notesDraft: MutableStateFlow<String?> = savedStateHandle.getMutableStateFlow(KEY_NOTES_DRAFT, null)
    val notesDraft: StateFlow<String?> = _notesDraft.asStateFlow()

    init {
        viewModelScope.launch {
            _notesDraft.filterNotNull()
                .debounce(NOTES_SAVE_DEBOUNCE_MS)
                .collect { saveNotesIfChanged(it) }
        }
        viewModelScope.launch {
            combine(_notesDraft, recipe) { draft, recipe -> draft to recipe }
                .collect { (draft, recipe) ->
                    if (draft != null && recipe != null && draft == recipe.userNotes.orEmpty()) {
                        _notesDraft.compareAndSet(draft, null)
                    }
                }
        }
    }

    fun onNotesChange(text: String) {
        _notesDraft.value = text
    }

    /** Saves any unsaved notes immediately (e.g. when the field loses focus). */
    fun flushNotes() {
        _notesDraft.value?.let { saveNotesIfChanged(it) }
    }

    private fun normalizeNotes(text: String): String? = text.takeIf { it.isNotBlank() }

    private fun saveNotesIfChanged(text: String) {
        val recipe = currentRecipe ?: return
        val normalizedNotes = normalizeNotes(text)
        if (normalizedNotes == recipe.userNotes) return
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
            val currentRecipe = currentRecipe ?: return@launch
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
                _events.send(RecipeDetailEvent.ShareExport(uri, currentRecipe.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to export recipe $recipeId", e)
                _events.send(RecipeDetailEvent.ShowMessage(UserMessage(R.string.export_recipe_failed)))
            }
        }
    }
}
