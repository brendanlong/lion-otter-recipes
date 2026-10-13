package com.lionotter.recipes.ui.screens.recipedetail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.InstructionIngredientKey
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.model.createInstructionIngredientKey
import com.lionotter.recipes.ui.screens.recipedetail.HighlightedInstructionStep
import androidx.compose.foundation.border
import com.lionotter.recipes.ui.theme.einkBorder

@Composable
internal fun InstructionSectionContent(
    section: InstructionSection,
    sectionIndex: Int,
    scale: Double,
    measurementPreference: MeasurementPreference,
    usedInstructionIngredients: Set<InstructionIngredientKey>,
    onToggleIngredient: (Int, Int, Int) -> Unit,
    highlightedInstructionStep: HighlightedInstructionStep?,
    onToggleHighlightedInstruction: (Int, Int) -> Unit,
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

        section.steps.forEachIndexed { stepIndex, step ->
            val isHighlighted = highlightedInstructionStep?.sectionIndex == sectionIndex &&
                                highlightedInstructionStep.stepIndex == stepIndex
            val currentStepDescription = stringResource(R.string.current_step)
            val highlightLabel = stringResource(
                if (isHighlighted) R.string.unmark_current_step else R.string.mark_current_step
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .then(
                        if (isHighlighted) {
                            Modifier.background(MaterialTheme.colorScheme.tertiaryContainer)
                        } else {
                            Modifier
                        }
                    )
                    .then(
                        // The e-ink theme has no tinted background to mark the step with.
                        einkBorder(2.dp)
                            ?.takeIf { isHighlighted }
                            ?.let { Modifier.border(it, MaterialTheme.shapes.medium) }
                            ?: Modifier
                    )
                    .clickable(onClickLabel = highlightLabel) {
                        onToggleHighlightedInstruction(sectionIndex, stepIndex)
                    }
                    .semantics {
                        if (isHighlighted) stateDescription = currentStepDescription
                    }
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
                            MaterialTheme.colorScheme.onTertiaryContainer
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                }

                // Step-level ingredients: each ingredient (with its alternates) is one
                // full-width checkbox row that can be ticked off while cooking.
                if (step.ingredients.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 28.dp, top = 4.dp)
                    ) {
                        step.ingredients.forEachIndexed { ingredientIndex, ingredient ->
                            val ingredientKey = createInstructionIngredientKey(sectionIndex, stepIndex, ingredientIndex)
                            val isUsed = ingredientKey in usedInstructionIngredients

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .toggleable(
                                        value = isUsed,
                                        role = Role.Checkbox,
                                        onValueChange = {
                                            onToggleIngredient(sectionIndex, stepIndex, ingredientIndex)
                                        }
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isUsed,
                                    onCheckedChange = null,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text(
                                        text = ingredient.format(scale, measurementPreference, volumeUnitSystem, weightUnitSystem),
                                        style = MaterialTheme.typography.bodyLarge,
                                        textDecoration = if (isUsed) TextDecoration.LineThrough else TextDecoration.None,
                                        color = if (isUsed) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
                                    )
                                    ingredient.alternates.forEach { alternate ->
                                        Text(
                                            text = stringResource(
                                                R.string.or_alternate,
                                                alternate.format(scale, measurementPreference, volumeUnitSystem, weightUnitSystem)
                                            ),
                                            style = MaterialTheme.typography.bodyMedium,
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
        }

        if (section.name != null) {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
