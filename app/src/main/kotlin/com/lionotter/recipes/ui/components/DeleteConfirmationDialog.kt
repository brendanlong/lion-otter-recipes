package com.lionotter.recipes.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.lionotter.recipes.R

@Composable
fun DeleteConfirmationDialog(
    recipeName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    affectedMealPlanCount: Int = 0
) {
    DeleteDialog(
        title = stringResource(R.string.delete_recipe),
        message = stringResource(R.string.delete_recipe_confirmation, recipeName),
        affectedMealPlanCount = affectedMealPlanCount,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

@Composable
fun BulkDeleteConfirmationDialog(
    recipeCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    affectedMealPlanCount: Int = 0
) {
    DeleteDialog(
        title = pluralStringResource(R.plurals.delete_recipes, recipeCount, recipeCount),
        message = pluralStringResource(R.plurals.delete_recipes_confirmation, recipeCount, recipeCount),
        affectedMealPlanCount = affectedMealPlanCount,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

/** Asks to confirm deleting recipes: [message], plus a warning if meal plans use them. */
@Composable
private fun DeleteDialog(
    title: String,
    message: String,
    affectedMealPlanCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val mealPlanWarning = if (affectedMealPlanCount > 0) {
        pluralStringResource(R.plurals.delete_recipe_meal_plan_warning, affectedMealPlanCount, affectedMealPlanCount)
    } else {
        null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(listOfNotNull(message, mealPlanWarning).joinToString("\n\n")) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
