package com.lionotter.recipes.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
 * Layout: `files/imports/<importId>/` holds `source` (the copied file), `selection.txt`,
 * `kind`, and any worker state (e.g. Paprika batch state). `files/work-inputs/` holds
 * one-off text inputs.
 */
@Singleton
class ImportInputStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ImportInputStore"
        private const val SOURCE_FILE = "source"
        private const val SELECTION_FILE = "selection.txt"
        private const val KIND_FILE = "kind"
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

    fun sourceFile(importId: String): File = File(importDir(importId), SOURCE_FILE)

    /**
     * Copy a picked or shared document into app storage, so it can still be read after process
     * death (when the content:// grant is gone). The file only appears at [sourceFile] once the
     * copy is complete.
     */
    suspend fun copySource(importId: String, uri: Uri): File = withContext(Dispatchers.IO) {
        val target = sourceFile(importId)
        val partial = File(target.parentFile, "$SOURCE_FILE.partial")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the selected file")
        input.use { source -> partial.outputStream().use { source.copyTo(it) } }
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("Could not save a copy of the selected file")
        }
        target
    }

    fun hasSource(importId: String): Boolean = sourceFile(importId).exists()

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

    fun hasImport(importId: String): Boolean = File(importsRoot, importId).exists()

    fun deleteImport(importId: String) {
        if (importId.isBlank() || importId.contains('/') || importId.contains("..")) return
        val dir = File(importsRoot, importId)
        if (dir.exists() && !dir.deleteRecursively()) {
            Log.w(TAG, "Failed to delete import files for $importId")
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
enum class ImportKind(val value: String) {
    URL("url"),
    PAPRIKA("paprika"),
    ZIP("zip")
}
