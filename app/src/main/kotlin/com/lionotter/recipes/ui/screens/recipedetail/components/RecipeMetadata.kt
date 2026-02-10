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

@Composable
internal fun RecipeMetadata(recipe: Recipe, scale: Double) {
    val items = buildList {
        recipe.prepTime?.let { add(stringResource(R.string.prep_time, it)) }
        recipe.cookTime?.let { add(stringResource(R.string.cook_time, it)) }
        recipe.totalTime?.let { add(stringResource(R.string.total_time, it)) }
        recipe.servings?.let {
            val scaled = (it * scale).let { s ->
                if (s == s.toLong().toDouble()) s.toLong().toString()
                else "%.1f".format(s)
            }
            add(stringResource(R.string.servings_label, scaled))
        }
    }

    if (items.isNotEmpty()) {
        Card(
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}
