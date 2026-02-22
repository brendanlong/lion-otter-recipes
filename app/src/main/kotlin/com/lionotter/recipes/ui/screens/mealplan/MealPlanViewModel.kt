package com.lionotter.recipes.ui.screens.mealplan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.repository.IMealPlanRepository
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.StartOfWeek
import com.lionotter.recipes.domain.usecase.GetTagsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.NonCancellable
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

@HiltViewModel
class MealPlanViewModel @Inject constructor(
    private val mealPlanRepository: IMealPlanRepository,
    private val recipeRepository: IRecipeRepository,
    private val getTagsUseCase: GetTagsUseCase,
    settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val today = Clock.System.todayIn(TimeZone.currentSystemDefault())

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
    private val _navigatedDate = MutableStateFlow(today)

    val currentWeekStart: StateFlow<LocalDate> = combine(
        _navigatedDate,
        startOfWeekSetting
    ) { date, setting ->
        getWeekStart(date, setting.resolve())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = getWeekStart(today, StartOfWeek.LOCALE_DEFAULT.resolve())
    )

    private val _showAddDialog = MutableStateFlow(false)
    val showAddDialog: StateFlow<Boolean> = _showAddDialog.asStateFlow()

    // When non-null, the dialog is in edit mode for this entry
    private val _editingEntry = MutableStateFlow<MealPlanEntry?>(null)
    val editingEntry: StateFlow<MealPlanEntry?> = _editingEntry.asStateFlow()

    private val _selectedDate = MutableStateFlow(today)
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val _selectedMealType = MutableStateFlow(MealType.DINNER)
    val selectedMealType: StateFlow<MealType> = _selectedMealType.asStateFlow()

    private val _selectedServings = MutableStateFlow(1.0)
    val selectedServings: StateFlow<Double> = _selectedServings.asStateFlow()

    // Recipe search state for the add dialog
    private val _recipeSearchQuery = MutableStateFlow("")
    val recipeSearchQuery: StateFlow<String> = _recipeSearchQuery.asStateFlow()

    private val _selectedTagFilter = MutableStateFlow<String?>(null)
    val selectedTagFilter: StateFlow<String?> = _selectedTagFilter.asStateFlow()

    val availableTags: StateFlow<List<String>> = recipeRepository.getAllRecipes()
        .map { recipes -> getTagsUseCase.execute(recipes) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val filteredRecipes: StateFlow<List<Recipe>> = combine(
        recipeRepository.getAllRecipes(),
        _recipeSearchQuery,
        _selectedTagFilter
    ) { recipes, query, tag ->
        recipes.filter { recipe ->
            val matchesSearch = query.isBlank() ||
                recipe.name.contains(query, ignoreCase = true) ||
                recipe.tags.any { it.contains(query, ignoreCase = true) }
            val matchesTag = tag == null ||
                recipe.tags.any { it.equals(tag, ignoreCase = true) }
            matchesSearch && matchesTag
        }.sortedBy { it.name }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /**
     * Meal plans for the current week, grouped by date.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val weekMealPlans: StateFlow<Map<LocalDate, List<MealPlanEntry>>> = currentWeekStart
        .flatMapLatest { weekStart ->
            val weekEnd = weekStart.plus(6, DateTimeUnit.DAY)
            mealPlanRepository.getMealPlansForDateRange(weekStart, weekEnd)
                .map { weekPlans ->
                    // Group by date and sort within each day
                    weekPlans.groupBy { it.date }
                        .mapValues { (_, entries) ->
                            entries.sortedWith(
                                compareBy<MealPlanEntry> { it.mealType.displayOrder }
                                    .thenBy { it.recipeName }
                            )
                        }
                }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    fun navigateWeek(forward: Boolean) {
        _navigatedDate.value = if (forward) {
            _navigatedDate.value.plus(7, DateTimeUnit.DAY)
        } else {
            _navigatedDate.value.minus(7, DateTimeUnit.DAY)
        }
    }

    fun goToToday() {
        _navigatedDate.value = today
    }

    fun openAddDialog() {
        _editingEntry.value = null
        _selectedDate.value = currentWeekStart.value
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

    fun addRecipeToMealPlan(recipe: Recipe) {
        viewModelScope.launch {
            val now = Clock.System.now()
            val existing = _editingEntry.value
            withContext(NonCancellable) {
                if (existing != null) {
                    val updated = existing.copy(
                        recipeId = recipe.id,
                        recipeName = recipe.name,
                        recipeImageUrl = recipe.imageUrl,
                        date = _selectedDate.value,
                        mealType = _selectedMealType.value,
                        servings = _selectedServings.value,
                        updatedAt = now
                    )
                    mealPlanRepository.updateMealPlan(updated)
                } else {
                    val entry = MealPlanEntry(
                        id = UUID.randomUUID().toString(),
                        recipeId = recipe.id,
                        recipeName = recipe.name,
                        recipeImageUrl = recipe.imageUrl,
                        date = _selectedDate.value,
                        mealType = _selectedMealType.value,
                        servings = _selectedServings.value,
                        createdAt = now,
                        updatedAt = now
                    )
                    mealPlanRepository.saveMealPlan(entry)
                }
            }
            _showAddDialog.value = false
            _editingEntry.value = null
        }
    }

    /**
     * Save edits to an existing meal plan without changing the recipe.
     */
    fun saveEditedMealPlan() {
        viewModelScope.launch {
            val existing = _editingEntry.value ?: return@launch
            val now = Clock.System.now()
            val updated = existing.copy(
                date = _selectedDate.value,
                mealType = _selectedMealType.value,
                servings = _selectedServings.value,
                updatedAt = now
            )
            withContext(NonCancellable) {
                mealPlanRepository.updateMealPlan(updated)
            }
            _showAddDialog.value = false
            _editingEntry.value = null
        }
    }

    fun deleteMealPlan(entryId: String) {
        viewModelScope.launch {
            withContext(NonCancellable) {
                mealPlanRepository.deleteMealPlan(entryId)
            }
        }
    }

    private fun getWeekStart(date: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate {
        val currentDay = date.dayOfWeek
        val daysFromStart = (currentDay.isoDayNumber - firstDayOfWeek.isoDayNumber + 7) % 7
        return date.minus(daysFromStart, DateTimeUnit.DAY)
    }
}
