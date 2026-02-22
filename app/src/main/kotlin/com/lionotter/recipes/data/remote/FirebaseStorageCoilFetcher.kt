package com.lionotter.recipes.data.remote

import android.content.Context
import android.util.Log
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.google.firebase.Firebase
import com.google.firebase.storage.storage
import kotlinx.coroutines.tasks.await
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Coil [Fetcher] that handles Firebase Storage paths (e.g., "users/{uid}/images/{filename}").
 *
 * On first load, downloads the image from Firebase Storage to a local cache file.
 * Subsequent loads serve from the cache file directly.
 *
 * This allows all existing Compose image components (RecipeThumbnail, RecipeContent, etc.)
 * to transparently display Firebase Storage images without changes.
 */
class FirebaseStorageCoilFetcher(
    private val storagePath: String,
    private val context: Context
) : Fetcher {

    companion object {
        private const val TAG = "FirebaseStorageFetcher"
    }

    override suspend fun fetch(): FetchResult {
        val filename = storagePath.substringAfterLast("/")
        val cacheFile = File(ImageCacheConfig.getImageCacheDir(context), filename)

        // Serve from cache if available
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return SourceFetchResult(
                source = ImageSource(
                    file = cacheFile.toOkioPath(),
                    fileSystem = okio.FileSystem.SYSTEM
                ),
                mimeType = null,
                dataSource = DataSource.DISK
            )
        }

        // Download from Firebase Storage
        val ref = Firebase.storage.reference.child(storagePath)
        val bytes = ref.getBytes(ImageCacheConfig.MAX_IMAGE_BYTES).await()
        cacheFile.writeBytes(bytes)
        Log.d(TAG, "Downloaded image from $storagePath (${bytes.size} bytes)")

        return SourceFetchResult(
            source = ImageSource(
                file = cacheFile.toOkioPath(),
                fileSystem = okio.FileSystem.SYSTEM
            ),
            mimeType = null,
            dataSource = DataSource.NETWORK
        )
    }

    /**
     * Factory that intercepts Firebase Storage paths passed as String models to Coil.
     */
    class Factory(private val context: Context) : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            // Only handle Firebase Storage paths
            if (!data.startsWith("users/")) return null
            return FirebaseStorageCoilFetcher(data, context)
        }
    }
}
