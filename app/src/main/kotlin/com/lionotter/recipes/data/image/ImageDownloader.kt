package com.lionotter.recipes.data.image

import android.util.Log
import com.lionotter.recipes.domain.port.ImportedImage
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads web images into [LocalImageFiles]. Rejects non-image responses (e.g. HTML error
 * pages) and bodies larger than [LocalImageFiles.MAX_LOCAL_IMAGE_BYTES]; an `http://` URL that
 * fails is retried over HTTPS.
 */
@Singleton
class ImageDownloader @Inject constructor(
    private val httpClient: HttpClient,
    private val localImageFiles: LocalImageFiles
) {
    companion object {
        private const val TAG = "ImageDownloader"
    }

    /** The downloaded image (with the URL it was actually downloaded from), or null if the download failed. */
    suspend fun download(imageUrl: String): ImportedImage? {
        attemptDownload(imageUrl)?.let { return it }
        if (imageUrl.startsWith("http://")) {
            val httpsUrl = imageUrl.replaceFirst("http://", "https://")
            Log.d(TAG, "Retrying image download with HTTPS: $httpsUrl")
            attemptDownload(httpsUrl)?.let { return it }
        }
        return null
    }

    private suspend fun attemptDownload(imageUrl: String): ImportedImage? = try {
        httpClient.prepareGet(imageUrl) {
            header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            header(HttpHeaders.Accept, "image/*,*/*;q=0.8")
        }.execute { response ->
            if (!response.status.isSuccess()) {
                Log.w(TAG, "Failed to download image from $imageUrl: HTTP ${response.status}")
                return@execute null
            }
            val contentType = response.contentType()
            if (contentType != null && !isAcceptableImageContentType(contentType)) {
                Log.w(TAG, "Not an image ($contentType): $imageUrl")
                return@execute null
            }
            val declaredLength = response.contentLength()
            if (declaredLength != null && declaredLength > LocalImageFiles.MAX_LOCAL_IMAGE_BYTES) {
                Log.w(TAG, "Image too large ($declaredLength bytes): $imageUrl")
                return@execute null
            }
            val extension = when (contentType?.contentSubtype) {
                "png" -> ".png"
                "webp" -> ".webp"
                "gif" -> ".gif"
                else -> ".jpg"
            }
            localImageFiles.save(response.bodyAsChannel().toInputStream(), extension)?.let { localUri ->
                Log.d(TAG, "Downloaded image: $imageUrl -> $localUri")
                ImportedImage(uri = localUri, sourceUrl = imageUrl)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Failed to download image from $imageUrl", e)
        null
    }

    private fun isAcceptableImageContentType(contentType: ContentType): Boolean =
        contentType.contentType == "image" || contentType.match(ContentType.Application.OctetStream)
}
