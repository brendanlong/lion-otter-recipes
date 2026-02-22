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
    affectedMealPlanCount: Int = 0,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val message = buildString {
        append(stringResource(R.string.delete_recipe_confirmation, recipeName))
        if (affectedMealPlanCount > 0) {
            append("\n\n")
            append(
                pluralStringResource(
                    R.plurals.delete_recipe_meal_plan_warning,
                    affectedMealPlanCount,
                    affectedMealPlanCount
                )
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_recipe)) },
        text = { Text(message) },
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

@Composable
fun BulkDeleteConfirmationDialog(
    recipeCount: Int,
    affectedMealPlanCount: Int = 0,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val message = buildString {
        append(
            pluralStringResource(
                R.plurals.delete_recipes_confirmation,
                recipeCount,
                recipeCount
            )
        )
        if (affectedMealPlanCount > 0) {
            append("\n\n")
            append(
                pluralStringResource(
                    R.plurals.delete_recipe_meal_plan_warning,
                    affectedMealPlanCount,
                    affectedMealPlanCount
                )
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                pluralStringResource(
                    R.plurals.delete_recipes,
                    recipeCount,
                    recipeCount
                )
            )
        },
        text = { Text(message) },
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
