package com.lionotter.recipes.data.remote

/**
 * Schedules a background pass that uploads recipe images still stored as local `file://` URIs
 * (see [ImageSyncService.uploadPendingLocalImages]).
 */
fun interface ImageUploadScheduler {
    fun scheduleLocalImageUpload()
}
