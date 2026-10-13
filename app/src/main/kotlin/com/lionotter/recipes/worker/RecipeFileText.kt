package com.lionotter.recipes.worker

import com.lionotter.recipes.domain.model.RecipeFileFormat
import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException

/** Decodes a recipe file's bytes: a byte order mark wins, then an HTML file's declared charset, then UTF-8. */
internal object RecipeFileText {
    private const val CHARSET_SNIFF_BYTES = 4096
    private val META_CHARSET = Regex("""<meta[^>]+charset\s*=\s*["']?\s*([A-Za-z0-9_.:\-]+)""", RegexOption.IGNORE_CASE)

    fun decode(bytes: ByteArray, format: RecipeFileFormat): String {
        bomCharset(bytes)?.let { (charset, bomLength) ->
            return String(bytes, bomLength, bytes.size - bomLength, charset)
        }
        val charset = if (format == RecipeFileFormat.HTML) declaredCharset(bytes) else null
        return String(bytes, charset ?: Charsets.UTF_8)
    }

    private fun bomCharset(bytes: ByteArray): Pair<Charset, Int>? {
        fun startsWith(vararg prefix: Int) =
            bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
        return when {
            startsWith(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
            startsWith(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
            startsWith(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
            else -> null
        }
    }

    private fun declaredCharset(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, CHARSET_SNIFF_BYTES), Charsets.ISO_8859_1)
        val name = META_CHARSET.find(head)?.groupValues?.get(1) ?: return null
        return try {
            if (Charset.isSupported(name)) Charset.forName(name) else null
        } catch (expected: IllegalCharsetNameException) {
            // A made-up name; fall back to UTF-8
            null
        }
    }
}
