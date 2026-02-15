package com.lionotter.recipes.ui.screens.recipedetail

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.AnthropicService
import com.lionotter.recipes.data.repository.MealPlanRepository
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.IngredientUsageStatus
import com.lionotter.recipes.domain.model.InstructionIngredientKey
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.model.createInstructionIngredientKey
import com.lionotter.recipes.domain.usecase.CalculateIngredientUsageUseCase
import com.lionotter.recipes.domain.usecase.ExportSingleRecipeUseCase
import com.lionotter.recipes.domain.util.RecipeSerializer
import com.lionotter.recipes.worker.RecipeRegenerateWorker
import com.lionotter.recipes.worker.observeWorkByTag
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * Represents the location of a highlighted instruction step.
 */
data class HighlightedInstructionStep(
    val sectionIndex: Int,
    val stepIndex: Int
)

sealed class RegenerateUiState {
    object Idle : RegenerateUiState()
    data class Loading(val progress: String) : RegenerateUiState()
    object Success : RegenerateUiState()
    data class Error(val message: String) : RegenerateUiState()
}

@HiltViewModel
class RecipeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val recipeRepository: RecipeRepository,
    private val mealPlanRepository: MealPlanRepository,
    private val settingsDataStore: SettingsDataStore,
    private val calculateIngredientUsage: CalculateIngredientUsageUseCase,
    private val workManager: WorkManager,
    private val exportSingleRecipeUseCase: ExportSingleRecipeUseCase,
    private val recipeSerializer: RecipeSerializer,
    @ApplicationContext private val applicationContext: Context
) : ViewModel() {

    private val recipeId: String = savedStateHandle.get<String>("recipeId")
        ?: throw IllegalArgumentException("RecipeDetailViewModel requires a 'recipeId' argument in SavedStateHandle")

    private val _recipeDeleted = MutableSharedFlow<Unit>()
    val recipeDeleted: SharedFlow<Unit> = _recipeDeleted.asSharedFlow()

    val recipe: StateFlow<Recipe?> = recipeRepository.getRecipeById(recipeId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    val keepScreenOn: StateFlow<Boolean> = settingsDataStore.keepScreenOn
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val volumeUnitSystem: StateFlow<UnitSystem> = settingsDataStore.volumeUnitSystem
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UnitSystem.localeDefault()
        )

    val weightUnitSystem: StateFlow<UnitSystem> = settingsDataStore.weightUnitSystem
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UnitSystem.localeDefault()
        )

    private val _scale = MutableStateFlow(1.0)
    val scale: StateFlow<Double> = _scale.asStateFlow()

    private val _measurementPreference = MutableStateFlow(MeasurementPreference.DEFAULT)
    val measurementPreference: StateFlow<MeasurementPreference> = _measurementPreference.asStateFlow()

    /**
     * Tracks which instruction step ingredients have been marked as used.
     * Key is InstructionIngredientKey (sectionIndex-stepIndex-ingredientIndex).
     */
    private val _usedInstructionIngredients = MutableStateFlow<Set<InstructionIngredientKey>>(emptySet())
    val usedInstructionIngredients: StateFlow<Set<InstructionIngredientKey>> = _usedInstructionIngredients.asStateFlow()

    /**
     * Tracks which instruction step is currently highlighted.
     */
    private val _highlightedInstructionStep = MutableStateFlow<HighlightedInstructionStep?>(null)
    val highlightedInstructionStep: StateFlow<HighlightedInstructionStep?> = _highlightedInstructionStep.asStateFlow()

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
        _scale.value = scale.coerceIn(0.25, 10.0)
    }

    fun incrementScale() {
        setScale(_scale.value + 0.5)
    }

    fun decrementScale() {
        setScale(_scale.value - 0.5)
    }

    fun setMeasurementPreference(preference: MeasurementPreference) {
        _measurementPreference.value = preference
    }

    /**
     * Toggles the used status of an instruction ingredient.
     * An ingredient and its alternates are treated as one item.
     */
    fun toggleInstructionIngredientUsed(sectionIndex: Int, stepIndex: Int, ingredientIndex: Int) {
        val key = createInstructionIngredientKey(sectionIndex, stepIndex, ingredientIndex)
        _usedInstructionIngredients.value = if (key in _usedInstructionIngredients.value) {
            _usedInstructionIngredients.value - key
        } else {
            _usedInstructionIngredients.value + key
        }
    }

    /**
     * Checks if an instruction ingredient is marked as used.
     */
    fun isInstructionIngredientUsed(sectionIndex: Int, stepIndex: Int, ingredientIndex: Int): Boolean {
        val key = createInstructionIngredientKey(sectionIndex, stepIndex, ingredientIndex)
        return key in _usedInstructionIngredients.value
    }

    /**
     * Computes the usage status for all aggregated ingredients based on which instruction
     * ingredients have been marked as used.
     * Key: ingredient name (lowercase)
     * Value: IngredientUsageStatus with total, used, and remaining amounts
     */
    val globalIngredientUsage: StateFlow<Map<String, IngredientUsageStatus>> = combine(
        recipe,
        _usedInstructionIngredients,
        _scale,
        _measurementPreference,
        volumeUnitSystem,
        weightUnitSystem
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val recipe = args[0] as Recipe?
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
    }

    /**
     * Toggles the highlighted instruction step.
     * If the step is already highlighted, clears the highlight.
     * Otherwise, sets this step as highlighted.
     */
    fun toggleHighlightedInstructionStep(sectionIndex: Int, stepIndex: Int) {
        val newStep = HighlightedInstructionStep(sectionIndex, stepIndex)
        _highlightedInstructionStep.value = if (_highlightedInstructionStep.value == newStep) {
            null
        } else {
            newStep
        }
    }

    /**
     * Toggles the favorite status of the current recipe.
     */
    fun toggleFavorite() {
        val currentRecipe = recipe.value ?: return
        recipeRepository.setFavorite(recipeId, !currentRecipe.isFavorite)
    }

    /**
     * Returns the number of meal plan entries that reference this recipe.
     */
    suspend fun getAffectedMealPlanCount(): Int {
        return mealPlanRepository.countMealPlansByRecipeId(recipeId)
    }

    /**
     * Deletes the current recipe and any meal plan entries that reference it,
     * then emits an event when complete.
     */
    fun deleteRecipe() {
        viewModelScope.launch {
            mealPlanRepository.deleteMealPlansByRecipeId(recipeId)
            recipeRepository.deleteRecipe(recipeId)
            _recipeDeleted.emit(Unit)
        }
    }

    // --- Regeneration ---

    private var currentRegenerateWorkId: UUID? = null

    private val _regenerateState = MutableStateFlow<RegenerateUiState>(RegenerateUiState.Idle)
    val regenerateState: StateFlow<RegenerateUiState> = _regenerateState.asStateFlow()

    private val _regenerateModel = MutableStateFlow(AnthropicService.DEFAULT_MODEL)
    val regenerateModel: StateFlow<String> = _regenerateModel.asStateFlow()

    private val _regenerateThinking = MutableStateFlow(true)
    val regenerateThinking: StateFlow<Boolean> = _regenerateThinking.asStateFlow()

    private val _hasOriginalHtml = MutableStateFlow(false)
    val hasOriginalHtml: StateFlow<Boolean> = _hasOriginalHtml.asStateFlow()

    /**
     * Whether the regenerate button should be shown.
     * True if the recipe has cached original HTML or a source URL to re-fetch from.
     */
    val canRegenerate: StateFlow<Boolean> = combine(
        _hasOriginalHtml,
        recipe
    ) { hasHtml, recipe ->
        hasHtml || !recipe?.sourceUrl.isNullOrBlank()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    private fun loadRegenerateDefaults() {
        viewModelScope.launch {
            _regenerateModel.value = settingsDataStore.aiModel.first()
            _regenerateThinking.value = settingsDataStore.extendedThinkingEnabled.first()
        }
    }

    private fun checkOriginalHtml() {
        viewModelScope.launch {
            val html = recipeRepository.getOriginalHtml(recipeId)
            _hasOriginalHtml.value = !html.isNullOrBlank()
        }
    }

    fun setRegenerateModel(model: String) {
        _regenerateModel.value = model
    }

    fun setRegenerateThinking(enabled: Boolean) {
        _regenerateThinking.value = enabled
    }

    fun regenerateRecipe() {
        val workRequest = OneTimeWorkRequestBuilder<RecipeRegenerateWorker>()
            .setInputData(
                RecipeRegenerateWorker.createInputData(
                    recipeId = recipeId,
                    model = _regenerateModel.value,
                    extendedThinking = _regenerateThinking.value
                )
            )
            .addTag(RecipeRegenerateWorker.TAG_RECIPE_REGENERATE)
            .build()

        currentRegenerateWorkId = workRequest.id
        workManager.enqueue(workRequest)
        _regenerateState.value = RegenerateUiState.Loading("Preparing...")
    }

    fun resetRegenerateState() {
        _regenerateState.value = RegenerateUiState.Idle
    }

    private fun observeRegenerateWorkStatus() {
        viewModelScope.launch {
            workManager.observeWorkByTag(RecipeRegenerateWorker.TAG_RECIPE_REGENERATE) { currentRegenerateWorkId }
                .collect { workInfo ->
                    when (workInfo.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                            _regenerateState.value = RegenerateUiState.Loading("Preparing...")
                        }
                        WorkInfo.State.RUNNING -> {
                            val progress = workInfo.progress.getString(RecipeRegenerateWorker.KEY_PROGRESS)
                            val message = when (progress) {
                                RecipeRegenerateWorker.PROGRESS_FETCHING -> "Fetching recipe page..."
                                RecipeRegenerateWorker.PROGRESS_PARSING -> "AI is re-analyzing..."
                                RecipeRegenerateWorker.PROGRESS_SAVING -> "Saving recipe..."
                                else -> "Starting..."
                            }
                            _regenerateState.value = RegenerateUiState.Loading(message)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            _regenerateState.value = RegenerateUiState.Success
                            currentRegenerateWorkId = null
                            workManager.pruneWork()
                        }
                        WorkInfo.State.FAILED -> {
                            val errorMessage = workInfo.outputData.getString(RecipeRegenerateWorker.KEY_ERROR_MESSAGE)
                                ?: "Unknown error"
                            _regenerateState.value = RegenerateUiState.Error(errorMessage)
                            currentRegenerateWorkId = null
                            workManager.pruneWork()
                        }
                        WorkInfo.State.CANCELLED -> {
                            _regenerateState.value = RegenerateUiState.Idle
                            currentRegenerateWorkId = null
                            workManager.pruneWork()
                        }
                    }
                }
        }
    }

    // --- Recipe Export ---

    private val _exportedFileUri = MutableSharedFlow<Uri>()
    val exportedFileUri: SharedFlow<Uri> = _exportedFileUri.asSharedFlow()

    /**
     * Exports the current recipe to a .lorecipes file in the cache directory
     * and emits the content URI for sharing.
     */
    fun exportRecipeFile() {
        viewModelScope.launch {
            val currentRecipe = recipe.value ?: return@launch

            val sharedDir = File(applicationContext.cacheDir, "shared_recipes").apply { mkdirs() }
            val fileName = "${recipeSerializer.sanitizeFolderName(currentRecipe.name)}.lorecipes"
            val file = File(sharedDir, fileName)

            val result = file.outputStream().use { outputStream ->
                exportSingleRecipeUseCase.exportRecipe(currentRecipe, outputStream)
            }

            if (result is ExportSingleRecipeUseCase.ExportResult.Success) {
                val uri = FileProvider.getUriForFile(
                    applicationContext,
                    "${applicationContext.packageName}.fileprovider",
                    file
                )
                _exportedFileUri.emit(uri)
            }
        }
    }

    init {
        observeRegenerateWorkStatus()
        loadRegenerateDefaults()
        checkOriginalHtml()
    }
}
