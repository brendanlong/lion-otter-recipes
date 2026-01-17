package com.lionotter.recipes.ui.screens.recipedetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import coil.compose.AsyncImage
import com.lionotter.recipes.domain.model.IngredientSection
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.MeasurementType
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.util.pluralize
import com.lionotter.recipes.util.singularize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    onBackClick: () -> Unit,
    viewModel: RecipeDetailViewModel = hiltViewModel()
) {
    val recipe by viewModel.recipe.collectAsStateWithLifecycle()
    val scale by viewModel.scale.collectAsStateWithLifecycle()
    val measurementPreference by viewModel.measurementPreference.collectAsStateWithLifecycle()
    val hasMultipleMeasurementTypes by viewModel.hasMultipleMeasurementTypes.collectAsStateWithLifecycle()
    val availableMeasurementTypes by viewModel.availableMeasurementTypes.collectAsStateWithLifecycle()
    val usedInstructionIngredients by viewModel.usedInstructionIngredients.collectAsStateWithLifecycle()
    val globalIngredientUsage by viewModel.globalIngredientUsage.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recipe?.name ?: "Recipe") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        if (recipe == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            RecipeContent(
                recipe = recipe!!,
                scale = scale,
                onScaleIncrement = viewModel::incrementScale,
                onScaleDecrement = viewModel::decrementScale,
                measurementPreference = measurementPreference,
                onMeasurementPreferenceChange = viewModel::setMeasurementPreference,
                showMeasurementToggle = hasMultipleMeasurementTypes,
                availableMeasurementTypes = availableMeasurementTypes,
                usedInstructionIngredients = usedInstructionIngredients,
                globalIngredientUsage = globalIngredientUsage,
                onToggleInstructionIngredient = viewModel::toggleInstructionIngredientUsed,
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeContent(
    recipe: Recipe,
    scale: Double,
    onScaleIncrement: () -> Unit,
    onScaleDecrement: () -> Unit,
    measurementPreference: MeasurementPreference,
    onMeasurementPreferenceChange: (MeasurementPreference) -> Unit,
    showMeasurementToggle: Boolean,
    availableMeasurementTypes: Set<MeasurementType>,
    usedInstructionIngredients: Set<InstructionIngredientKey>,
    globalIngredientUsage: Map<String, IngredientUsageStatus>,
    onToggleInstructionIngredient: (Int, Int, Int) -> Unit,
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

            // Measurement toggle (only shown if recipe has multiple measurement types)
            if (showMeasurementToggle) {
                Spacer(modifier = Modifier.height(16.dp))
                MeasurementToggle(
                    selectedPreference = measurementPreference,
                    onPreferenceChange = onMeasurementPreferenceChange,
                    availableMeasurementTypes = availableMeasurementTypes
                )
            }

            // Ingredients
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Ingredients",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            recipe.ingredientSections.forEach { section ->
                IngredientSectionContent(
                    section = section,
                    scale = scale,
                    measurementPreference = measurementPreference,
                    globalIngredientUsage = globalIngredientUsage
                )
            }

            // Instructions
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Instructions",
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
                    onToggleIngredient = onToggleInstructionIngredient
                )
            }

            // Source
            recipe.sourceUrl?.let { url ->
                val context = LocalContext.current
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Source: $url",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivity(intent)
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RecipeMetadata(recipe: Recipe, scale: Double) {
    val items = buildList {
        recipe.prepTime?.let { add("Prep: $it") }
        recipe.cookTime?.let { add("Cook: $it") }
        recipe.totalTime?.let { add("Total: $it") }
        recipe.servings?.let {
            val scaled = (it * scale).let { s ->
                if (s == s.toLong().toDouble()) s.toLong().toString()
                else "%.1f".format(s)
            }
            add("Servings: $scaled")
        }
    }

    if (items.isNotEmpty()) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
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

@Composable
private fun ScaleControl(
    scale: Double,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Scale:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(onClick = onDecrement) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Decrease scale"
                )
            }
            Text(
                text = "${scale}x",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            IconButton(onClick = onIncrement) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Increase scale"
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeasurementToggle(
    selectedPreference: MeasurementPreference,
    onPreferenceChange: (MeasurementPreference) -> Unit,
    availableMeasurementTypes: Set<MeasurementType>
) {
    // Determine which options to show based on available measurement types
    val hasVolume = MeasurementType.VOLUME in availableMeasurementTypes
    val hasWeight = MeasurementType.WEIGHT in availableMeasurementTypes

    // Build the list of options to display
    val options = buildList {
        add(MeasurementPreference.ORIGINAL to "Original")
        if (hasVolume) add(MeasurementPreference.VOLUME to "Volume")
        if (hasWeight) add(MeasurementPreference.WEIGHT to "Weight")
    }

    // Only show toggle if there are at least 2 options (Original plus at least one conversion)
    if (options.size < 2) return

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Units",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                options.forEachIndexed { index, (preference, label) ->
                    SegmentedButton(
                        selected = selectedPreference == preference,
                        onClick = { onPreferenceChange(preference) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = options.size
                        )
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun IngredientSectionContent(
    section: IngredientSection,
    scale: Double,
    measurementPreference: MeasurementPreference,
    globalIngredientUsage: Map<String, IngredientUsageStatus>
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
                    text = "•",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isFullyUsed) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column {
                    Text(
                        text = ingredient.format(scale, measurementPreference),
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
                        text = "or",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Column {
                        Text(
                            text = alternate.format(scale, measurementPreference),
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
private fun formatRemainingAmount(amount: Double, unit: String?): String {
    val formattedAmount = formatQuantity(amount)
    return if (unit != null) {
        val count = if (amount > 1.0) 2 else 1
        val pluralizedUnit = unit.singularize().pluralize(count)
        "$formattedAmount $pluralizedUnit left"
    } else {
        "$formattedAmount left"
    }
}

private fun formatQuantity(qty: Double): String {
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

@Composable
private fun InstructionSectionContent(
    section: InstructionSection,
    sectionIndex: Int,
    scale: Double,
    measurementPreference: MeasurementPreference,
    usedInstructionIngredients: Set<InstructionIngredientKey>,
    onToggleIngredient: (Int, Int, Int) -> Unit
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(
                            text = "${step.stepNumber}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        text = step.instruction,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
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
                                    text = if (isUsed) "✓" else "•",
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
                                        text = "or",
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
