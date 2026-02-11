package com.lionotter.recipes.data.remote

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads recipe images and stores them locally in the app's internal storage.
 * Returns a file:// URI that can be stored in the recipe's imageUrl field.
 */
@Singleton
class ImageDownloadService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient
) {
    companion object {
        private const val TAG = "ImageDownloadService"
        private const val IMAGE_DIR = "recipe_images"
    }

    private fun getImageDir(): File {
        val dir = File(context.filesDir, IMAGE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Downloads an image from a URL and stores it locally.
     * Returns the local file URI string, or null if download fails.
     *
     * If the URL is already a local file URI, returns it as-is.
     */
    suspend fun downloadAndStore(imageUrl: String): String? {
        // Skip if already a local file
        if (imageUrl.startsWith("file://")) {
            return imageUrl
        }

        return try {
            val response = httpClient.get(imageUrl) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                header(HttpHeaders.Accept, "image/*,*/*;q=0.8")
            }

            if (!response.status.isSuccess()) {
                Log.w(TAG, "Failed to download image from $imageUrl: HTTP ${response.status}")
                return null
            }

            // Determine file extension from content type
            val extension = when (response.contentType()?.contentSubtype) {
                "jpeg", "jpg" -> ".jpg"
                "png" -> ".png"
                "webp" -> ".webp"
                "gif" -> ".gif"
                else -> ".jpg" // Default to jpg
            }

            val fileName = "${UUID.randomUUID()}$extension"
            val imageFile = File(getImageDir(), fileName)

            response.bodyAsChannel().toInputStream().use { input ->
                imageFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Verify the file was written and has content
            if (!imageFile.exists() || imageFile.length() == 0L) {
                Log.w(TAG, "Image file is empty after download: $imageUrl")
                imageFile.delete()
                return null
            }

            val localUri = "file://${imageFile.absolutePath}"
            Log.d(TAG, "Downloaded image: $imageUrl -> $localUri (${imageFile.length()} bytes)")
            localUri
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download image from $imageUrl", e)
            null
        }
    }

    /**
     * Deletes a locally stored image file.
     */
    fun deleteLocalImage(localUri: String) {
        if (!localUri.startsWith("file://")) return
        try {
            val path = localUri.removePrefix("file://")
            val file = File(path)
            if (file.exists() && file.parentFile?.name == IMAGE_DIR) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete image: $localUri", e)
        }
    }
}
