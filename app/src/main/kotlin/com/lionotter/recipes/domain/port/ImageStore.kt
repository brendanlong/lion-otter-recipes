package com.lionotter.recipes.domain.port

import java.io.File
import java.io.InputStream

/** Downloads recipe images and stores them where recipes can reference them. */
interface ImageStore {
    /** Downloads [imageUrl] to local storage. Null if it couldn't be downloaded. */
    suspend fun downloadAndStoreWithResult(imageUrl: String): DownloadedImage?

    /**
     * Makes a local image (from [downloadAndStoreWithResult] or [saveImageFromStream]) available
     * to the recipe, uploading it if the user syncs, and cleans up [previousImageUrl]. Returns
     * the URL to save on the recipe.
     */
    suspend fun storeImage(localUri: String, previousImageUrl: String? = null, recipeId: String? = null): String

    /** Saves an image to local storage. Returns its local URI, or null if it couldn't be saved. */
    suspend fun saveImageFromStream(inputStream: InputStream, extension: String = ".jpg"): String?

    /**
     * A local copy of a recipe's image, for use before [storeImage]: an existing local file is
     * kept, a stored image is returned as-is, and a web URL is downloaded. Null if [imageUrl] is
     * null or the image isn't available.
     */
    suspend fun downloadImageIfNeeded(imageUrl: String?): String?

    /** Deletes a local image that no recipe uses (e.g. one saved but never stored). */
    suspend fun deleteLocalImage(localUri: String)

    /** The image stored for a recipe as a local file (fetched from storage if needed), or null. */
    suspend fun localImageFile(imageUrl: String): File?
}

/**
 * A downloaded image: its local [localUri] and the URL it was actually downloaded from (an
 * HTTP URL may have been upgraded to HTTPS).
 */
data class DownloadedImage(
    val localUri: String,
    val effectiveUrl: String
)
