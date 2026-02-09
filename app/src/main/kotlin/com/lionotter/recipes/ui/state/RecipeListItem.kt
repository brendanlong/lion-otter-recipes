package com.lionotter.recipes.ui.state

import androidx.compose.runtime.Immutable
import com.lionotter.recipes.domain.model.Recipe

@Immutable
sealed class RecipeListItem {
    abstract val id: String
    abstract val name: String
    abstract val isLoading: Boolean

    data class Saved(val recipe: Recipe) : RecipeListItem() {
        override val id: String = recipe.id
        override val name: String = recipe.name
        override val isLoading: Boolean = false
    }

    data class InProgress(val inProgressRecipe: InProgressRecipe) : RecipeListItem() {
        override val id: String = inProgressRecipe.id
        override val name: String = inProgressRecipe.name
        override val isLoading: Boolean = true
    }
}
