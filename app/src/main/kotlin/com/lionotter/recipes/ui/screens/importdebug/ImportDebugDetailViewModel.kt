package com.lionotter.recipes.ui.screens.importdebug

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lionotter.recipes.data.local.ImportDebugDao
import com.lionotter.recipes.data.local.ImportDebugSummary
import com.lionotter.recipes.ui.navigation.Route
import com.lionotter.recipes.util.ErrorLog
import com.lionotter.recipes.util.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** The large text columns of an import debug entry. */
enum class DebugContentField(val column: String) {
    ORIGINAL_HTML("originalHtml"),
    CLEANED_CONTENT("cleanedContent"),
    AI_OUTPUT("aiOutputJson")
}

/**
 * The first [text] characters of a debug content column, capped so the UI never has to lay
 * out or hold multi-megabyte strings. [totalLength] is the full length stored in the database.
 */
data class DebugContentPreview(val text: String, val totalLength: Int) {
    val isTruncated: Boolean get() = text.length < totalLength
}

sealed interface ImportDebugDetailState {
    data object Loading : ImportDebugDetailState
    data object NotFound : ImportDebugDetailState
    data object Error : ImportDebugDetailState
    data class Loaded(
        val summary: ImportDebugSummary,
        val contents: Map<DebugContentField, DebugContentPreview?>
    ) : ImportDebugDetailState
}

@HiltViewModel
class ImportDebugDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val importDebugDao: ImportDebugDao,
    @param:ApplicationContext private val appContext: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val entryId: String = savedStateHandle.toRoute<Route.ImportDebugDetail>().debugEntryId

    private val _state = MutableStateFlow<ImportDebugDetailState>(ImportDebugDetailState.Loading)
    val state: StateFlow<ImportDebugDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = try {
                loadState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Failed to load import debug entry $entryId", e)
                ImportDebugDetailState.Error
            }
        }
    }

    private suspend fun loadState(): ImportDebugDetailState {
        val summary = importDebugDao.getDebugSummaryById(entryId)
            ?: return ImportDebugDetailState.NotFound
        val contents = DebugContentField.entries.associateWith { field ->
            val length = importDebugDao.getContentLength(entryId, field.column)
            if (length == null || length == 0) {
                null
            } else {
                val previewLength = minOf(length, MAX_PREVIEW_CHARS)
                val text = importDebugDao.getContentChunk(entryId, field.column, 1, previewLength) ?: ""
                DebugContentPreview(text, length)
            }
        }
        return ImportDebugDetailState.Loaded(summary, contents)
    }

    /**
     * Writes the full content of [field] to a shareable cache file, reading it from the
     * database in chunks that fit in a CursorWindow.
     */
    suspend fun writeFullContentToFile(field: DebugContentField): File = withContext(ioDispatcher) {
        val dir = File(appContext.cacheDir, SHARE_DIR).apply { mkdirs() }
        val extension = if (field == DebugContentField.AI_OUTPUT) "json" else "txt"
        val file = File(dir, "import-debug-${field.column}.$extension")
        val totalLength = importDebugDao.getContentLength(entryId, field.column) ?: 0
        file.bufferedWriter().use { writer ->
            var start = 1
            while (start <= totalLength) {
                val chunk = importDebugDao.getContentChunk(entryId, field.column, start, READ_CHUNK_CHARS)
                    ?: break
                if (chunk.isEmpty()) break
                writer.write(chunk)
                start += READ_CHUNK_CHARS
            }
        }
        file
    }

    companion object {
        private const val TAG = "ImportDebugDetailVM"

        /** Max characters shown on screen per content tab. */
        const val MAX_PREVIEW_CHARS = 200_000

        /** Chunk size for reading full content; worst-case UTF-8 stays well under 2MB. */
        private const val READ_CHUNK_CHARS = 250_000

        /** Must match a cache-path in res/xml/file_paths.xml. */
        const val SHARE_DIR = "shared_recipes"
    }
}
