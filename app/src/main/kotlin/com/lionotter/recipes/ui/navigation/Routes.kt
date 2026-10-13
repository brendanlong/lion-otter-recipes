package com.lionotter.recipes.ui.navigation

import com.lionotter.recipes.ui.screens.importselection.ImportType
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation destinations. Each route's properties are its arguments; ViewModels read
 * them with `savedStateHandle.toRoute<Route.X>()`, and the back stack (including arguments) is
 * restored after process death.
 */
sealed interface Route {
    @Serializable
    data object RecipeList : Route

    @Serializable
    data class RecipeDetail(val recipeId: String) : Route

    @Serializable
    data class EditRecipe(val recipeId: String) : Route

    /** [url] pre-fills the URL field, e.g. from a share intent. */
    @Serializable
    data class AddRecipe(val url: String? = null) : Route

    /**
     * The in-app browser showing [url] so the user can confirm the page before it is imported
     * ([reimport]: re-imported into the recipe being edited). The chosen page goes back to the
     * previous screen as [NavResult.BrowsedPage].
     */
    @Serializable
    data class ImportBrowser(val url: String, val reimport: Boolean = false) : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object ManageTags : Route

    @Serializable
    data object MealPlan : Route

    /** [weekStart] is an ISO-8601 date (the first day of the week shown). */
    @Serializable
    data class GroceryList(val weekStart: String) : Route {
        constructor(weekStart: LocalDate) : this(weekStart.toString())

        val weekStartDate: LocalDate get() = LocalDate.parse(weekStart)
    }

    @Serializable
    data object ImportDebugList : Route

    @Serializable
    data class ImportDebugDetail(val debugEntryId: String) : Route

    /** [importType] is a hint from the file name; [uri] is the content URI of the export file. */
    @Serializable
    data class ImportSelection(val importType: ImportType, val uri: String) : Route
}
