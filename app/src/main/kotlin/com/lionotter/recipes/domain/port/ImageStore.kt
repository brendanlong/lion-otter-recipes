package com.lionotter.recipes.domain.port

import java.io.File
import java.io.InputStream

/**
 * Recipe images and their lifecycle.
 *
 * An image is first brought onto the device ([importFromUrl], [saveImage], [copyLocalImage]),
 * which gives a local URL nothing uses yet. [attach] then makes it a recipe's image (uploading
 * it if the user syncs) and returns the URL to save on the recipe; a local image that is never
 * attached is given back with [discardLocalImage]. [release] is called when a recipe stops using
 * an image; the image is only deleted once no recipe uses it (a recipe saved as a copy shares
 * the original's image).
 */
interface ImageStore {
    /**
     * Brings the image at [imageUrl] onto the device: a web (`http(s)://`) URL is downloaded (an
     * `http://` URL that fails is retried over HTTPS); an existing local image or an already
     * stored image is used as is. Anything else (e.g. a `content://` URL from an imported file)
     * isn't available. Null if the image isn't available. Cancellable; run [attach] (with the
     * recipe save) under `NonCancellable`.
     */
    suspend fun importFromUrl(imageUrl: String): ImportedImage?

    /** Copies an image the user picked (a `content://` URI) onto the device; null if it isn't usable. */
    suspend fun importPickedImage(contentUri: String): String?

    /** Saves the image in [inputStream] (closed by this) on the device; null if it isn't a usable image. */
    suspend fun saveImage(inputStream: InputStream, extension: String = ".jpg"): String?

    /** A new local image with the content of the local image [localUri], or null if it couldn't be copied. */
    suspend fun copyLocalImage(localUri: String): String?

    /** Deletes a local image that was never attached to a recipe. */
    suspend fun discardLocalImage(localUri: String)

    /**
     * Makes the local image [localUri] the image of a recipe and returns the URL to save on it.
     * For a user who syncs it is uploaded; if that fails, the local URL is returned (so nothing is
     * lost) and the upload retried in the background. [previousImageUrl], the image being
     * replaced, is released only after the new one is stored (see [release]).
     *
     * @param recipeId The recipe getting the image, which doesn't count as a user of [previousImageUrl]
     */
    suspend fun attach(localUri: String, recipeId: String? = null, previousImageUrl: String? = null): String

    /**
     * Recipe [recipeId] no longer uses [imageUrl]: deletes it (locally and from cloud storage)
     * unless another recipe still uses it, or that can't be determined. Runs in the background,
     * like the repository writes; failures are logged.
     */
    fun release(imageUrl: String, recipeId: String)

    /** A recipe's image as a local file (fetched from cloud storage if needed), or null. */
    suspend fun localImageFile(imageUrl: String): File?
}

/**
 * An image brought onto the device by [ImageStore.importFromUrl]: its [uri] to attach, and the
 * URL it was actually fetched from ([sourceUrl]; an HTTP URL may have been upgraded to HTTPS).
 */
data class ImportedImage(
    val uri: String,
    val sourceUrl: String
)
