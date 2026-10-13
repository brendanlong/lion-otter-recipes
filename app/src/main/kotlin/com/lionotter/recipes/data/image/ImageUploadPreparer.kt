package com.lionotter.recipes.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.lionotter.recipes.util.DefaultDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Turns a local image into upload-ready bytes: EXIF-rotated and resized to keep retina quality
 * without excessively large files (at most [MAX_IMAGE_DIMENSION] on the longest side and
 * [MAX_UPLOAD_BYTES]).
 */
class ImageUploadPreparer @Inject constructor(
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "ImageUploadPreparer"
        internal const val MAX_IMAGE_DIMENSION = 2048

        /** Maximum size of an image uploaded to cloud storage. */
        const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024L
        private val JPEG_QUALITIES = listOf(85, 75, 60, 45)
        private val PASSTHROUGH_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")

        /**
         * Largest power-of-2 sample size that keeps the longest side at or above
         * [targetSize], so the decoded bitmap only needs a final downscale.
         */
        internal fun calculateInSampleSize(width: Int, height: Int, targetSize: Int): Int {
            val longest = maxOf(width, height)
            var inSampleSize = 1
            while (longest / (inSampleSize * 2) >= targetSize) {
                inSampleSize *= 2
            }
            return inSampleSize
        }
    }

    class PreparedImage(val bytes: ByteArray, val mimeType: String)

    /**
     * Returns the original file when it is already a small, upright JPEG/PNG/WebP, otherwise a
     * re-encoded JPEG that is EXIF-rotated, scaled to at most [MAX_IMAGE_DIMENSION] on its longest
     * side and no larger than [MAX_UPLOAD_BYTES].
     *
     * @throws IOException if the image can't be decoded or compressed small enough
     */
    suspend fun prepare(file: File): PreparedImage = withContext(defaultDispatcher) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            throw IOException("Unsupported image format: ${bounds.outMimeType ?: "unknown"}")
        }

        val orientation = readExifOrientation(file)
        val isUpright = orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        val mimeType: String? = bounds.outMimeType
        if (isUpright && maxOf(width, height) <= MAX_IMAGE_DIMENSION &&
            file.length() <= MAX_UPLOAD_BYTES &&
            mimeType != null && mimeType in PASSTHROUGH_MIME_TYPES
        ) {
            return@withContext PreparedImage(file.readBytes(), mimeType)
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(width, height, MAX_IMAGE_DIMENSION)
        }
        val sampled = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            ?: throw IOException("Failed to decode image")

        val scale = minOf(1f, MAX_IMAGE_DIMENSION.toFloat() / maxOf(sampled.width, sampled.height))
        val matrix = Matrix().apply {
            setScale(scale, scale)
            applyExifOrientation(orientation)
        }
        val bitmap = if (matrix.isIdentity) {
            sampled
        } else {
            Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, true)
                .also { if (it !== sampled) sampled.recycle() }
        }

        try {
            for (quality in JPEG_QUALITIES) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= MAX_UPLOAD_BYTES) {
                    Log.d(
                        TAG, "Prepared image: ${width}x$height (orientation $orientation) -> " +
                            "${bitmap.width}x${bitmap.height} q$quality, ${file.length()} -> ${out.size()} bytes"
                    )
                    return@withContext PreparedImage(out.toByteArray(), "image/jpeg")
                }
            }
            throw IOException("Image is too large to upload even after compression")
        } finally {
            bitmap.recycle()
        }
    }

    private fun readExifOrientation(file: File): Int = try {
        ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        Log.w(TAG, "Could not read EXIF orientation for ${file.name}", e)
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun Matrix.applyExifOrientation(orientation: Int) {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                postRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                postRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
        }
    }
}
