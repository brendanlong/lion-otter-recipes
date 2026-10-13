package com.lionotter.recipes.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.lionotter.recipes.ui.screens.importbrowser.BrowsedPage

/**
 * A typed result a destination hands back to a screen below it, stored in that entry's
 * SavedStateHandle so it survives process death until consumed. The entry's handle is not the
 * one its ViewModels get, so the destination reads the result and passes it on.
 */
class NavResult<T : Any> private constructor(val key: String) {
    companion object {
        /** An edit was saved; the recipe detail screen shows a confirmation. */
        val EditSaved = NavResult<Boolean>("edit_success")

        /** A tag was tapped on a recipe; the recipe list filters by it. */
        val FilterByTag = NavResult<String>("filter_by_tag")

        /** The page the user chose to (re-)import in the in-app browser. */
        val BrowsedPage = NavResult<BrowsedPage>("browsed_page")
    }
}

fun <T : Any> NavController.setPreviousResult(result: NavResult<T>, value: T) {
    previousBackStackEntry?.setResult(result, value)
}

fun <T : Any> NavBackStackEntry.setResult(result: NavResult<T>, value: T) {
    savedStateHandle[result.key] = value
}

@Composable
fun <T : Any> NavBackStackEntry.resultState(result: NavResult<T>): State<T?> =
    savedStateHandle.getStateFlow<T?>(result.key, null).collectAsStateWithLifecycle()

/** Clears the result so it is handled once. Sets null rather than removing so [resultState] updates. */
fun <T : Any> NavBackStackEntry.consumeResult(result: NavResult<T>) {
    savedStateHandle[result.key] = null
}
