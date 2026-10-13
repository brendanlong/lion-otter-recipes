package com.lionotter.recipes.data.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import okio.Path.Companion.toOkioPath
import java.io.IOException

/**
 * Wrapper type so Coil dispatches to our [Fetcher.Factory] instead of the
 * built-in Uri fetcher. Coil 3's service-loaded fetchers claim plain [Uri]
 * and [String] before user-registered factories, so we need a distinct type.
 *
 * Pass this directly as the `model` in Coil `AsyncImage`/`SubcomposeAsyncImage`
 * calls for `gs://` URIs. Use [imageModel] to convert a nullable image URL
 * string to the correct Coil model type.
 */
data class FirebaseStorageUri(val uri: String)

/**
 * Converts an image URL string to the appropriate Coil model.
 *
 * - `gs://` URIs → [FirebaseStorageUri] (handled by [FirebaseStorageCoilFetcher])
 * - Other URLs (http://, content://, file://) → passed through as-is for Coil's built-in fetchers
 * - `null` → `null`
 */
fun imageModel(imageUrl: String?): Any? {
    if (imageUrl == null) return null
    return if (RemoteImageStorage.isStorageUrl(imageUrl)) FirebaseStorageUri(imageUrl) else imageUrl
}

/**
 * Coil [Keyer] that provides a stable memory cache key for [FirebaseStorageUri].
 */
class FirebaseStorageKeyer : Keyer<FirebaseStorageUri> {
    override fun key(data: FirebaseStorageUri, options: Options): String {
        return data.uri
    }
}

/**
 * Coil [Fetcher] that handles Firebase Storage images referenced by `gs://` URIs
 * (e.g., `gs://bucket/users/{uid}/images/{filename}`).
 *
 * Downloads go through [RemoteImageStorage.cachedFile], so they share the local cache used
 * for export and are only downloaded once.
 */
class FirebaseStorageCoilFetcher(
    private val storageUri: String,
    private val remoteImageStorage: RemoteImageStorage
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val cacheFile = remoteImageStorage.cachedFile(storageUri)
            ?: throw IOException("Failed to download image: $storageUri")

        return SourceFetchResult(
            source = ImageSource(
                file = cacheFile.toOkioPath(),
                fileSystem = okio.FileSystem.SYSTEM
            ),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }

    /**
     * Factory that creates [FirebaseStorageCoilFetcher] for [FirebaseStorageUri] data.
     */
    class Factory(
        private val remoteImageStorage: RemoteImageStorage
    ) : Fetcher.Factory<FirebaseStorageUri> {
        override fun create(data: FirebaseStorageUri, options: Options, imageLoader: ImageLoader): Fetcher {
            return FirebaseStorageCoilFetcher(data.uri, remoteImageStorage)
        }
    }
}
