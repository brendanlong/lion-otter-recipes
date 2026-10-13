package com.lionotter.recipes.data.image

/**
 * Schedules a background pass that uploads recipe images still stored as local `file://` URLs
 * (see [LocalImageUploader.uploadPendingLocalImages]). Implemented by a WorkManager worker, so
 * the data layer doesn't depend on workers.
 */
fun interface ImageUploadScheduler {
    fun scheduleLocalImageUpload()
}
