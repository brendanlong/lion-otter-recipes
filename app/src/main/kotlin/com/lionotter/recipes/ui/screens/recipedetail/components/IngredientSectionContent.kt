package com.lionotter.recipes.ui.screens.recipedetail.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.IngredientSection
import com.lionotter.recipes.domain.model.IngredientUsageStatus
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.model.formatAmount

/** Key for an ingredient checked off in the aggregated ingredient list. */
internal fun aggregateIngredientKey(sectionName: String?, ingredientName: String): String =
    "${sectionName.orEmpty()}|${ingredientName.lowercase()}"

/**
 * An aggregated ingredient section. When [onToggleChecked] is provided, each ingredient is a
 * full-width checkbox row so the user can tick off ingredients as they gather them.
 */
@Composable
internal fun IngredientSectionContent(
    section: IngredientSection,
    scale: Double,
    measurementPreference: MeasurementPreference,
    ingredientUsage: Map<String, IngredientUsageStatus>,
    checkedIngredients: Set<String> = emptySet(),
    onToggleChecked: ((String) -> Unit)? = null,
    volumeUnitSystem: UnitSystem = UnitSystem.localeDefault(),
    weightUnitSystem: UnitSystem = UnitSystem.localeDefault()
) {
    Column {
        section.name?.let { name ->
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        section.ingredients.forEach { ingredient ->
            val key = aggregateIngredientKey(section.name, ingredient.name)
            val isChecked = key in checkedIngredients
            val rowModifier = if (onToggleChecked != null) {
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .toggleable(
                        value = isChecked,
                        role = Role.Checkbox,
                        onValueChange = { onToggleChecked(key) }
                    )
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            }

            Row(
                modifier = rowModifier,
                verticalAlignment = if (onToggleChecked != null) Alignment.CenterVertically else Alignment.Top
            ) {
                if (onToggleChecked != null) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = null,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                } else {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    IngredientLine(
                        ingredient = ingredient,
                        prefix = null,
                        usage = ingredientUsage[ingredient.name.lowercase()],
                        forceStruck = isChecked,
                        scale = scale,
                        measurementPreference = measurementPreference,
                        volumeUnitSystem = volumeUnitSystem,
                        weightUnitSystem = weightUnitSystem
                    )
                    ingredient.alternates.forEach { alternate ->
                        IngredientLine(
                            ingredient = alternate,
                            prefix = stringResource(R.string.or),
                            usage = ingredientUsage[alternate.name.lowercase()],
                            forceStruck = isChecked,
                            scale = scale,
                            measurementPreference = measurementPreference,
                            volumeUnitSystem = volumeUnitSystem,
                            weightUnitSystem = weightUnitSystem
                        )
                    }
                }
            }
        }

        if (section.name != null) {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun IngredientLine(
    ingredient: Ingredient,
    prefix: String?,
    usage: IngredientUsageStatus?,
    forceStruck: Boolean,
    scale: Double,
    measurementPreference: MeasurementPreference,
    volumeUnitSystem: UnitSystem,
    weightUnitSystem: UnitSystem
) {
    val isFullyUsed = usage?.isFullyUsed == true
    val isStruck = isFullyUsed || forceStruck
    val isAlternate = prefix != null
    val formatted = ingredient.format(scale, measurementPreference, volumeUnitSystem, weightUnitSystem)
    Text(
        text = if (prefix != null) "$prefix $formatted" else formatted,
        style = if (isAlternate) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
        textDecoration = if (isStruck) TextDecoration.LineThrough else TextDecoration.None,
        color = when {
            isStruck -> MaterialTheme.colorScheme.outline
            isAlternate -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = if (isAlternate) Modifier.padding(start = 16.dp) else Modifier
    )
    // Show remaining amount if partially used by checked-off steps
    val remaining = usage?.remainingAmount
    if (!isStruck && usage != null && usage.usedAmount > 0 && remaining != null && remaining > 0) {
        Text(
            text = formatRemainingAmount(remaining, usage.remainingUnit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = if (isAlternate) Modifier.padding(start = 16.dp) else Modifier
        )
    }
}

/**
 * Formats the remaining amount for display (e.g., "1/2 cup left").
 */
internal fun formatRemainingAmount(amount: Double, unit: String?): String {
    return "${formatAmount(amount, unit)} left"
}
