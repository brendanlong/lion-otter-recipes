package com.lionotter.recipes.domain.model

import com.lionotter.recipes.domain.ResourceLimits

/** A document a single recipe is imported from (as opposed to a recipe archive). */
enum class RecipeFileFormat(
    /** Bigger files are rejected before they are imported. */
    val maxBytes: Long,
    /** Whether the file is read as text; the others are sent to the AI as attachments. */
    val isText: Boolean
) {
    /** Markdown or plain text, sent to the AI as is. */
    TEXT(ResourceLimits.MAX_TEXT_FILE_BYTES, isText = true),

    /** A saved web page, prepared for the AI like a downloaded one. */
    HTML(ResourceLimits.MAX_HTML_FILE_BYTES, isText = true),

    /** A photo of a recipe (one recipe can have several), scaled down and sent to the AI. */
    IMAGE(ResourceLimits.MAX_PHOTO_FILE_BYTES, isText = false),

    /** A PDF document, sent to the AI as is. */
    PDF(ResourceLimits.MAX_PDF_FILE_BYTES, isText = false);

    companion object {
        private val TEXT_EXTENSIONS = setOf("md", "markdown", "txt", "text")
        private val HTML_EXTENSIONS = setOf("html", "htm")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif")
        private val TEXT_MIME_TYPES = setOf("text/markdown", "text/x-markdown", "text/plain")
        private val HTML_MIME_TYPES = setOf("text/html")
        private const val PDF_MIME_TYPE = "application/pdf"

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
                in IMAGE_EXTENSIONS -> return IMAGE
                "pdf" -> return PDF
            }
            val type = mimeType?.substringBefore(';')?.trim()?.lowercase()
            return when {
                type in TEXT_MIME_TYPES -> TEXT
                type in HTML_MIME_TYPES -> HTML
                type == PDF_MIME_TYPE -> PDF
                type != null && type.startsWith("image/") -> IMAGE
                else -> null
            }
        }
    }
}
