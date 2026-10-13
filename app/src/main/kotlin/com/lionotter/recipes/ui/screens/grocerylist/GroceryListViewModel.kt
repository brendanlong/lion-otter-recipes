package com.lionotter.recipes.ui.screens.grocerylist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.port.AppSettings
import com.lionotter.recipes.domain.port.GroceryListStateStore
import com.lionotter.recipes.domain.port.SavedGroceryListState
import com.lionotter.recipes.domain.repository.MealPlanRepository
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.domain.usecase.AggregateGroceryListUseCase
import com.lionotter.recipes.domain.usecase.GroceryItem
import com.lionotter.recipes.ui.navigation.Route
import com.lionotter.recipes.util.ErrorLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import javax.inject.Inject

/**
 * A meal plan entry grouped by date and meal type for the recipe selector.
 */
data class SelectableMealPlanEntry(
    val entry: MealPlanEntry,
    val isSelected: Boolean
)

sealed interface GroceryListUiState {
    data object Loading : GroceryListUiState

    /**
     * Choosing which of the week's meals go on the list.
     *
     * @param loadFailed the week's meal plans couldn't be loaded
     */
    data class SelectRecipes(
        val entriesByDate: Map<LocalDate, List<SelectableMealPlanEntry>>,
        val loadFailed: Boolean = false
    ) : GroceryListUiState {
        val hasSelection: Boolean get() = entriesByDate.values.any { day -> day.any { it.isSelected } }
    }

    /**
     * The generated list, kept up to date as the selected meals and their recipes change.
     *
     * @param items null while the recipes load
     * @param skippedRecipeNames selected meals whose recipe doesn't exist (any more)
     * @param failed the list couldn't be built
     */
    data class ViewList(
        val items: List<DisplayGroceryItem>?,
        val skippedRecipeNames: List<String> = emptyList(),
        val failed: Boolean = false
    ) : GroceryListUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GroceryListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    mealPlanRepository: MealPlanRepository,
    recipeRepository: RecipeRepository,
    aggregateGroceryListUseCase: AggregateGroceryListUseCase,
    settings: AppSettings,
    private val groceryListStateStore: GroceryListStateStore
) : ViewModel() {

    private val weekStart: LocalDate = savedStateHandle.toRoute<Route.GroceryList>().weekStartDate
    private val weekEnd: LocalDate = weekStart.plus(6, DateTimeUnit.DAY)

    private sealed interface MealPlans {
        data class Loaded(val entries: List<MealPlanEntry>) : MealPlans
        data object Failed : MealPlans
    }

    /** The week's meal plans, or null while loading. */
    private val mealPlans: StateFlow<MealPlans?> = mealPlanRepository.getMealPlansForDateRange(weekStart, weekEnd)
        .map<List<MealPlanEntry>, MealPlans> { entries ->
            MealPlans.Loaded(
                entries.sortedWith(
                    compareBy<MealPlanEntry> { it.date }
                        .thenBy { it.mealType.displayOrder }
                        .thenBy { it.recipeName }
                )
            )
        }
        .catch { e ->
            ErrorLog.e(TAG, "Failed to load meal plans for $weekStart", e)
            emit(MealPlans.Failed)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Selection, step and check-offs: the only state this screen owns, persisted on every change.
     * Null until restored from [groceryListStateStore].
     */
    private val listState = MutableStateFlow<SavedGroceryListState?>(null)

    private sealed interface GroceryContent {
        data class Built(val items: List<GroceryItem>, val skippedRecipeNames: List<String>) : GroceryContent
        data object Failed : GroceryContent
    }

    /** The aggregated list for the selected meals, rebuilt when they or their recipes change. */
    private val groceryContent: Flow<GroceryContent?> = combine(
        mealPlans,
        listState.map { state -> state?.takeIf { it.showingList }?.selectedEntryIds }.distinctUntilChanged()
    ) { plans, selectedIds ->
        if (plans !is MealPlans.Loaded || selectedIds == null) null
        else plans.entries.filter { it.id in selectedIds }
    }
        .distinctUntilChanged()
        .flatMapLatest { selectedEntries ->
            if (selectedEntries == null) {
                flowOf(null)
            } else {
                recipeRepository.getAllRecipes()
                    .map<_, GroceryContent?> { recipes ->
                        val recipesById = recipes.associateBy { it.id }
                        val withRecipes = selectedEntries.mapNotNull { entry ->
                            recipesById[entry.recipeId]?.let { entry to it }
                        }
                        GroceryContent.Built(
                            items = aggregateGroceryListUseCase.execute(withRecipes),
                            skippedRecipeNames = selectedEntries
                                .filter { it.recipeId !in recipesById }
                                .map { it.recipeName }
                                .distinct()
                        )
                    }
                    .catch { e ->
                        ErrorLog.e(TAG, "Failed to generate grocery list", e)
                        emit(GroceryContent.Failed)
                    }
            }
        }

    private val unitSystems = combine(
        settings.groceryVolumeUnitSystem,
        settings.groceryWeightUnitSystem
    ) { volume, weight -> volume to weight }

    val uiState: StateFlow<GroceryListUiState> = combine(
        mealPlans,
        listState,
        groceryContent,
        unitSystems
    ) { plans, state, content, (volumeSystem, weightSystem) ->
        when (plans) {
            null -> GroceryListUiState.Loading
            MealPlans.Failed -> GroceryListUiState.SelectRecipes(emptyMap(), loadFailed = true)
            is MealPlans.Loaded -> when {
                state == null && plans.entries.isNotEmpty() -> GroceryListUiState.Loading
                state?.showingList == true -> viewList(content, state.checkedSources, volumeSystem, weightSystem)
                else -> GroceryListUiState.SelectRecipes(
                    selectableEntries(plans.entries, state?.selectedEntryIds.orEmpty())
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GroceryListUiState.Loading)

    init {
        viewModelScope.launch {
            listState.value = loadSavedState() ?: defaultState()
            listState.filterNotNull()
                .distinctUntilChanged()
                .conflate()
                .collect { save(it) }
        }
    }

    private suspend fun loadSavedState(): SavedGroceryListState? = try {
        groceryListStateStore.load(weekStart)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ErrorLog.w(TAG, "Failed to load saved grocery list state", e)
        null
    }

    /**
     * Everything selected on the first visit. Waits for meal plans to arrive: an empty first
     * snapshot may just be a cold cache (offline, or right after sign-in), and persisting an
     * empty selection then would stick.
     */
    private suspend fun defaultState(): SavedGroceryListState {
        val entries = mealPlans
            .map { (it as? MealPlans.Loaded)?.entries }
            .first { !it.isNullOrEmpty() }
            .orEmpty()
        return SavedGroceryListState(
            selectedEntryIds = entries.map { it.id }.toSet(),
            showingList = false,
            checkedSources = emptySet()
        )
    }

    private suspend fun save(state: SavedGroceryListState) {
        try {
            withContext(NonCancellable) { groceryListStateStore.save(weekStart, state) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.w(TAG, "Failed to save grocery list state", e)
        }
    }

    fun setEntrySelected(entryId: String, selected: Boolean) = listState.update { state ->
        state?.copy(
            selectedEntryIds = if (selected) state.selectedEntryIds + entryId else state.selectedEntryIds - entryId
        )
    }

    fun selectAll() {
        val entries = (mealPlans.value as? MealPlans.Loaded)?.entries.orEmpty()
        listState.update { it?.copy(selectedEntryIds = entries.map { entry -> entry.id }.toSet()) }
    }

    fun deselectAll() = listState.update { it?.copy(selectedEntryIds = emptySet()) }

    /** Shows the list for the selected meals; it stays in sync with them and their recipes. */
    fun generateGroceryList() = listState.update { it?.copy(showingList = true) }

    fun backToSelection() = listState.update { it?.copy(showingList = false) }

    fun setItemChecked(item: DisplayGroceryItem, checked: Boolean) = listState.update { state ->
        state?.copy(checkedSources = GroceryListPresenter.setItemChecked(state.checkedSources, item, checked))
    }

    fun setSourceChecked(sourceKey: String, checked: Boolean) = listState.update { state ->
        state?.copy(checkedSources = GroceryListPresenter.setSourceChecked(state.checkedSources, sourceKey, checked))
    }

    private fun viewList(
        content: GroceryContent?,
        checkedSources: Set<String>,
        volumeSystem: UnitSystem,
        weightSystem: UnitSystem
    ): GroceryListUiState.ViewList = when (content) {
        null -> GroceryListUiState.ViewList(items = null)
        GroceryContent.Failed -> GroceryListUiState.ViewList(items = emptyList(), failed = true)
        is GroceryContent.Built -> GroceryListUiState.ViewList(
            items = GroceryListPresenter.displayItems(content.items, checkedSources, volumeSystem, weightSystem),
            skippedRecipeNames = content.skippedRecipeNames
        )
    }

    private fun selectableEntries(
        entries: List<MealPlanEntry>,
        selectedIds: Set<String>
    ): Map<LocalDate, List<SelectableMealPlanEntry>> = entries
        .map { SelectableMealPlanEntry(entry = it, isSelected = it.id in selectedIds) }
        .groupBy { it.entry.date }
        .mapValues { (_, day) ->
            day.sortedWith(
                compareBy<SelectableMealPlanEntry> { it.entry.mealType.displayOrder }
                    .thenBy { it.entry.recipeName }
            )
        }
        .toSortedMap()

    companion object {
        private const val TAG = "GroceryListViewModel"
    }
}
