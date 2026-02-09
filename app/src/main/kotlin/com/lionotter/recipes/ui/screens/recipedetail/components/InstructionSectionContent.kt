package com.lionotter.recipes.ui.screens.recipedetail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.InstructionIngredientKey
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.createInstructionIngredientKey
import com.lionotter.recipes.ui.screens.recipedetail.HighlightedInstructionStep

@Composable
internal fun InstructionSectionContent(
    section: InstructionSection,
    sectionIndex: Int,
    scale: Double,
    measurementPreference: MeasurementPreference,
    usedInstructionIngredients: Set<InstructionIngredientKey>,
    onToggleIngredient: (Int, Int, Int) -> Unit,
    highlightedInstructionStep: HighlightedInstructionStep?,
    onToggleHighlightedInstruction: (Int, Int) -> Unit
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

        section.steps.forEachIndexed { stepIndex, step ->
            val isHighlighted = highlightedInstructionStep?.sectionIndex == sectionIndex &&
                                highlightedInstructionStep?.stepIndex == stepIndex

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clickable { onToggleHighlightedInstruction(sectionIndex, stepIndex) }
                    .then(
                        if (isHighlighted) {
                            Modifier
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isHighlighted)
                                MaterialTheme.colorScheme.secondaryContainer
                            else
                                MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(
                            text = "${step.stepNumber}",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isHighlighted)
                                MaterialTheme.colorScheme.onSecondaryContainer
                            else
                                MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        text = step.instruction,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                        color = if (isHighlighted)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                }

                // Display step-level ingredients if present
                if (step.ingredients.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 40.dp, top = 8.dp)
                    ) {
                        step.ingredients.forEachIndexed { ingredientIndex, ingredient ->
                            val ingredientKey = createInstructionIngredientKey(sectionIndex, stepIndex, ingredientIndex)
                            val isUsed = ingredientKey in usedInstructionIngredients

                            // Main ingredient row - clickable
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleIngredient(sectionIndex, stepIndex, ingredientIndex) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isUsed) "\u2713" else "\u2022",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isUsed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    text = ingredient.format(scale, measurementPreference),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textDecoration = if (isUsed) TextDecoration.LineThrough else TextDecoration.None,
                                    color = if (isUsed) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
                                )
                            }

                            // Display alternates for step ingredients - also clickable (same toggle)
                            ingredient.alternates.forEach { alternate ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onToggleIngredient(sectionIndex, stepIndex, ingredientIndex) }
                                        .padding(vertical = 2.dp, horizontal = 24.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.or),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(
                                        text = alternate.format(scale, measurementPreference),
                                        style = MaterialTheme.typography.bodySmall,
                                        textDecoration = if (isUsed) TextDecoration.LineThrough else TextDecoration.None,
                                        color = if (isUsed) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
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
