package com.lionotter.recipes.ui.screens.mealplan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.repository.MealPlanRepository
import com.lionotter.recipes.data.repository.RecipeRepository
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.usecase.GetTagsUseCase
import com.lionotter.recipes.worker.MealPlanSyncTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class MealPlanViewModel @Inject constructor(
    private val mealPlanRepository: MealPlanRepository,
    private val recipeRepository: RecipeRepository,
    private val getTagsUseCase: GetTagsUseCase,
    private val mealPlanSyncTrigger: MealPlanSyncTrigger
) : ViewModel() {

    private val today = Clock.System.todayIn(TimeZone.currentSystemDefault())

    private val _currentWeekStart = MutableStateFlow(getWeekStart(today))
    val currentWeekStart: StateFlow<LocalDate> = _currentWeekStart.asStateFlow()

    private val _showAddDialog = MutableStateFlow(false)
    val showAddDialog: StateFlow<Boolean> = _showAddDialog.asStateFlow()

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
    val weekMealPlans: StateFlow<Map<LocalDate, List<MealPlanEntry>>> = combine(
        _currentWeekStart,
        mealPlanRepository.getAllMealPlans()
    ) { weekStart, allPlans ->
        val weekEnd = weekStart.plus(6, DateTimeUnit.DAY)
        val weekPlans = allPlans.filter { it.date in weekStart..weekEnd }
        // Group by date and sort within each day
        weekPlans.groupBy { it.date }
            .mapValues { (_, entries) ->
                entries.sortedWith(
                    compareBy<MealPlanEntry> { it.mealType.displayOrder }
                        .thenBy { it.recipeName }
                )
            }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyMap()
    )

    fun navigateWeek(forward: Boolean) {
        _currentWeekStart.value = if (forward) {
            _currentWeekStart.value.plus(7, DateTimeUnit.DAY)
        } else {
            _currentWeekStart.value.minus(7, DateTimeUnit.DAY)
        }
    }

    fun goToToday() {
        _currentWeekStart.value = getWeekStart(today)
    }

    fun openAddDialog() {
        _selectedDate.value = today
        _selectedMealType.value = MealType.DINNER
        _selectedServings.value = 1.0
        _recipeSearchQuery.value = ""
        _selectedTagFilter.value = null
        _showAddDialog.value = true
    }

    fun dismissAddDialog() {
        _showAddDialog.value = false
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
            val now = Clock.System.now().toEpochMilliseconds()
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
            _showAddDialog.value = false
            mealPlanSyncTrigger.triggerIncrementalSync()
        }
    }

    fun deleteMealPlan(entryId: String) {
        viewModelScope.launch {
            mealPlanRepository.deleteMealPlan(entryId)
            mealPlanSyncTrigger.triggerIncrementalSync()
        }
    }

    private fun getWeekStart(date: LocalDate): LocalDate {
        val dayOfWeek = date.dayOfWeek
        val daysFromMonday = when (dayOfWeek) {
            DayOfWeek.MONDAY -> 0
            DayOfWeek.TUESDAY -> 1
            DayOfWeek.WEDNESDAY -> 2
            DayOfWeek.THURSDAY -> 3
            DayOfWeek.FRIDAY -> 4
            DayOfWeek.SATURDAY -> 5
            DayOfWeek.SUNDAY -> 6
        }
        return date.minus(daysFromMonday, DateTimeUnit.DAY)
    }
}
