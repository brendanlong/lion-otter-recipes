package com.lionotter.recipes.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lionotter.recipes.domain.ImportFileFailure
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.RecipeErrorException
import com.lionotter.recipes.domain.model.RecipeFileFormat
import com.lionotter.recipes.util.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-private storage for background work inputs.
 *
 * WorkManager input [androidx.work.Data] is limited to 10 KB, and content:// permission grants
 * don't survive process death, so large inputs (selected IDs, edited markdown) and picked files
 * are copied here at enqueue time and workers receive only an import ID or file path.
 *
 * Layout: `files/imports/<importId>/` holds `source` (the copied archive or recipe file; further photos of
 * the same recipe are `source-1`, `source-2`, ...), `selection.txt`,
 * `kind`, `page.html` (a URL import's page captured in the in-app browser) and any worker
 * state (e.g. Paprika batch state). `files/work-inputs/` holds one-off text inputs (edited
 * markdown, a page captured for a re-import).
 */
@Singleton
class ImportInputStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "ImportInputStore"
        private const val SOURCE_FILE = "source"
        private const val SELECTION_FILE = "selection.txt"
        private const val KIND_FILE = "kind"
        private const val PAGE_FILE = "page.html"
        private const val ORPHAN_AGE_MILLIS = 60 * 60 * 1000L
        private const val STALE_WORK_INPUT_EXTRA_MILLIS = 6 * 24 * 60 * 60 * 1000L
    }

    private val importLocks = ConcurrentHashMap<String, Mutex>()

    private val importsRoot: File get() = File(context.filesDir, "imports")

    /**
     * Run [block] while holding this import's in-process lock. Workers hold it while they use
     * the import's files, and cancellation takes it before deleting them.
     */
    suspend fun <T> withImportLock(importId: String, block: suspend () -> T): T =
        importLocks.getOrPut(importId) { Mutex() }.withLock { block() }
    private val workInputsRoot: File get() = File(context.filesDir, "work-inputs")

    fun importDir(importId: String): File {
        require(importId.isNotBlank() && !importId.contains('/') && !importId.contains("..")) {
            "Invalid import ID"
        }
        return File(importsRoot, importId).apply { mkdirs() }
    }

    /** The import's copied file; [index] > 0 for the further photos of a recipe imported from several. */
    fun sourceFile(importId: String, index: Int = 0): File =
        File(importDir(importId), if (index == 0) SOURCE_FILE else "$SOURCE_FILE-$index")

    /** The import's copied files in order ([sourceFile] 0, 1, ... while they exist). */
    fun sourceFiles(importId: String): List<File> =
        generateSequence(0) { it + 1 }.map { sourceFile(importId, it) }.takeWhile { it.exists() }.toList()

    /**
     * Copy a picked or shared document into app storage, so it can still be read after process
     * death (when the content:// grant is gone). The file only appears at [sourceFile] once the
     * copy is complete.
     *
     * @param index the file's position, for a recipe imported from several photos
     * @throws RecipeErrorException ([RecipeError.ImportFile]) if the file is bigger than [maxBytes]
     */
    suspend fun copySource(
        importId: String,
        uri: Uri,
        maxBytes: Long = Long.MAX_VALUE,
        index: Int = 0
    ): File = withContext(ioDispatcher) {
        val target = sourceFile(importId, index)
        val partial = File(target.parentFile, "${target.name}.partial")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the selected file")
        val withinLimit = input.use { source -> partial.outputStream().use { copyAtMost(source, it, maxBytes) } }
        if (!withinLimit) {
            partial.delete()
            throw RecipeErrorException(RecipeError.ImportFile(ImportFileFailure.TOO_LARGE, maxBytes))
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("Could not save a copy of the selected file")
        }
        target
    }

    /** Copies [input] to [output], stopping (and returning false) once more than [maxBytes] were read. */
    private fun copyAtMost(input: InputStream, output: OutputStream, maxBytes: Long): Boolean {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return true
            total += read
            if (total > maxBytes) return false
            output.write(buffer, 0, read)
        }
    }

    fun hasSource(importId: String): Boolean = sourceFile(importId).exists()

    /**
     * The recipe file staged for a [ImportKind.isRecipeFile] import, or null if [importId] isn't
     * one. Doesn't create the import's directory.
     */
    fun readRecipeFile(importId: String): StagedRecipeFile? {
        if (!hasImport(importId)) return null
        val format = readKind(importId)?.recipeFileFormat ?: return null
        if (!format.isText) return StagedRecipeFile(format, content = null, sources = sourceFiles(importId))
        val content = sourceFile(importId).takeIf { it.exists() }
            ?.readBytes()
            ?.let { RecipeFileText.decode(it, format) }
        return StagedRecipeFile(format, content)
    }

    /** Mark the import's files as in use so orphan cleanup doesn't remove them. */
    fun touch(importId: String) {
        importDir(importId).setLastModified(System.currentTimeMillis())
    }

    fun writeSelection(importId: String, ids: Set<String>?) {
        val file = File(importDir(importId), SELECTION_FILE)
        if (ids == null) {
            file.delete()
        } else {
            file.writeText(ids.joinToString("\n"))
        }
    }

    /** Returns the selected IDs, or null if everything should be imported. */
    fun readSelection(importId: String): Set<String>? {
        val file = File(importDir(importId), SELECTION_FILE)
        if (!file.exists()) return null
        return file.readLines().filter { it.isNotBlank() }.toSet()
    }

    fun writeKind(importId: String, kind: ImportKind) {
        File(importDir(importId), KIND_FILE).writeText(kind.value)
    }

    /** The kind written by [writeKind], or null if there is none (or it is unknown). */
    fun readKind(importId: String): ImportKind? {
        val value = File(importDir(importId), KIND_FILE).takeIf { it.exists() }?.readText()?.trim() ?: return null
        return ImportKind.entries.firstOrNull { it.value == value }
            ?: null.also { Log.w(TAG, "Unknown import kind '$value' for $importId") }
    }

    /**
     * Move a page written with [writeWorkText] (captured in the in-app browser) into the
     * import, where [readPageHtml] finds it.
     */
    fun adoptPageHtml(importId: String, workTextPath: String) {
        val source = File(workTextPath)
        if (!isWorkInput(source) || !source.exists()) throw IOException("The captured page is gone")
        val target = File(importDir(importId), PAGE_FILE)
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            deleteWorkText(workTextPath)
        }
    }

    /** The import's captured page, or null for a plain URL import. Doesn't create the import's directory. */
    fun readPageHtml(importId: String): String? {
        if (!hasImport(importId)) return null
        return File(importDir(importId), PAGE_FILE).takeIf { it.exists() }?.readText()
    }

    fun hasImport(importId: String): Boolean = File(importsRoot, importId).exists()

    fun deleteImport(importId: String) {
        if (importId.isBlank() || importId.contains('/') || importId.contains("..")) return
        val dir = File(importsRoot, importId)
        if (dir.exists() && !dir.deleteRecursively()) {
            Log.w(TAG, "Failed to delete import files for $importId")
        }
    }

    /**
     * Deletes every import's files. One-off work inputs are left to the work using them.
     * @throws IOException if some could not be deleted
     */
    fun deleteAllImports() {
        if (importsRoot.exists() && !importsRoot.deleteRecursively()) {
            throw IOException("Failed to delete import files")
        }
    }

    /**
     * Delete import directories that no pending import refers to, and stale one-off work
     * inputs, left behind by cancelled or crashed work. Recently modified files are kept so an
     * import that is still being set up isn't removed.
     */
    fun cleanupOrphans(activeImportIds: Set<String>) {
        val cutoff = System.currentTimeMillis() - ORPHAN_AGE_MILLIS
        importsRoot.listFiles()?.forEach { dir ->
            if (dir.name !in activeImportIds && dir.lastModified() < cutoff) {
                Log.i(TAG, "Removing orphaned import files ${dir.name}")
                dir.deleteRecursively()
            }
        }
        workInputsRoot.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff - STALE_WORK_INPUT_EXTRA_MILLIS) file.delete()
        }
    }

    /** Store a text input for a worker and return its path. */
    fun writeWorkText(text: String): String {
        workInputsRoot.mkdirs()
        val file = File(workInputsRoot, "${UUID.randomUUID()}.txt")
        file.writeText(text)
        return file.absolutePath
    }

    fun readWorkText(path: String): String? {
        val file = File(path)
        if (!isWorkInput(file) || !file.exists()) return null
        return file.readText()
    }

    fun deleteWorkText(path: String) {
        val file = File(path)
        if (isWorkInput(file) && file.exists() && !file.delete()) {
            Log.w(TAG, "Failed to delete work input $path")
        }
    }

    private fun isWorkInput(file: File): Boolean =
        file.canonicalFile.parentFile == workInputsRoot.canonicalFile
}

/** What an import reads from; [value] is stored in the import's `kind` file. */
enum class ImportKind(val value: String, val recipeFileFormat: RecipeFileFormat? = null) {
    URL("url"),
    PAPRIKA("paprika"),
    ZIP("zip"),
    /** A Markdown or text file with one recipe. */
    TEXT_FILE("text_file", RecipeFileFormat.TEXT),
    /** A saved web page with one recipe. */
    HTML_FILE("html_file", RecipeFileFormat.HTML),
    /** Photos of one recipe. */
    IMAGE_FILE("image_file", RecipeFileFormat.IMAGE),
    /** A PDF with one recipe. */
    PDF_FILE("pdf_file", RecipeFileFormat.PDF);

    val isRecipeFile: Boolean get() = recipeFileFormat != null

    companion object {
        fun forRecipeFile(format: RecipeFileFormat): ImportKind = entries.first { it.recipeFileFormat == format }
    }
}

/**
 * A recipe file copied into the import. A text file's [content] is null if the file is gone; the
 * other formats are read from [sources] (empty if they are gone).
 */
data class StagedRecipeFile(
    val format: RecipeFileFormat,
    val content: String?,
    val sources: List<File> = emptyList()
)
