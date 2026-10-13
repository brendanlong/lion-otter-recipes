package com.lionotter.recipes.domain.usecase.paprika

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The durable state of one Paprika import, kept in its work directory so the import survives
 * process death between (and during) steps. The layout is also what imports started by earlier
 * app versions left behind, so it must stay compatible:
 *
 * - `batch.json`: the [PaprikaBatch], written once (atomically) right after the batch is created.
 *   Its presence means a batch exists and must never be submitted again.
 * - `processed.log`: one line per handled result, `customId` (saved) or `customId<TAB>error`.
 *   Only newline-terminated lines count, so a line cut short by a crash is ignored.
 * - `poll_failures`: consecutive failed polls.
 * - `photos/<index>.jpg`: decoded Paprika photos, referenced from [PaprikaBatchRecipe.photoFile].
 * - `cancelled`: the server-side batch has been cancelled.
 */
class PaprikaImportStateStore(val dir: File, private val json: Json) {
    private companion object {
        const val BATCH_FILE = "batch.json"
        const val PROCESSED_LOG = "processed.log"
        const val POLL_FAILURES_FILE = "poll_failures"
        const val PHOTOS_DIR = "photos"
        const val CANCELLED_FILE = "cancelled"
    }

    private val batchFile get() = File(dir, BATCH_FILE)
    private val processedLog get() = File(dir, PROCESSED_LOG)
    private val pollFailuresFile get() = File(dir, POLL_FAILURES_FILE)
    private val cancelledFile get() = File(dir, CANCELLED_FILE)

    fun hasBatch(): Boolean = batchFile.exists()

    /**
     * The submitted batch, or null if none was submitted.
     * @throws IOException or [kotlinx.serialization.SerializationException] if the file is unreadable
     */
    fun readBatch(): PaprikaBatch? {
        val file = batchFile
        if (!file.exists()) return null
        return json.decodeFromString<PaprikaBatch>(file.readText())
    }

    fun writeBatch(batch: PaprikaBatch) {
        writeAtomically(batchFile, json.encodeToString(batch))
    }

    /** True once a result has been handled, which means the batch has ended. */
    fun hasProcessedResults(): Boolean = processedLog.exists()

    /** customId → null if it was saved, or the error message if it failed. */
    fun readProcessed(): Map<String, String?> {
        val file = processedLog
        if (!file.exists()) return emptyMap()
        return file.readText()
            .split('\n')
            .dropLast(1)
            .filter { it.isNotBlank() }
            .associate { line ->
                val parts = line.split('\t', limit = 2)
                parts[0] to parts.getOrNull(1)
            }
    }

    fun appendProcessed(customId: String, error: String?) {
        val line = if (error == null) customId else "$customId\t${error.replace('\n', ' ').replace('\t', ' ')}"
        val file = processedLog
        dropPartialLine(file)
        FileOutputStream(file, true).use { out ->
            out.write("$line\n".toByteArray())
            out.fd.sync()
        }
    }

    fun incrementPollFailures(): Int {
        val file = pollFailuresFile
        val count = (file.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0) + 1
        writeAtomically(file, count.toString())
        return count
    }

    fun resetPollFailures() {
        pollFailuresFile.delete()
    }

    /** Store a decoded photo for the recipe at [index] and return its path. */
    fun savePhoto(index: Int, bytes: ByteArray): String {
        val photos = File(dir, PHOTOS_DIR).apply { mkdirs() }
        val file = File(photos, "$index.jpg")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    fun isCancelled(): Boolean = cancelledFile.exists()

    fun markCancelled() {
        cancelledFile.createNewFile()
    }

    /** Remove a last line that a crash cut short, so the next line doesn't get appended to it. */
    private fun dropPartialLine(file: File) {
        if (!file.exists() || file.length() == 0L) return
        RandomAccessFile(file, "rw").use { raf ->
            var end = raf.length()
            while (end > 0) {
                raf.seek(end - 1)
                if (raf.read() == '\n'.code) break
                end--
            }
            if (end < raf.length()) raf.setLength(end)
        }
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Failed to write ${target.name}")
        }
    }
}
