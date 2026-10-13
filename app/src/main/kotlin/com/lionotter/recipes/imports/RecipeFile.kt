package com.lionotter.recipes.imports

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.lionotter.recipes.R
import com.lionotter.recipes.domain.model.RecipeFileFormat
import com.lionotter.recipes.util.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A picked or shared Markdown, text or HTML file to import one recipe from. */
data class RecipeFile(
    val uri: Uri,
    /** The file's name, shown in the import list until the recipe's name is known. */
    val displayName: String,
    val format: RecipeFileFormat
)

/** Tells recipe files (imported directly) apart from recipe archives (which go to the selection screen). */
fun interface ImportFileInspector {
    /**
     * The recipe file at [uri], or null if it isn't one (e.g. a .lorecipes or Paprika export).
     * [mimeTypeHint] is the type the sending app gave, used when the provider doesn't know.
     */
    suspend fun recipeFile(uri: Uri, mimeTypeHint: String?): RecipeFile?
}

@Singleton
class ContentResolverImportFileInspector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ImportFileInspector {
    companion object {
        private const val TAG = "ImportFileInspector"
        private const val GENERIC_MIME_TYPE = "application/octet-stream"
    }

    override suspend fun recipeFile(uri: Uri, mimeTypeHint: String?): RecipeFile? = withContext(ioDispatcher) {
        val name = displayName(uri)
        val providerType = try {
            context.contentResolver.getType(uri)
        } catch (e: SecurityException) {
            Log.w(TAG, "Couldn't get the type of $uri", e)
            null
        }
        // The provider knows best, unless it only knows the file is binary data
        val mimeType = providerType?.takeUnless { it == GENERIC_MIME_TYPE } ?: mimeTypeHint ?: providerType
        RecipeFileFormat.detect(name, mimeType)?.let { format ->
            RecipeFile(uri, name ?: context.getString(R.string.recipe_file_default_name), format)
        }
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return uri.lastPathSegment
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Some providers don't support queries; the type alone may still be enough
            Log.w(TAG, "Couldn't get the name of $uri", e)
            null
        } ?: uri.lastPathSegment
    }
}
