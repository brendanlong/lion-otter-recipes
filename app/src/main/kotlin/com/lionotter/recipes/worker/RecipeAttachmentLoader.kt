package com.lionotter.recipes.worker

import android.util.Log
import com.lionotter.recipes.data.image.ImageUploadPreparer
import com.lionotter.recipes.domain.ImportFileFailure
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.RecipeErrorException
import com.lionotter.recipes.domain.model.RecipeFileFormat
import com.lionotter.recipes.domain.port.ParseAttachment
import com.lionotter.recipes.util.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Turns the files staged for a photo or PDF import into what is sent to the AI: photos are
 * rotated upright and scaled down (the API scales bigger ones down anyway, so sending more only
 * costs time); a PDF is sent as is (its size and page count were checked when it was staged).
 */
class RecipeAttachmentLoader @Inject constructor(
    private val imagePreparer: ImageUploadPreparer,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "RecipeAttachmentLoader"

        /** Longest side of a photo sent to the AI. */
        const val MAX_AI_IMAGE_DIMENSION = 1568

        /** The API takes images up to 5 MB once base64 encoded (a third larger). */
        const val MAX_AI_IMAGE_BYTES = 3_750_000L
    }

    /**
     * @throws RecipeErrorException ([RecipeError.ImportFile]) if a photo isn't an image this
     *   device can read
     */
    suspend fun load(format: RecipeFileFormat, sources: List<File>): List<ParseAttachment> = when (format) {
        RecipeFileFormat.IMAGE -> sources.map { loadPhoto(it) }
        RecipeFileFormat.PDF -> listOf(ParseAttachment.Pdf(withContext(ioDispatcher) { sources.single().readBytes() }))
        RecipeFileFormat.TEXT, RecipeFileFormat.HTML ->
            throw IllegalArgumentException("$format files are read as text")
    }

    private suspend fun loadPhoto(file: File): ParseAttachment.Image {
        val prepared = try {
            imagePreparer.prepare(file, MAX_AI_IMAGE_DIMENSION, MAX_AI_IMAGE_BYTES)
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't read the photo ${file.name}", e)
            throw RecipeErrorException(RecipeError.ImportFile(ImportFileFailure.UNREADABLE), e)
        }
        return ParseAttachment.Image(prepared.bytes, prepared.mimeType)
    }
}
