package com.lionotter.recipes.ui.screens.mealplan

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.R
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.repository.IMealPlanRepository
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.StartOfWeek
import com.lionotter.recipes.domain.model.hasTag
import com.lionotter.recipes.domain.model.matchesSearch
import com.lionotter.recipes.domain.usecase.GetTagsUseCase
import com.lionotter.recipes.util.ErrorLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import java.util.UUID
import javax.inject.Inject

/** A user-facing message as a string resource plus format arguments. */
data class MealPlanMessage(@param:StringRes val resId: Int, val args: List<Any> = emptyList())

@HiltViewModel
class MealPlanViewModel @Inject constructor(
    private val mealPlanRepository: IMealPlanRepository,
    private val recipeRepository: IRecipeRepository,
    private val getTagsUseCase: GetTagsUseCase,
    settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val clock: Clock = Clock.System

    private fun currentDate(): LocalDate = clock.todayIn(TimeZone.currentSystemDefault())

    private val _today = MutableStateFlow(currentDate())
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    private val startOfWeekSetting: StateFlow<StartOfWeek> = settingsDataStore.startOfWeek
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = StartOfWeek.LOCALE_DEFAULT
        )

    /**
     * Tracks the date the user has navigated to. The actual week start is computed
     * from this date and the start-of-week setting.
     */
    private val _navigatedDate = MutableStateFlow(_today.value)

    val currentWeekStart: StateFlow<LocalDate> = combine(
        _navigatedDate,
        startOfWeekSetting
    ) { date, setting ->
        getWeekStart(date, setting.resolve())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = getWeekStart(_today.value, StartOfWeek.LOCALE_DEFAULT.resolve())
    )

    private val _showAddDialog = MutableStateFlow(false)
    val showAddDialog: StateFlow<Boolean> = _showAddDialog.asStateFlow()

    // When non-null, the dialog is in edit mode for this entry
    private val _editingEntry = MutableStateFlow<MealPlanEntry?>(null)
    val editingEntry: StateFlow<MealPlanEntry?> = _editingEntry.asStateFlow()

    private val _selectedDate = MutableStateFlow(_today.value)
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val _selectedMealType = MutableStateFlow(MealType.DINNER)
    val selectedMealType: StateFlow<MealType> = _selectedMealType.asStateFlow()

    private val _selectedServings = MutableStateFlow(1.0)
    val selectedServings: StateFlow<Double> = _selectedServings.asStateFlow()

    /** True while an add/edit is being saved, to block duplicate submissions. */
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    // Recipe search state for the add dialog
    private val _recipeSearchQuery = MutableStateFlow("")
    val recipeSearchQuery: StateFlow<String> = _recipeSearchQuery.asStateFlow()

    private val _selectedTagFilter = MutableStateFlow<String?>(null)
    val selectedTagFilter: StateFlow<String?> = _selectedTagFilter.asStateFlow()

    private val _errorMessage = MutableStateFlow<MealPlanMessage?>(null)
    val errorMessage: StateFlow<MealPlanMessage?> = _errorMessage.asStateFlow()

    /** The most recently deleted entry, offered for undo. */
    private val _recentlyDeleted = MutableStateFlow<MealPlanEntry?>(null)
    val recentlyDeleted: StateFlow<MealPlanEntry?> = _recentlyDeleted.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    private val allRecipes: Flow<List<Recipe>> = recipeRepository.getAllRecipes()
        .catch { e ->
            ErrorLog.e(TAG, "Failed to load recipes", e)
            _errorMessage.value = MealPlanMessage(R.string.meal_plan_recipes_load_failed)
            emit(emptyList())
        }

    val availableTags: StateFlow<List<String>> = allRecipes
        // Tag selection runs an ILP solver, so only rerun it (off the main thread)
        // when recipe tags actually change
        .distinctUntilChangedBy { recipes -> recipes.map { it.id to it.tags } }
        .map { recipes -> getTagsUseCase.execute(recipes) }
        .flowOn(Dispatchers.Default)
        .catch { e ->
            ErrorLog.e(TAG, "Failed to compute tags", e)
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val filteredRecipes: StateFlow<List<Recipe>> = combine(
        allRecipes,
        _recipeSearchQuery,
        _selectedTagFilter
    ) { recipes, query, tag ->
        recipes.filter { recipe ->
            recipe.matchesSearch(query) && (tag == null || recipe.hasTag(tag))
        }.sortedBy { it.name }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /**
     * Meal plans for the current week, grouped by date, or null while the week is loading.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val weekMealPlans: StateFlow<Map<LocalDate, List<MealPlanEntry>>?> = currentWeekStart
        .flatMapLatest { weekStart ->
            val weekEnd = weekStart.plus(6, DateTimeUnit.DAY)
            mealPlanRepository.getMealPlansForDateRange(weekStart, weekEnd)
                .map<List<MealPlanEntry>, Map<LocalDate, List<MealPlanEntry>>?> { weekPlans ->
                    // Group by date and sort within each day
                    weekPlans.groupBy { it.date }
                        .mapValues { (_, entries) ->
                            entries.sortedWith(
                                compareBy<MealPlanEntry> { it.mealType.displayOrder }
                                    .thenBy { it.recipeName }
                            )
                        }
                }
                .onStart { emit(null) }
                .catch { e ->
                    ErrorLog.e(TAG, "Failed to load meal plans for week of $weekStart", e)
                    _errorMessage.value = MealPlanMessage(R.string.meal_plan_load_failed)
                    emit(emptyMap())
                }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    /**
     * Re-reads the current date (e.g. when the screen resumes after midnight). If the user
     * was looking at the old "today", follows it to the new date.
     */
    fun refreshToday() {
        val newToday = currentDate()
        val oldToday = _today.value
        if (newToday == oldToday) return
        _today.value = newToday
        if (_navigatedDate.value == oldToday) {
            _navigatedDate.value = newToday
        }
    }

    fun navigateWeek(forward: Boolean) {
        _navigatedDate.value = if (forward) {
            _navigatedDate.value.plus(7, DateTimeUnit.DAY)
        } else {
            _navigatedDate.value.minus(7, DateTimeUnit.DAY)
        }
    }

    fun goToToday() {
        refreshToday()
        _navigatedDate.value = _today.value
    }

    fun openAddDialog() {
        val weekStart = currentWeekStart.value
        val weekEnd = weekStart.plus(6, DateTimeUnit.DAY)
        val today = _today.value
        _editingEntry.value = null
        _selectedDate.value = if (today in weekStart..weekEnd) today else weekStart
        _selectedMealType.value = MealType.DINNER
        _selectedServings.value = 1.0
        _recipeSearchQuery.value = ""
        _selectedTagFilter.value = null
        _showAddDialog.value = true
    }

    fun openEditDialog(entry: MealPlanEntry) {
        _editingEntry.value = entry
        _selectedDate.value = entry.date
        _selectedMealType.value = entry.mealType
        _selectedServings.value = entry.servings
        _recipeSearchQuery.value = ""
        _selectedTagFilter.value = null
        _showAddDialog.value = true
    }

    fun dismissAddDialog() {
        _showAddDialog.value = false
        _editingEntry.value = null
    }

    fun setSelectedDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun setSelectedMealType(mealType: MealType) {
        _selectedMealType.value = mealType
    }

    fun setSelectedServings(servings: Double) {
        _selectedServings.value = servings
    }

    fun onRecipeSearchQueryChange(query: String) {
        _recipeSearchQuery.value = query
    }

    fun onTagFilterSelected(tag: String?) {
        _selectedTagFilter.value = if (_selectedTagFilter.value == tag) null else tag
    }

    /**
     * Runs a save, ignoring calls while another save is in flight, and closes the dialog
     * on success. Failures are logged and shown to the user.
     */
    private fun launchSave(save: suspend () -> Boolean) {
        if (_isSaving.value) return
        _isSaving.value = true
        viewModelScope.launch {
            try {
                if (save()) {
                    _showAddDialog.value = false
                    _editingEntry.value = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to save meal plan", e)
                _errorMessage.value = MealPlanMessage(R.string.meal_plan_save_failed)
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun addRecipeToMealPlan(recipe: Recipe) = launchSave {
        val now = clock.now()
        val existing = _editingEntry.value
        val date = _selectedDate.value
        val mealType = _selectedMealType.value
        val servings = _selectedServings.value
        if (existing != null) {
            withContext(NonCancellable) {
                mealPlanRepository.updateMealPlan(
                    existing.copy(
                        recipeId = recipe.id,
                        recipeName = recipe.name,
                        recipeImageUrl = recipe.imageUrl,
                        date = date,
                        mealType = mealType,
                        servings = servings,
                        updatedAt = now
                    )
                )
            }
            true
        } else {
            // Check meal plan count limit before creating
            val count = mealPlanRepository.getMealPlanCount()
            if (count >= ResourceLimits.MAX_MEAL_PLANS) {
                Log.w(TAG, "Meal plan limit reached ($count/${ResourceLimits.MAX_MEAL_PLANS})")
                _errorMessage.value = MealPlanMessage(
                    R.string.meal_plan_limit_reached,
                    listOf(count, ResourceLimits.MAX_MEAL_PLANS)
                )
                false
            } else {
                withContext(NonCancellable) {
                    mealPlanRepository.saveMealPlan(
                        MealPlanEntry(
                            id = UUID.randomUUID().toString(),
                            recipeId = recipe.id,
                            recipeName = recipe.name,
                            recipeImageUrl = recipe.imageUrl,
                            date = date,
                            mealType = mealType,
                            servings = servings,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }
                true
            }
        }
    }

    /**
     * Save edits to an existing meal plan without changing the recipe.
     */
    fun saveEditedMealPlan() = launchSave {
        val existing = _editingEntry.value ?: return@launchSave false
        val updated = existing.copy(
            date = _selectedDate.value,
            mealType = _selectedMealType.value,
            servings = _selectedServings.value,
            updatedAt = clock.now()
        )
        withContext(NonCancellable) {
            mealPlanRepository.updateMealPlan(updated)
        }
        true
    }

    fun deleteMealPlan(entry: MealPlanEntry) {
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    mealPlanRepository.deleteMealPlan(entry.id)
                }
                _recentlyDeleted.value = entry
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to delete meal plan ${entry.id}", e)
                _errorMessage.value = MealPlanMessage(R.string.meal_plan_delete_failed)
            }
        }
    }

    /**
     * Restores the most recently deleted entry. It gets a fresh id so the list doesn't
     * restore the swiped-away card's saved (dismissed) swipe state for the old key.
     */
    fun undoDelete() {
        val entry = _recentlyDeleted.value ?: return
        _recentlyDeleted.value = null
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    mealPlanRepository.saveMealPlan(entry.copy(id = UUID.randomUUID().toString()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to restore meal plan ${entry.id}", e)
                _errorMessage.value = MealPlanMessage(R.string.meal_plan_save_failed)
            }
        }
    }

    fun clearRecentlyDeleted() {
        _recentlyDeleted.value = null
    }

    private fun getWeekStart(date: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate {
        val currentDay = date.dayOfWeek
        val daysFromStart = (currentDay.isoDayNumber - firstDayOfWeek.isoDayNumber + 7) % 7
        return date.minus(daysFromStart, DateTimeUnit.DAY)
    }

    companion object {
        private const val TAG = "MealPlanViewModel"
    }
}
