package com.lionotter.recipes.ui.screens.recipedetail.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.ui.theme.einkBorder

/** Prep/cook/total time. Servings are shown (and adjusted) by [ScaleControl]. */
@Composable
internal fun RecipeMetadata(recipe: Recipe) {
    val items = buildList {
        recipe.prepTime?.let { add(stringResource(R.string.prep_time, it)) }
        recipe.cookTime?.let { add(stringResource(R.string.cook_time, it)) }
        recipe.totalTime?.let { add(stringResource(R.string.total_time, it)) }
    }

    if (items.isNotEmpty()) {
        Card(
            border = einkBorder(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                items.forEach { item ->
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}
