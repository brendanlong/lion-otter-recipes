package com.lionotter.recipes.data.image

import android.util.Log
import androidx.core.net.toUri
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.di.ApplicationScope
import com.lionotter.recipes.domain.port.ImageStore
import com.lionotter.recipes.domain.port.ImportedImage
import com.lionotter.recipes.domain.repository.RecipeRepository
import com.lionotter.recipes.util.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ImageStore] over [LocalImageFiles] (the device), [ImageDownloader] (the web) and
 * [RemoteImageStorage] (cloud storage, for users signed in with Google). Guests' images stay
 * local; they are uploaded after sign-in by [LocalImageUploader].
 *
 * All functions are main-safe.
 */
@Singleton
class RecipeImageStore @Inject constructor(
    private val localFiles: LocalImageFiles,
    private val downloader: ImageDownloader,
    private val remoteStorage: RemoteImageStorage,
    private val recipeRepository: RecipeRepository,
    private val authService: AuthService,
    private val uploadScheduler: ImageUploadScheduler,
    @param:ApplicationScope private val appScope: CoroutineScope
) : ImageStore {
    companion object {
        private const val TAG = "RecipeImageStore"
    }

    override suspend fun importFromUrl(imageUrl: String): ImportedImage? = when {
        RemoteImageStorage.isStorageUrl(imageUrl) -> ImportedImage(imageUrl, imageUrl)
        LocalImageFiles.isLocalUrl(imageUrl) ->
            if (localFiles.exists(imageUrl)) {
                ImportedImage(imageUrl, imageUrl)
            } else {
                // E.g. a recipe exported on another device
                Log.w(TAG, "Local image doesn't exist on this device: $imageUrl")
                null
            }
        imageUrl.startsWith("https://") || imageUrl.startsWith("http://") -> downloader.download(imageUrl)
        else -> {
            Log.w(TAG, "Not a web image URL; ignoring it: $imageUrl")
            null
        }
    }

    override suspend fun importPickedImage(contentUri: String): String? =
        localFiles.saveFromContentUri(contentUri.toUri())

    override suspend fun saveImage(inputStream: InputStream, extension: String): String? =
        localFiles.save(inputStream, extension)

    override suspend fun copyLocalImage(localUri: String): String? = localFiles.copy(localUri)

    override suspend fun discardLocalImage(localUri: String) = localFiles.delete(localUri)

    override suspend fun attach(localUri: String, recipeId: String?, previousImageUrl: String?): String {
        if (RemoteImageStorage.isStorageUrl(localUri)) return localUri
        val stored = if (authService.isGoogleUser()) {
            localFiles.file(localUri)?.let { remoteStorage.upload(it) }
        } else {
            // Guests keep the local file; it is uploaded after sign-in.
            localUri
        }
        if (stored == null) {
            Log.w(TAG, "Image upload failed; keeping the local copy (and the previous image) and scheduling a retry")
            scheduleUpload()
            return localUri
        }
        if (previousImageUrl != null && previousImageUrl != stored) releaseNow(previousImageUrl, recipeId)
        return stored
    }

    private fun scheduleUpload() {
        try {
            uploadScheduler.scheduleLocalImageUpload()
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Failed to schedule local image upload", e)
        }
    }

    override fun release(imageUrl: String, recipeId: String) {
        appScope.launch { withContext(NonCancellable) { releaseNow(imageUrl, recipeId) } }
    }

    private suspend fun releaseNow(imageUrl: String, recipeId: String?) {
        if (isUsedByAnotherRecipe(imageUrl, recipeId)) {
            Log.d(TAG, "Image is still used by another recipe; keeping it: $imageUrl")
            return
        }
        if (RemoteImageStorage.isStorageUrl(imageUrl)) {
            remoteStorage.delete(imageUrl)
            localFiles.deleteCachedCopy(imageUrl)
        } else {
            localFiles.delete(imageUrl)
        }
    }

    /** True if a recipe other than [recipeId] uses [imageUrl], or if that can't be determined. */
    private suspend fun isUsedByAnotherRecipe(imageUrl: String, recipeId: String?): Boolean = try {
        recipeRepository.isImageReferenced(imageUrl, excludingRecipeId = recipeId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not check whether $imageUrl is still in use; keeping it", e)
        true
    }

    override suspend fun localImageFile(imageUrl: String): File? = when {
        RemoteImageStorage.isStorageUrl(imageUrl) -> remoteStorage.cachedFile(imageUrl)
        else -> localFiles.file(imageUrl)
    }
}
