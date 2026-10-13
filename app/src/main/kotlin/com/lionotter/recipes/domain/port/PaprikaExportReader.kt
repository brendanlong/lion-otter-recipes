package com.lionotter.recipes.domain.port

import java.io.InputStream

/** Reads Paprika exports (.paprikarecipes). */
interface PaprikaExportReader {
    /**
     * Streams the recipes in a Paprika export: [onRecipe] for each recipe and [onError] for each
     * entry that couldn't be read (the rest of the export is still read).
     *
     * @throws com.lionotter.recipes.domain.util.ArchiveTooLargeException if the export exceeds
     *     the size limits
     */
    fun readRecipes(
        inputStream: InputStream,
        onError: (entryName: String, error: Exception) -> Unit,
        onRecipe: (PaprikaExportRecipe) -> Unit
    )
}

/** What an import needs from one Paprika recipe. */
data class PaprikaExportRecipe(
    val uid: String,
    val name: String,
    val sourceUrl: String?,
    val imageUrl: String?,
    /** The embedded photo, base64-encoded. */
    val photoData: String?,
    /** The recipe as text for the AI. */
    val contentForAi: String
)
