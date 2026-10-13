package com.lionotter.recipes.data.image

import android.graphics.Bitmap
import android.graphics.Color
import com.lionotter.recipes.domain.model.Recipe
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.time.Instant

/** A small PNG that decodes (needs Robolectric's native graphics). */
fun pngBytes(width: Int = 4, height: Int = 3): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}

fun imageRecipe(id: String, imageUrl: String?) = Recipe(
    id = id,
    name = "Recipe $id",
    imageUrl = imageUrl,
    createdAt = Instant.fromEpochMilliseconds(0),
    updatedAt = Instant.fromEpochMilliseconds(0)
)

/** In-memory cloud storage; downloads go to [localFiles]' cache like the real one. */
class FakeRemoteImageStorage(private val localFiles: LocalImageFiles) : RemoteImageStorage {
    val objects = mutableMapOf<String, ByteArray>()

    /** "upload:<file name>" / "delete:<url>", in order. */
    val events = mutableListOf<String>()

    var failUploads = false

    override suspend fun upload(file: File): String? {
        events += "upload:${file.name}"
        if (failUploads) return null
        val url = "gs://bucket/users/u1/images/${file.name}"
        objects[url] = file.readBytes()
        return url
    }

    override suspend fun delete(storageUrl: String) {
        events += "delete:$storageUrl"
        objects -= storageUrl
    }

    override suspend fun cachedFile(storageUrl: String): File? {
        val bytes = objects[storageUrl] ?: return null
        return localFiles.cacheFileFor(storageUrl).apply { writeBytes(bytes) }
    }
}
