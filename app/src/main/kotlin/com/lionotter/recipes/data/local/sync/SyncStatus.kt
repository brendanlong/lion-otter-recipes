package com.lionotter.recipes.data.local.sync

/**
 * Status of a recipe's sync state with Google Drive.
 */
enum class SyncStatus {
    /** Recipe is in sync with Drive */
    SYNCED,

    /** Local changes need to be uploaded to Drive */
    PENDING_UPLOAD,

    /** Recipe was deleted locally and needs to be deleted from Drive */
    PENDING_DELETE,

    /** Drive version differs unexpectedly - requires user resolution */
    CONFLICT,

    /** Sync failed and needs attention */
    ERROR
}

/**
 * Type of sync operation.
 */
enum class SyncOperationType {
    /** Create or update recipe on Drive */
    UPLOAD,

    /** Remove recipe from Drive */
    DELETE
}

/**
 * Status of a pending sync operation.
 */
enum class OperationStatus {
    /** Waiting to execute */
    PENDING,

    /** Currently executing */
    IN_PROGRESS,

    /** Completed successfully */
    COMPLETED,

    /** Failed, will retry */
    FAILED_RETRYING,

    /** Failed permanently or superseded by another operation */
    ABANDONED
}
