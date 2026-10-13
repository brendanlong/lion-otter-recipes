package com.lionotter.recipes.ui.screens.mealplan

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.StartOfWeek
import com.lionotter.recipes.domain.model.hasTag
import com.lionotter.recipes.domain.model.matchesSearch
import com.lionotter.recipes.domain.port.AppSettings
import com.lionotter.recipes.domain.repository.MealPlanRepository
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.domain.usecase.GetTagsUseCase
import com.lionotter.recipes.messages.UserMessage
import com.lionotter.recipes.util.CurrentDate
import com.lionotter.recipes.util.DefaultDispatcher
import com.lionotter.recipes.util.ErrorLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import java.util.UUID
import javax.inject.Inject

sealed interface MealPlanEvent {
    data class ShowMessage(val message: UserMessage) : MealPlanEvent

    /** [entry] was removed; the UI offers to undo it with [MealPlanViewModel.undoDelete]. */
    data class MealRemoved(val entry: MealPlanEntry) : MealPlanEvent
}

/** The week's meal plans grouped by date. */
sealed interface WeekMealPlans {
    data object Loading : WeekMealPlans
    data class Loaded(val byDate: Map<LocalDate, List<MealPlanEntry>>) : WeekMealPlans
    data object Failed : WeekMealPlans
}

/**
 * The add/edit meal dialog. Opening it starts from fresh fields; edits replace the whole state.
 *
 * @param isSaving an add/edit is being saved, to block duplicate submissions
 */
sealed interface MealDialog {
    val date: LocalDate
    val mealType: MealType
    val servings: Double
    val searchQuery: String
    val tagFilter: String?
    val isSaving: Boolean

    data class Add(
        override val date: LocalDate,
        override val mealType: MealType = MealType.DINNER,
        override val servings: Double = 1.0,
        override val searchQuery: String = "",
        override val tagFilter: String? = null,
        override val isSaving: Boolean = false
    ) : MealDialog

    /** Changing [entry]'s date, meal or servings, or swapping its recipe. */
    data class Edit(
        val entry: MealPlanEntry,
        override val date: LocalDate = entry.date,
        override val mealType: MealType = entry.mealType,
        override val servings: Double = entry.servings,
        override val searchQuery: String = "",
        override val tagFilter: String? = null,
        override val isSaving: Boolean = false
    ) : MealDialog
}

private fun MealDialog.with(
    date: LocalDate = this.date,
    mealType: MealType = this.mealType,
    servings: Double = this.servings,
    searchQuery: String = this.searchQuery,
    tagFilter: String? = this.tagFilter,
    isSaving: Boolean = this.isSaving
): MealDialog = when (this) {
    is MealDialog.Add -> copy(date, mealType, servings, searchQuery, tagFilter, isSaving)
    is MealDialog.Edit -> copy(
        date = date,
        mealType = mealType,
        servings = servings,
        searchQuery = searchQuery,
        tagFilter = tagFilter,
        isSaving = isSaving
    )
}

/**
 * Recipes to pick from in the dialog.
 *
 * @param loadFailed the recipes couldn't be loaded
 */
data class RecipeChoices(
    val recipes: List<Recipe> = emptyList(),
    val availableTags: List<String> = emptyList(),
    val loadFailed: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MealPlanViewModel @Inject constructor(
    private val mealPlanRepository: MealPlanRepository,
    recipeRepository: RecipeRepository,
    getTagsUseCase: GetTagsUseCase,
    settings: AppSettings,
    private val currentDate: CurrentDate,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val clock: Clock = Clock.System

    /** Today, updated when the date changes while the screen is open. */
    val today: StateFlow<LocalDate> = currentDate.dates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), currentDate.today())

    /**
     * The date the user navigated to, or null to follow [today]. The week shown is computed
     * from it and the start-of-week setting.
     */
    private val navigatedDate = MutableStateFlow<LocalDate?>(null)

    val currentWeekStart: StateFlow<LocalDate> = combine(
        navigatedDate,
        today,
        settings.startOfWeek
    ) { navigated, today, setting ->
        getWeekStart(navigated ?: today, setting.resolve())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = getWeekStart(currentDate.today(), StartOfWeek.LOCALE_DEFAULT.resolve())
    )

    private val _dialog = MutableStateFlow<MealDialog?>(null)

    /** The add/edit dialog, or null when it is closed. */
    val dialog: StateFlow<MealDialog?> = _dialog.asStateFlow()

    private val _events = Channel<MealPlanEvent>(Channel.BUFFERED)
    val events: Flow<MealPlanEvent> = _events.receiveAsFlow()

    private sealed interface AllRecipes {
        data class Loaded(val recipes: List<Recipe>) : AllRecipes
        data object Failed : AllRecipes
    }

    private val allRecipes: Flow<AllRecipes> = recipeRepository.getAllRecipes()
        .map<List<Recipe>, AllRecipes> { AllRecipes.Loaded(it) }
        .catch { e ->
            ErrorLog.e(TAG, "Failed to load recipes", e)
            emit(AllRecipes.Failed)
        }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    private val availableTags: Flow<List<String>> = allRecipes
        .map { (it as? AllRecipes.Loaded)?.recipes.orEmpty() }
        // Tag selection runs an ILP solver, so only rerun it (off the main thread)
        // when recipe tags actually change
        .distinctUntilChangedBy { recipes -> recipes.map { it.id to it.tags } }
        .map { recipes -> getTagsUseCase.execute(recipes) }
        .flowOn(defaultDispatcher)
        .catch { e ->
            ErrorLog.e(TAG, "Failed to compute tags", e)
            emit(emptyList())
        }
        .onStart { emit(emptyList()) }

    /** Recipes for the dialog, filtered by its search and tag. */
    val recipeChoices: StateFlow<RecipeChoices> = combine(
        allRecipes,
        availableTags,
        _dialog.map { it?.searchQuery.orEmpty() to it?.tagFilter }.distinctUntilChanged()
    ) { all, tags, (query, tag) ->
        when (all) {
            AllRecipes.Failed -> RecipeChoices(loadFailed = true)
            is AllRecipes.Loaded -> RecipeChoices(
                recipes = all.recipes
                    .filter { recipe -> recipe.matchesSearch(query) && (tag == null || recipe.hasTag(tag)) }
                    .sortedBy { it.name },
                availableTags = tags
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RecipeChoices()
    )

    val weekMealPlans: StateFlow<WeekMealPlans> = currentWeekStart
        .flatMapLatest { weekStart ->
            val weekEnd = weekStart.plus(6, DateTimeUnit.DAY)
            mealPlanRepository.getMealPlansForDateRange(weekStart, weekEnd)
                .map<List<MealPlanEntry>, WeekMealPlans> { weekPlans ->
                    // Group by date and sort within each day
                    WeekMealPlans.Loaded(
                        weekPlans.groupBy { it.date }
                            .mapValues { (_, entries) ->
                                entries.sortedWith(
                                    compareBy<MealPlanEntry> { it.mealType.displayOrder }
                                        .thenBy { it.recipeName }
                                )
                            }
                    )
                }
                .onStart { emit(WeekMealPlans.Loading) }
                .catch { e ->
                    ErrorLog.e(TAG, "Failed to load meal plans for week of $weekStart", e)
                    emit(WeekMealPlans.Failed)
                }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = WeekMealPlans.Loading
        )

    fun navigateWeek(forward: Boolean) {
        navigatedDate.update { date ->
            val from = date ?: currentDate.today()
            if (forward) from.plus(7, DateTimeUnit.DAY) else from.minus(7, DateTimeUnit.DAY)
        }
    }

    fun goToToday() {
        navigatedDate.value = null
    }

    fun openAddDialog() {
        val weekStart = currentWeekStart.value
        val weekEnd = weekStart.plus(6, DateTimeUnit.DAY)
        val today = currentDate.today()
        _dialog.value = MealDialog.Add(date = if (today in weekStart..weekEnd) today else weekStart)
    }

    fun openEditDialog(entry: MealPlanEntry) {
        _dialog.value = MealDialog.Edit(entry)
    }

    fun dismissDialog() {
        _dialog.value = null
    }

    fun setDate(date: LocalDate) = _dialog.update { it?.with(date = date) }

    fun setMealType(mealType: MealType) = _dialog.update { it?.with(mealType = mealType) }

    fun setServings(servings: Double) = _dialog.update { it?.with(servings = servings) }

    fun onRecipeSearchQueryChange(query: String) = _dialog.update { it?.with(searchQuery = query) }

    fun onTagFilterSelected(tag: String?) = _dialog.update {
        it?.with(tagFilter = if (it.tagFilter == tag) null else tag)
    }

    /**
     * Runs a save of the open dialog, ignoring calls while another save is in flight, and closes
     * the dialog on success. Failures are logged and shown to the user.
     */
    private fun launchSave(save: suspend (MealDialog) -> Boolean) {
        val dialog = _dialog.value ?: return
        if (dialog.isSaving) return
        _dialog.value = dialog.with(isSaving = true)
        viewModelScope.launch {
            val saved = try {
                save(dialog)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to save meal plan", e)
                showMessage(R.string.meal_plan_save_failed)
                false
            }
            _dialog.update { current ->
                // A dialog opened since then isn't saving and stays open.
                when {
                    current?.isSaving != true -> current
                    saved -> null
                    else -> current.with(isSaving = false)
                }
            }
        }
    }

    private fun showMessage(@StringRes resId: Int, vararg args: Any) {
        _events.trySend(MealPlanEvent.ShowMessage(UserMessage(resId, *args)))
    }

    /** Adds [recipe] (or, when editing, swaps the meal's recipe) with the dialog's fields. */
    fun addRecipeToMealPlan(recipe: Recipe) = launchSave { dialog ->
        val now = clock.now()
        when (dialog) {
            is MealDialog.Edit -> {
                mealPlanRepository.updateMealPlan(
                    dialog.entry.copy(
                        recipeId = recipe.id,
                        recipeName = recipe.name,
                        recipeImageUrl = recipe.imageUrl,
                        date = dialog.date,
                        mealType = dialog.mealType,
                        servings = dialog.servings,
                        updatedAt = now
                    )
                )
                true
            }
            is MealDialog.Add -> {
                // Check meal plan count limit before creating
                val count = mealPlanRepository.getMealPlanCount()
                if (count >= ResourceLimits.MAX_MEAL_PLANS) {
                    ErrorLog.w(TAG, "Meal plan limit reached ($count/${ResourceLimits.MAX_MEAL_PLANS})")
                    showMessage(R.string.meal_plan_limit_reached, count, ResourceLimits.MAX_MEAL_PLANS)
                    false
                } else {
                    mealPlanRepository.saveMealPlan(
                        MealPlanEntry(
                            id = UUID.randomUUID().toString(),
                            recipeId = recipe.id,
                            recipeName = recipe.name,
                            recipeImageUrl = recipe.imageUrl,
                            date = dialog.date,
                            mealType = dialog.mealType,
                            servings = dialog.servings,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                    true
                }
            }
        }
    }

    /**
     * Save edits to an existing meal plan without changing the recipe.
     */
    fun saveEditedMealPlan() = launchSave { dialog ->
        if (dialog !is MealDialog.Edit) return@launchSave false
        mealPlanRepository.updateMealPlan(
            dialog.entry.copy(
                date = dialog.date,
                mealType = dialog.mealType,
                servings = dialog.servings,
                updatedAt = clock.now()
            )
        )
        true
    }

    /** Repository writes apply locally at once and report sync failures themselves. */
    fun deleteMealPlan(entry: MealPlanEntry) {
        mealPlanRepository.deleteMealPlan(entry.id)
        _events.trySend(MealPlanEvent.MealRemoved(entry))
    }

    /**
     * Restores a removed entry. It gets a fresh id so the list doesn't restore the
     * swiped-away card's saved (dismissed) swipe state for the old key.
     */
    fun undoDelete(entry: MealPlanEntry) {
        mealPlanRepository.saveMealPlan(entry.copy(id = UUID.randomUUID().toString()))
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
