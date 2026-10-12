package com.lionotter.recipes.ui.screens.importdebug

import android.content.ClipData
import android.content.Intent
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lionotter.recipes.R
import com.lionotter.recipes.data.local.ImportDebugSummary
import com.lionotter.recipes.ui.components.ErrorCard
import com.lionotter.recipes.ui.components.RecipeTopAppBar
import com.lionotter.recipes.util.ErrorLog
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
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

private const val TAG = "ImportDebugDetail"

/** JSON larger than this is shown as raw text instead of a composable tree. */
private const val MAX_JSON_TREE_CHARS = 100_000

/** Max characters per lazily-composed text block when showing raw content. */
private const val DISPLAY_CHUNK_CHARS = 4_000

@Composable
fun ImportDebugDetailScreen(
    onBackClick: () -> Unit,
    onNavigateToRecipe: ((String) -> Unit)? = null,
    viewModel: ImportDebugDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val summary = (state as? ImportDebugDetailState.Loaded)?.summary

    Scaffold(
        topBar = {
            RecipeTopAppBar(
                title = summary?.recipeName ?: summary?.sourceUrl ?: stringResource(R.string.import_debug_data),
                onBackClick = onBackClick
            )
        }
    ) { paddingValues ->
        when (val currentState = state) {
            ImportDebugDetailState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            ImportDebugDetailState.NotFound -> {
                CenteredMessage(
                    message = stringResource(R.string.import_debug_entry_not_found),
                    modifier = Modifier.padding(paddingValues)
                )
            }
            is ImportDebugDetailState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ErrorCard(
                        message = stringResource(R.string.import_debug_load_failed, currentState.message)
                    )
                }
            }
            is ImportDebugDetailState.Loaded -> {
                DebugDetailContent(
                    state = currentState,
                    onNavigateToRecipe = onNavigateToRecipe,
                    onShareFullContent = viewModel::writeFullContentToFile,
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}

@Composable
private fun CenteredMessage(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DebugDetailContent(
    state: ImportDebugDetailState.Loaded,
    onNavigateToRecipe: ((String) -> Unit)?,
    onShareFullContent: suspend (DebugContentField) -> java.io.File,
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
        PrimaryScrollableTabRow(selectedTabIndex = selectedTabIndex, edgePadding = 8.dp) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTabIndex) {
            0 -> SelectionContainer {
                SummaryTab(entry = state.summary, onNavigateToRecipe = onNavigateToRecipe)
            }
            1 -> HtmlContentTab(
                field = DebugContentField.ORIGINAL_HTML,
                preview = state.contents[DebugContentField.ORIGINAL_HTML],
                onShareFullContent = onShareFullContent
            )
            2 -> HtmlContentTab(
                field = DebugContentField.CLEANED_CONTENT,
                preview = state.contents[DebugContentField.CLEANED_CONTENT],
                onShareFullContent = onShareFullContent
            )
            3 -> AiOutputTab(
                preview = state.contents[DebugContentField.AI_OUTPUT],
                onShareFullContent = onShareFullContent
            )
        }
    }
}

@Composable
private fun SummaryTab(
    entry: ImportDebugSummary,
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
                value = estimateCost(entry.aiModel, entry.inputTokens, entry.outputTokens, entry.thinkingEnabled, entry.batchMode)
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
            label = stringResource(R.string.timestamp_label),
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

/**
 * Toolbar for a content tab: truncation notice plus copy (displayed text) and share
 * (full content, written to a file since it can exceed Binder/clipboard limits).
 */
@Composable
private fun ContentActions(
    field: DebugContentField,
    preview: DebugContentPreview,
    copyText: String,
    onShareFullContent: suspend (DebugContentField) -> java.io.File,
    modifier: Modifier = Modifier,
    extraActions: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.json_label)
    val shareFailedMessage = stringResource(R.string.import_debug_share_failed)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (preview.isTruncated) {
                stringResource(
                    R.string.import_debug_truncated,
                    formatNumber(preview.text.length),
                    formatNumber(preview.totalLength)
                )
            } else {
                stringResource(R.string.characters_count, formatNumber(preview.totalLength))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        extraActions()
        if (!preview.isTruncated) {
            IconButton(onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, copyText)))
                }
            }) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.import_debug_copy_content)
                )
            }
        }
        IconButton(onClick = {
            scope.launch {
                try {
                    val file = onShareFullContent(field)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, null))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorLog.e(TAG, "Failed to share debug content", e)
                    Toast.makeText(context, shareFailedMessage, Toast.LENGTH_LONG).show()
                }
            }
        }) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = stringResource(R.string.import_debug_share_full_content)
            )
        }
    }
}

/**
 * Renders long text as a lazy list of bounded chunks so huge (minified) content doesn't
 * freeze the UI laying out a single giant Text.
 */
@Composable
private fun ChunkedText(
    text: String,
    monospace: Boolean,
    modifier: Modifier = Modifier
) {
    val chunks = remember(text) { splitForDisplay(text, DISPLAY_CHUNK_CHARS) }
    val style = if (monospace) {
        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    } else {
        MaterialTheme.typography.bodySmall
    }
    SelectionContainer(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp)
        ) {
            itemsIndexed(chunks) { _, chunk ->
                Text(text = chunk, style = style)
            }
        }
    }
}

/** Splits [text] into pieces of at most [maxChars], preferring to break after newlines. */
internal fun splitForDisplay(text: String, maxChars: Int): List<String> {
    if (text.length <= maxChars) return listOf(text)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + maxChars, text.length)
        if (end < text.length) {
            val newline = text.lastIndexOf('\n', end - 1)
            if (newline >= start + maxChars / 2) end = newline + 1
        }
        chunks.add(text.substring(start, end).removeSuffix("\n"))
        start = end
    }
    return chunks
}

@Composable
private fun NotAvailableContent() {
    CenteredMessage(message = stringResource(R.string.not_available))
}

@Composable
private fun HtmlContentTab(
    field: DebugContentField,
    preview: DebugContentPreview?,
    onShareFullContent: suspend (DebugContentField) -> java.io.File
) {
    if (preview == null || preview.text.isBlank()) {
        NotAvailableContent()
        return
    }
    val content = preview.text

    var showSource by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        ContentActions(
            field = field,
            preview = preview,
            copyText = content,
            onShareFullContent = onShareFullContent,
            extraActions = {
                TextButton(onClick = { showSource = !showSource }) {
                    Text(
                        text = if (showSource) stringResource(R.string.show_rendered) else stringResource(R.string.show_source)
                    )
                }
            }
        )

        if (showSource) {
            ChunkedText(text = content, monospace = true, modifier = Modifier.fillMaxSize())
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
                ChunkedText(text = content, monospace = false, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun AiOutputTab(
    preview: DebugContentPreview?,
    onShareFullContent: suspend (DebugContentField) -> java.io.File
) {
    if (preview == null || preview.text.isBlank()) {
        NotAvailableContent()
        return
    }
    val jsonString = preview.text

    val jsonElement = remember(jsonString) {
        if (preview.isTruncated || jsonString.length > MAX_JSON_TREE_CHARS) {
            null
        } else {
            try {
                Json.parseToJsonElement(jsonString)
            } catch (e: Exception) {
                Log.w(TAG, "AI output is not valid JSON; showing raw text", e)
                null
            }
        }
    }

    val prettyJson = remember(jsonElement) {
        jsonElement?.let { prettyJsonFormat.encodeToString(JsonElement.serializer(), it) } ?: jsonString
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ContentActions(
            field = DebugContentField.AI_OUTPUT,
            preview = preview,
            copyText = prettyJson,
            onShareFullContent = onShareFullContent
        )
        if (jsonElement != null) {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    JsonTreeView(element = jsonElement, depth = 0)
                    Spacer(modifier = Modifier.padding(bottom = 16.dp))
                }
            }
        } else {
            ChunkedText(text = jsonString, monospace = true, modifier = Modifier.fillMaxSize())
        }
    }
}

private val prettyJsonFormat = Json { prettyPrint = true }

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

private fun estimateCost(model: String, inputTokens: Long, outputTokens: Long, thinkingEnabled: Boolean, batchMode: Boolean = false): String {
    // Pricing per million tokens from https://platform.claude.com/docs/en/about-claude/pricing
    // Batch mode is 50% cheaper than standard API calls
    val (baseInput, baseOutput) = when {
        model.contains("fable") -> 10.0 to 50.0
        model.contains("opus-5-5") -> 4.0 to 20.0
        model.contains("opus-5") || model.contains("opus-4-5") || model.contains("opus-4-6") ||
            model.contains("opus-4-7") || model.contains("opus-4-8") -> 5.0 to 25.0
        model.contains("opus") -> 15.0 to 75.0
        model.contains("sonnet-5") -> 2.0 to 10.0
        model.contains("sonnet") -> 3.0 to 15.0
        model.contains("haiku-4-5") -> 1.0 to 5.0
        model.contains("haiku-3-5") -> 0.80 to 4.0
        model.contains("haiku") -> 0.25 to 1.25
        else -> 3.0 to 15.0
    }
    val multiplier = if (batchMode) 0.5 else 1.0
    val inputCost = (inputTokens / 1_000_000.0) * baseInput * multiplier
    val outputCost = (outputTokens / 1_000_000.0) * baseOutput * multiplier
    val totalCost = inputCost + outputCost
    val suffix = if (batchMode) " (batch)" else ""
    return "$${String.format("%.4f", totalCost)}$suffix"
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
