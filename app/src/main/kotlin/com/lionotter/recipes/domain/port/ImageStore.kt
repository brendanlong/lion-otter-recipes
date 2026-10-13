package com.lionotter.recipes.domain.port

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
}

/**
 * A downloaded image: its local [localUri] and the URL it was actually downloaded from (an
 * HTTP URL may have been upgraded to HTTPS).
 */
data class DownloadedImage(
    val localUri: String,
    val effectiveUrl: String
)
