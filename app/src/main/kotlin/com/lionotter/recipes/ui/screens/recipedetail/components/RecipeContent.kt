package com.lionotter.recipes.ui.screens.recipedetail.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.lionotter.recipes.R
import com.lionotter.recipes.data.image.imageModel
import com.lionotter.recipes.domain.model.IngredientUsageStatus
import com.lionotter.recipes.domain.model.InstructionIngredientKey
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.ui.TestTags
import com.lionotter.recipes.ui.components.rememberScrollAids
import com.lionotter.recipes.ui.components.scrollAids
import com.lionotter.recipes.ui.screens.recipedetail.HighlightedInstructionStep
import com.lionotter.recipes.ui.theme.einkBorder

private const val TAG = "RecipeContent"

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
    ingredientUsageBySection: Map<String?, Map<String, IngredientUsageStatus>>,
    onToggleInstructionIngredient: (Int, Int, Int) -> Unit,
    highlightedInstructionStep: HighlightedInstructionStep?,
    onToggleHighlightedInstruction: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    notesText: String = recipe.userNotes ?: "",
    onNotesChange: (String) -> Unit = {},
    onNotesFocusLost: () -> Unit = {},
    onTagClick: (String) -> Unit = {},
    onOpenLinkFailed: () -> Unit = {},
    checkedAggregateIngredients: Set<String> = emptySet(),
    onToggleAggregateIngredient: ((String) -> Unit)? = null,
    volumeUnitSystem: UnitSystem = UnitSystem.localeDefault(),
    weightUnitSystem: UnitSystem = UnitSystem.localeDefault()
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(TestTags.RECIPE_DETAIL_CONTENT)
            .scrollAids(rememberScrollAids(scrollState))
            .verticalScroll(scrollState)
    ) {
        // Hero image (only shown if image loads successfully)
        recipe.imageUrl?.let { imageUrl ->
            var showImage by remember(imageUrl) { mutableStateOf(true) }
            if (showImage) {
                SubcomposeAsyncImage(
                    model = imageModel(imageUrl),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Crop,
                    loading = { },
                    error = { showImage = false },
                    success = { SubcomposeAsyncImageContent() }
                )
            }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            // Tags
            if (recipe.tags.isNotEmpty()) {
                DisableSelection {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        recipe.tags.forEach { tag ->
                            SuggestionChip(
                                onClick = { onTagClick(tag) },
                                label = { Text(tag) }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Time and servings info
            RecipeMetadata(recipe = recipe)

            // Story
            recipe.story?.let { story ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    border = einkBorder(),
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
            DisableSelection {
                ScaleControl(
                    scale = scale,
                    servings = recipe.servings,
                    onIncrement = onScaleIncrement,
                    onDecrement = onScaleDecrement
                )
            }

            // Measurement toggle (only shown if recipe has ingredients with density for conversion)
            if (showMeasurementToggle) {
                Spacer(modifier = Modifier.height(16.dp))
                DisableSelection {
                    MeasurementToggle(
                        selectedPreference = measurementPreference,
                        onPreferenceChange = onMeasurementPreferenceChange
                    )
                }
            }

            // Ingredients (aggregated from steps)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.ingredients),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .testTag(TestTags.INGREDIENTS_SECTION)
                    .semantics { heading() }
            )
            Spacer(modifier = Modifier.height(12.dp))
            val aggregatedSections = remember(recipe) { recipe.aggregateIngredients() }
            aggregatedSections.forEach { section ->
                IngredientSectionContent(
                    section = section,
                    scale = scale,
                    measurementPreference = measurementPreference,
                    ingredientUsage = ingredientUsageBySection[section.name] ?: emptyMap(),
                    checkedIngredients = checkedAggregateIngredients,
                    onToggleChecked = onToggleAggregateIngredient,
                    volumeUnitSystem = volumeUnitSystem,
                    weightUnitSystem = weightUnitSystem
                )
            }

            // Equipment
            if (recipe.equipment.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.equipment),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(modifier = Modifier.height(12.dp))
                recipe.equipment.forEach { item ->
                    Text(
                        text = "• $item",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                    )
                }
            }

            // Instructions
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.instructions),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .testTag(TestTags.INSTRUCTIONS_SECTION)
                    .semantics { heading() }
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

            // Notes
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.notes),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(12.dp))
            DisableSelection {
                var wasFocused by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = notesText,
                    onValueChange = onNotesChange,
                    placeholder = { Text(stringResource(R.string.add_notes)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            if (wasFocused && !focusState.isFocused) onNotesFocusLost()
                            wasFocused = focusState.isFocused
                        },
                    minLines = 3
                )
            }

            // Source
            recipe.sourceUrl?.let { url ->
                val context = LocalContext.current
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.source_prefix, url),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                            } catch (e: ActivityNotFoundException) {
                                Log.w(TAG, "No app to open $url", e)
                                onOpenLinkFailed()
                            }
                        }
                        .wrapContentHeight(Alignment.CenterVertically)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
