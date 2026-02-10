package com.lionotter.recipes.ui.screens.recipedetail.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.IngredientSection
import com.lionotter.recipes.domain.model.IngredientUsageStatus
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.util.pluralize
import com.lionotter.recipes.util.singularize

@Composable
internal fun IngredientSectionContent(
    section: IngredientSection,
    scale: Double,
    measurementPreference: MeasurementPreference,
    globalIngredientUsage: Map<String, IngredientUsageStatus>,
    volumeUnitSystem: UnitSystem = UnitSystem.CUSTOMARY,
    weightUnitSystem: UnitSystem = UnitSystem.METRIC
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
            val usage = globalIngredientUsage[ingredient.name.lowercase()]
            val isFullyUsed = usage?.isFullyUsed == true
            val hasPartialUsage = usage != null && usage.usedAmount > 0 && !isFullyUsed

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = "\u2022",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isFullyUsed) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column {
                    Text(
                        text = ingredient.format(scale, measurementPreference, volumeUnitSystem, weightUnitSystem),
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (isFullyUsed) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (isFullyUsed) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
                    )
                    // Show remaining amount if partially used
                    if (hasPartialUsage && usage?.remainingAmount != null && usage.remainingAmount > 0) {
                        Text(
                            text = formatRemainingAmount(usage.remainingAmount, usage.unit),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            // Display alternates
            ingredient.alternates.forEach { alternate ->
                val altUsage = globalIngredientUsage[alternate.name.lowercase()]
                val altIsFullyUsed = altUsage?.isFullyUsed == true
                val altHasPartialUsage = altUsage != null && altUsage.usedAmount > 0 && !altIsFullyUsed

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp, horizontal = 24.dp)
                ) {
                    Text(
                        text = stringResource(R.string.or),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Column {
                        Text(
                            text = alternate.format(scale, measurementPreference, volumeUnitSystem, weightUnitSystem),
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (altIsFullyUsed) TextDecoration.LineThrough else TextDecoration.None,
                            color = if (altIsFullyUsed) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Show remaining amount if partially used
                        if (altHasPartialUsage && altUsage?.remainingAmount != null && altUsage.remainingAmount > 0) {
                            Text(
                                text = formatRemainingAmount(altUsage.remainingAmount, altUsage.unit),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }

        if (section.name != null) {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Formats the remaining amount for display (e.g., "1/2 cup left").
 */
internal fun formatRemainingAmount(amount: Double, unit: String?): String {
    val formattedAmount = formatQuantity(amount)
    return if (unit != null) {
        val count = if (amount > 1.0) 2 else 1
        val pluralizedUnit = unit.singularize().pluralize(count)
        "$formattedAmount $pluralizedUnit left"
    } else {
        "$formattedAmount left"
    }
}

internal fun formatQuantity(qty: Double): String {
    return if (qty == qty.toLong().toDouble()) {
        qty.toLong().toString()
    } else {
        val fractions = mapOf(
            0.25 to "1/4",
            0.33 to "1/3",
            0.5 to "1/2",
            0.66 to "2/3",
            0.75 to "3/4"
        )
        val whole = qty.toLong()
        val decimal = qty - whole

        val fraction = fractions.entries.minByOrNull {
            kotlin.math.abs(it.key - decimal)
        }?.takeIf {
            kotlin.math.abs(it.key - decimal) < 0.05
        }?.value

        when {
            fraction != null && whole > 0 -> "$whole $fraction"
            fraction != null -> fraction
            else -> "%.2f".format(qty).trimEnd('0').trimEnd('.')
        }
    }
}
