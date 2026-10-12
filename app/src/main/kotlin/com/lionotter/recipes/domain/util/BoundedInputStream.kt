package com.lionotter.recipes.domain.util

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Thrown when an archive entry (or the archive as a whole) exceeds a size limit. */
class ArchiveTooLargeException(message: String) : IOException(message)

/**
 * Tracks total bytes decompressed from an archive across entries to defend against zip bombs.
 */
class ArchiveBudget(private val maxTotalBytes: Long) {
    var consumed: Long = 0
        private set

    fun consume(bytes: Long) {
        consumed += bytes
        if (consumed > maxTotalBytes) {
            throw ArchiveTooLargeException("Archive expands to more than ${maxTotalBytes / (1024 * 1024)} MB")
        }
    }
}

/**
 * Wraps an entry stream and throws [ArchiveTooLargeException] once more than [maxBytes] have been
 * read. Closing it does not close the underlying stream, so it can wrap a ZipInputStream entry.
 */
class BoundedInputStream(
    input: InputStream,
    private val maxBytes: Long,
    private val budget: ArchiveBudget? = null
) : FilterInputStream(input) {
    private var count = 0L

    private fun record(n: Long) {
        if (n <= 0) return
        count += n
        budget?.consume(n)
        if (count > maxBytes) {
            throw ArchiveTooLargeException("Entry is larger than ${maxBytes / 1024} KB")
        }
    }

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) record(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        record(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        record(skipped)
        return skipped
    }

    override fun close() {
        // Leave the underlying (archive) stream open.
    }
}
