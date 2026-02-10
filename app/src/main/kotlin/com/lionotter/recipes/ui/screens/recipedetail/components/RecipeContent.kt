package com.lionotter.recipes.ui.screens.recipedetail.components

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.IngredientUsageStatus
import com.lionotter.recipes.domain.model.InstructionIngredientKey
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.ui.screens.recipedetail.HighlightedInstructionStep

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipeContent(
    recipe: Recipe,
    scale: Double,
    onScaleIncrement: () -> Unit,
    onScaleDecrement: () -> Unit,
    measurementPreference: MeasurementPreference,
    onMeasurementPreferenceChange: (MeasurementPreference) -> Unit,
    showMeasurementToggle: Boolean,
    usedInstructionIngredients: Set<InstructionIngredientKey>,
    globalIngredientUsage: Map<String, IngredientUsageStatus>,
    onToggleInstructionIngredient: (Int, Int, Int) -> Unit,
    highlightedInstructionStep: HighlightedInstructionStep?,
    onToggleHighlightedInstruction: (Int, Int) -> Unit,
    volumeUnitSystem: UnitSystem = UnitSystem.CUSTOMARY,
    weightUnitSystem: UnitSystem = UnitSystem.METRIC,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Hero image
        recipe.imageUrl?.let { imageUrl ->
            AsyncImage(
                model = imageUrl,
                contentDescription = recipe.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Crop
            )
        }

        Column(modifier = Modifier.padding(16.dp)) {
            // Tags
            if (recipe.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    recipe.tags.forEach { tag ->
                        SuggestionChip(
                            onClick = { },
                            label = { Text(tag) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Time and servings info
            RecipeMetadata(recipe = recipe, scale = scale)

            // Story
            recipe.story?.let { story ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = story,
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // Scale controls
            Spacer(modifier = Modifier.height(24.dp))
            ScaleControl(
                scale = scale,
                onIncrement = onScaleIncrement,
                onDecrement = onScaleDecrement
            )

            // Measurement toggle (only shown if recipe has ingredients with density for conversion)
            if (showMeasurementToggle) {
                Spacer(modifier = Modifier.height(16.dp))
                MeasurementToggle(
                    selectedPreference = measurementPreference,
                    onPreferenceChange = onMeasurementPreferenceChange
                )
            }

            // Ingredients (aggregated from steps)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.ingredients),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            val aggregatedSections = recipe.aggregateIngredients()
            aggregatedSections.forEach { section ->
                IngredientSectionContent(
                    section = section,
                    scale = scale,
                    measurementPreference = measurementPreference,
                    globalIngredientUsage = globalIngredientUsage,
                    volumeUnitSystem = volumeUnitSystem,
                    weightUnitSystem = weightUnitSystem
                )
            }

            // Instructions
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.instructions),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            recipe.instructionSections.forEachIndexed { sectionIndex, section ->
                InstructionSectionContent(
                    section = section,
                    sectionIndex = sectionIndex,
                    scale = scale,
                    measurementPreference = measurementPreference,
                    usedInstructionIngredients = usedInstructionIngredients,
                    onToggleIngredient = onToggleInstructionIngredient,
                    highlightedInstructionStep = highlightedInstructionStep,
                    onToggleHighlightedInstruction = onToggleHighlightedInstruction,
                    volumeUnitSystem = volumeUnitSystem,
                    weightUnitSystem = weightUnitSystem
                )
            }

            // Source
            recipe.sourceUrl?.let { url ->
                val context = LocalContext.current
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.source_prefix, url),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                        context.startActivity(intent)
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
