package com.lionotter.recipes.imports

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.lionotter.recipes.data.local.LocalDataScope
import com.lionotter.recipes.data.local.PerUserLocalData
import com.lionotter.recipes.util.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Files the camera app saves photos of a recipe to, until they are imported (which copies them
 * into [com.lionotter.recipes.worker.ImportInputStore]) or discarded. They are offered to the
 * camera app as content URIs of the app's FileProvider.
 */
@Singleton
class CameraPhotoFiles @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : PerUserLocalData {
    companion object {
        private const val TAG = "CameraPhotoFiles"
        private const val DIRECTORY = "camera_photos"
        private const val STALE_AGE_MILLIS = 24 * 60 * 60 * 1000L
    }

    private val directory: File get() = File(context.cacheDir, DIRECTORY)

    /** A new (empty) file for the camera app to save a photo to. Photos left from earlier days are deleted. */
    suspend fun newPhotoUri(): Uri = withContext(ioDispatcher) {
        val cutoff = System.currentTimeMillis() - STALE_AGE_MILLIS
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Couldn't create $directory")
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(directory, "IMG_${time}_${UUID.randomUUID().toString().take(8)}.jpg")
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Whether the camera app saved a photo to [uri] (a URI from [newPhotoUri]). */
    suspend fun hasPhoto(uri: Uri): Boolean = withContext(ioDispatcher) {
        fileFor(uri)?.let { it.length() > 0 } == true
    }

    /** Deletes the photo at [uri] (a URI from [newPhotoUri]). */
    suspend fun delete(uri: Uri) = withContext(ioDispatcher) {
        val file = fileFor(uri) ?: return@withContext
        if (file.exists() && !file.delete()) Log.w(TAG, "Couldn't delete ${file.name}")
    }

    private fun fileFor(uri: Uri): File? {
        val name = uri.lastPathSegment?.takeUnless { it.startsWith('.') } ?: return null
        return File(directory, name).takeIf { it.parentFile == directory && it.name == name }
    }

    override val scope = LocalDataScope.ACCOUNT

    override suspend fun clearUserData() = withContext(ioDispatcher) {
        if (directory.exists() && !directory.deleteRecursively()) throw IOException("Couldn't delete the camera photos")
    }
}
