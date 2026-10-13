package com.lionotter.recipes.imports

import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.lionotter.recipes.domain.ImportFileFailure
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.RecipeErrorException
import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.domain.model.RecipeFileFormat
import com.lionotter.recipes.util.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Checks a copied photo or PDF when its import starts, so one that can't be imported is reported
 * at once rather than failing later in the background.
 */
interface ImportFileChecker {
    /**
     * @throws RecipeErrorException ([RecipeError.ImportFile]) if [file] is a photo this device
     *   can't read, or a PDF it can't open (damaged or password protected) or with more than
     *   [ResourceLimits.MAX_PDF_PAGES] pages
     */
    suspend fun check(file: File, format: RecipeFileFormat)
}

class AndroidImportFileChecker @Inject constructor(
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ImportFileChecker {
    companion object {
        private const val TAG = "ImportFileChecker"
    }

    override suspend fun check(file: File, format: RecipeFileFormat) = withContext(ioDispatcher) {
        when (format) {
            RecipeFileFormat.IMAGE -> checkPhoto(file)
            RecipeFileFormat.PDF -> checkPdf(file)
            RecipeFileFormat.TEXT, RecipeFileFormat.HTML -> Unit
        }
    }

    /** Whether this device can decode it (e.g. HEIC only from Android 9). */
    private fun checkPhoto(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "Can't decode the photo (type ${bounds.outMimeType})")
            throw RecipeErrorException(RecipeError.ImportFile(ImportFileFailure.UNREADABLE))
        }
    }

    private fun checkPdf(file: File) {
        val pages = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { it.pageCount }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Can't open the PDF", e)
            throw RecipeErrorException(RecipeError.ImportFile(ImportFileFailure.UNREADABLE), e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't open the PDF (password protected)", e)
            throw RecipeErrorException(RecipeError.ImportFile(ImportFileFailure.UNREADABLE), e)
        }
        if (pages > ResourceLimits.MAX_PDF_PAGES) {
            throw RecipeErrorException(
                RecipeError.ImportFile(ImportFileFailure.TOO_MANY_PAGES, limit = ResourceLimits.MAX_PDF_PAGES)
            )
        }
    }
}
