package com.lionotter.recipes.ui.screens.importdebug

import android.content.ClipData
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lionotter.recipes.R
import com.lionotter.recipes.data.local.ImportDebugEntity
import com.lionotter.recipes.ui.components.RecipeTopAppBar
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun ImportDebugDetailScreen(
    onBackClick: () -> Unit,
    onNavigateToRecipe: ((String) -> Unit)? = null,
    viewModel: ImportDebugDetailViewModel = hiltViewModel()
) {
    val entry by viewModel.entry.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            RecipeTopAppBar(
                title = entry?.recipeName ?: entry?.sourceUrl ?: stringResource(R.string.import_debug_data),
                onBackClick = onBackClick
            )
        }
    ) { paddingValues ->
        val currentEntry = entry
        if (currentEntry == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            SelectionContainer {
                DebugDetailContent(
                    entry = currentEntry,
                    onNavigateToRecipe = onNavigateToRecipe,
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DebugDetailContent(
    entry: ImportDebugEntity,
    onNavigateToRecipe: ((String) -> Unit)?,
    modifier: Modifier = Modifier
) {
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.summary),
        stringResource(R.string.original_content),
        stringResource(R.string.cleaned_content),
        stringResource(R.string.ai_output)
    )

    Column(modifier = modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTabIndex) {
            0 -> SummaryTab(entry = entry, onNavigateToRecipe = onNavigateToRecipe)
            1 -> HtmlContentTab(content = entry.originalHtml)
            2 -> HtmlContentTab(content = entry.cleanedContent)
            3 -> AiOutputTab(jsonString = entry.aiOutputJson)
        }
    }
}

@Composable
private fun SummaryTab(
    entry: ImportDebugEntity,
    onNavigateToRecipe: ((String) -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryRow(
            label = stringResource(R.string.source_url_label),
            value = entry.sourceUrl ?: stringResource(R.string.not_available)
        )

        HorizontalDivider()

        SummaryRow(
            label = stringResource(R.string.source_length_raw),
            value = stringResource(R.string.characters_count, formatNumber(entry.originalLength))
        )

        SummaryRow(
            label = stringResource(R.string.source_length_cleaned),
            value = stringResource(R.string.characters_count, formatNumber(entry.cleanedLength))
        )

        HorizontalDivider()

        SummaryRow(
            label = stringResource(R.string.input_tokens_label),
            value = if (entry.inputTokens != null) {
                stringResource(R.string.tokens_count, formatNumber(entry.inputTokens))
            } else {
                stringResource(R.string.not_available)
            }
        )

        SummaryRow(
            label = stringResource(R.string.output_tokens_label),
            value = if (entry.outputTokens != null) {
                stringResource(R.string.tokens_count, formatNumber(entry.outputTokens))
            } else {
                stringResource(R.string.not_available)
            }
        )

        HorizontalDivider()

        SummaryRow(
            label = stringResource(R.string.ai_model_label),
            value = entry.aiModel ?: stringResource(R.string.not_available)
        )

        SummaryRow(
            label = stringResource(R.string.thinking_mode_label),
            value = if (entry.thinkingEnabled) stringResource(R.string.enabled) else stringResource(R.string.disabled)
        )

        if (entry.inputTokens != null && entry.outputTokens != null && entry.aiModel != null) {
            SummaryRow(
                label = stringResource(R.string.ai_cost_label),
                value = estimateCost(entry.aiModel, entry.inputTokens, entry.outputTokens, entry.thinkingEnabled)
            )
        }

        if (entry.durationMs != null) {
            SummaryRow(
                label = stringResource(R.string.ai_duration_label),
                value = formatDuration(entry.durationMs)
            )
        }

        HorizontalDivider()

        if (entry.isError) {
            SummaryRow(
                label = stringResource(R.string.error_message_label),
                value = entry.errorMessage ?: stringResource(R.string.not_available)
            )
        } else if (entry.recipeId != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.imported_recipe_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = entry.recipeName ?: entry.recipeId,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (onNavigateToRecipe != null) {
                    TextButton(onClick = { onNavigateToRecipe(entry.recipeId) }) {
                        Text(text = stringResource(R.string.recipe))
                    }
                }
            }
        }

        SummaryRow(
            label = "Timestamp",
            value = formatTimestamp(entry.createdAt)
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun HtmlContentTab(content: String?) {
    if (content.isNullOrBlank()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.not_available),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    var showSource by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { showSource = !showSource }) {
                Text(
                    text = if (showSource) stringResource(R.string.show_rendered) else stringResource(R.string.show_source)
                )
            }
        }

        if (showSource) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(16.dp)
            )
        } else {
            val isHtml = content.contains("<") && content.contains(">")
            if (isHtml) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            webViewClient = WebViewClient()
                            settings.javaScriptEnabled = false
                            loadDataWithBaseURL(null, content, "text/html", "UTF-8", null)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun AiOutputTab(jsonString: String?) {
    if (jsonString.isNullOrBlank()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.not_available),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val jsonElement = remember(jsonString) {
        try {
            Json.parseToJsonElement(jsonString)
        } catch (e: Exception) {
            null
        }
    }

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val prettyJson = remember(jsonString) {
        try {
            val json = Json { prettyPrint = true }
            val element = json.parseToJsonElement(jsonString)
            json.encodeToString(JsonElement.serializer(), element)
        } catch (e: Exception) {
            jsonString
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (jsonElement != null) {
                JsonTreeView(element = jsonElement, depth = 0)
            } else {
                Text(
                    text = jsonString,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }

        IconButton(
            onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("JSON", prettyJson))) } },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(40.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.copy_json)
            )
        }
    }
}

@Composable
private fun JsonTreeView(
    element: JsonElement,
    depth: Int,
    key: String? = null
) {
    val indent = "  ".repeat(depth)
    when (element) {
        is JsonObject -> {
            if (key != null) {
                Text(
                    text = "$indent\"$key\": {",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = "$indent{",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
            element.entries.forEach { (k, v) ->
                JsonTreeView(element = v, depth = depth + 1, key = k)
            }
            Text(
                text = "$indent}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }
        is JsonArray -> {
            if (key != null) {
                Text(
                    text = "$indent\"$key\": [",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = "$indent[",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
            element.forEach { item ->
                JsonTreeView(element = item, depth = depth + 1)
            }
            Text(
                text = "$indent]",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }
        is JsonPrimitive -> {
            val valueStr = element.toString()
            val text = if (key != null) {
                "$indent\"$key\": $valueStr"
            } else {
                "$indent$valueStr"
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (key != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.secondary
            )
        }
        is JsonNull -> {
            val text = if (key != null) "$indent\"$key\": null" else "${indent}null"
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatNumber(value: Long): String {
    return String.format("%,d", value)
}

private fun formatNumber(value: Int): String {
    return String.format("%,d", value)
}

private fun formatTimestamp(epochMillis: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMillis)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${localDateTime.year}-${localDateTime.month.number.toString().padStart(2, '0')}-${localDateTime.day.toString().padStart(2, '0')} ${localDateTime.hour.toString().padStart(2, '0')}:${localDateTime.minute.toString().padStart(2, '0')}:${localDateTime.second.toString().padStart(2, '0')}"
}

private fun estimateCost(model: String, inputTokens: Long, outputTokens: Long, thinkingEnabled: Boolean): String {
    // Pricing per million tokens from https://platform.claude.com/docs/en/about-claude/pricing
    val (inputCostPerM, outputCostPerM) = when {
        model.contains("opus-4-5") || model.contains("opus-4-6") -> 5.0 to 25.0
        model.contains("opus") -> 15.0 to 75.0
        model.contains("sonnet") -> 3.0 to 15.0
        model.contains("haiku-4-5") -> 1.0 to 5.0
        model.contains("haiku-3-5") -> 0.80 to 4.0
        model.contains("haiku") -> 0.25 to 1.25
        else -> 3.0 to 15.0
    }
    val inputCost = (inputTokens / 1_000_000.0) * inputCostPerM
    val outputCost = (outputTokens / 1_000_000.0) * outputCostPerM
    val totalCost = inputCost + outputCost
    return "$${String.format("%.4f", totalCost)}"
}

private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000.0
    return if (seconds >= 60) {
        val minutes = (seconds / 60).toInt()
        val remainingSeconds = seconds % 60
        "${minutes}m ${String.format("%.1f", remainingSeconds)}s"
    } else {
        "${String.format("%.1f", seconds)}s"
    }
}
