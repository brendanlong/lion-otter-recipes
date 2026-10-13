package com.lionotter.recipes.ui.navigation

import android.os.Bundle
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import com.lionotter.recipes.ui.screens.importselection.ImportType
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Navigates a real NavController to each route and reads the arguments back the way screens
 * and ViewModels do, including after the back stack is saved and restored (process death).
 */
@RunWith(RobolectricTestRunner::class)
class RouteTest {

    private fun NavGraphBuilder.allDestinations() {
        composable<Route.RecipeList> {}
        composable<Route.RecipeDetail> {}
        composable<Route.EditRecipe> {}
        composable<Route.AddRecipe> {}
        composable<Route.Settings> {}
        composable<Route.MealPlan> {}
        composable<Route.GroceryList> {}
        composable<Route.ImportDebugList> {}
        composable<Route.ImportDebugDetail> {}
        composable<Route.ImportSelection> {}
    }

    /** A controller as the app's NavHost sets it up, optionally restoring [savedState]. */
    private fun newController(savedState: Bundle? = null) =
        TestNavHostController(ApplicationProvider.getApplicationContext()).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            setViewModelStore(ViewModelStore())
            if (savedState != null) restoreState(savedState)
            graph = createGraph(startDestination = Route.RecipeList) { allDestinations() }
        }

    private inline fun <reified T : Route> TestNavHostController.currentRoute(): T {
        val entry = checkNotNull(currentBackStackEntry)
        assertTrue(entry.destination.hasRoute<T>())
        val fromEntry = entry.toRoute<T>()
        assertEquals(fromEntry, entry.savedStateHandle.toRoute<T>())
        return fromEntry
    }

    @Test
    fun `shared URLs with reserved characters round-trip exactly`() {
        val urls = listOf(
            "https://example.com/recipe?a=1&b=%20#frag",
            "https://example.com/a b/c%2Fd?q=x+y&r=100%#top",
            "https://example.com/{braces}/[brackets]?x=é&y=/",
        )
        for (url in urls) {
            val nav = newController()
            nav.navigate(Route.AddRecipe(url))
            assertEquals(url, nav.currentRoute<Route.AddRecipe>().url)
        }
    }

    @Test
    fun `add recipe without a URL has a null url`() {
        val nav = newController()
        nav.navigate(Route.AddRecipe())
        assertEquals(null, nav.currentRoute<Route.AddRecipe>().url)
    }

    @Test
    fun `every route with arguments round-trips`() {
        val routes = listOf(
            Route.RecipeDetail("id/with?odd&chars"),
            Route.EditRecipe("recipe-1"),
            Route.GroceryList(LocalDate(2026, 3, 2)),
            Route.ImportDebugDetail("debug-1"),
            Route.ImportSelection(ImportType.PAPRIKA, "content://downloads/x/My Recipes%20(1).paprikarecipes"),
            Route.ImportSelection(ImportType.LORECIPES, "content://media/external/file/42"),
        )
        for (route in routes) {
            val nav = newController()
            nav.navigate(route)
            val decoded = when (route) {
                is Route.RecipeDetail -> nav.currentRoute<Route.RecipeDetail>()
                is Route.EditRecipe -> nav.currentRoute<Route.EditRecipe>()
                is Route.GroceryList -> nav.currentRoute<Route.GroceryList>()
                is Route.ImportDebugDetail -> nav.currentRoute<Route.ImportDebugDetail>()
                is Route.ImportSelection -> nav.currentRoute<Route.ImportSelection>()
                else -> error("unexpected $route")
            }
            assertEquals(route, decoded)
        }
    }

    @Test
    fun `grocery list week start parses back to a date`() {
        val nav = newController()
        nav.navigate(Route.GroceryList(LocalDate(2026, 12, 28)))
        assertEquals(LocalDate(2026, 12, 28), nav.currentRoute<Route.GroceryList>().weekStartDate)
    }

    @Test
    fun `back stack and arguments are restored after process death`() {
        val url = "https://example.com/r?a=1&b=%20#frag"
        val nav = newController()
        nav.navigate(Route.RecipeDetail("recipe-1"))
        nav.navigate(Route.EditRecipe("recipe-1"))
        nav.navigate(Route.AddRecipe(url))
        val saved = checkNotNull(nav.saveState())

        val restored = newController(savedState = saved)

        assertEquals(url, restored.currentRoute<Route.AddRecipe>().url)
        assertTrue(restored.popBackStack())
        assertEquals(Route.EditRecipe("recipe-1"), restored.currentRoute<Route.EditRecipe>())
        assertTrue(restored.popBackStack())
        assertEquals(Route.RecipeDetail("recipe-1"), restored.currentRoute<Route.RecipeDetail>())
        assertTrue(restored.popBackStack())
        assertTrue(checkNotNull(restored.currentBackStackEntry).destination.hasRoute<Route.RecipeList>())
    }

    @Test
    fun `a result set on an entry below the top is still there after popping to it`() {
        val nav = newController()
        nav.navigate(Route.RecipeDetail("recipe-1"))
        nav.navigate(Route.EditRecipe("recipe-1"))
        nav.getBackStackEntry<Route.RecipeList>().setResult(NavResult.FilterByTag, "dessert")
        nav.setPreviousResult(NavResult.EditSaved, true)
        val restored = newController(savedState = checkNotNull(nav.saveState()))

        assertTrue(restored.popBackStack())
        val detail = checkNotNull(restored.currentBackStackEntry)
        assertEquals(true, detail.savedStateHandle.get<Boolean>(NavResult.EditSaved.key))
        assertTrue(restored.popBackStack<Route.RecipeList>(inclusive = false))
        val list = checkNotNull(restored.currentBackStackEntry)
        assertEquals("dessert", list.savedStateHandle.get<String>(NavResult.FilterByTag.key))
        list.consumeResult(NavResult.FilterByTag)
        assertEquals(null, list.savedStateHandle.get<String>(NavResult.FilterByTag.key))
    }

    @Test
    fun `hasRoute distinguishes destinations`() {
        val nav = newController()
        nav.navigate(Route.RecipeDetail("recipe-1"))
        val destination = checkNotNull(nav.currentDestination)
        assertTrue(destination.hasRoute<Route.RecipeDetail>())
        assertFalse(destination.hasRoute<Route.EditRecipe>())
    }

    @Test
    fun `import type is guessed from the file name`() {
        assertEquals(ImportType.PAPRIKA, ImportType.guessFromUri("content://x/Export.PaprikaRecipes"))
        assertEquals(ImportType.LORECIPES, ImportType.guessFromUri("content://x/backup.lorecipes"))
        assertEquals(ImportType.LORECIPES, ImportType.guessFromUri("content://media/external/file/42"))
    }
}
