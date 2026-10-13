package com.lionotter.recipes.domain.model

import com.lionotter.recipes.domain.ResourceLimits

/** A document a single recipe is imported from (as opposed to a recipe archive). */
enum class RecipeFileFormat(
    /** Bigger files are rejected before they are imported. */
    val maxBytes: Long
) {
    /** Markdown or plain text, sent to the AI as is. */
    TEXT(ResourceLimits.MAX_TEXT_FILE_BYTES),

    /** A saved web page, prepared for the AI like a downloaded one. */
    HTML(ResourceLimits.MAX_HTML_FILE_BYTES);

    companion object {
        private val TEXT_EXTENSIONS = setOf("md", "markdown", "txt", "text")
        private val HTML_EXTENSIONS = setOf("html", "htm")
        private val TEXT_MIME_TYPES = setOf("text/markdown", "text/x-markdown", "text/plain")
        private val HTML_MIME_TYPES = setOf("text/html")

        /**
         * The format of a file named [fileName] with type [mimeType] (either may be unknown), or
         * null if it isn't a recipe document (e.g. a .lorecipes or Paprika export). The file
         * name's extension wins, since providers often report a generic type for .md files.
         */
        fun detect(fileName: String?, mimeType: String?): RecipeFileFormat? {
            val name = fileName?.substringAfterLast('/')
            val extension = name?.takeIf { '.' in it }?.substringAfterLast('.')?.lowercase()
            when (extension) {
                in TEXT_EXTENSIONS -> return TEXT
                in HTML_EXTENSIONS -> return HTML
            }
            return when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
                in TEXT_MIME_TYPES -> TEXT
                in HTML_MIME_TYPES -> HTML
                else -> null
            }
        }
    }
}
