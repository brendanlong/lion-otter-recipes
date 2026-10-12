package com.lionotter.recipes.data.local

import android.util.Log
import androidx.room.TypeConverter

/**
 * Status of a [PendingImportEntity]. [value] is what is stored in the database; existing rows
 * hold these strings, so never change them.
 *
 * Live progress comes from WorkManager; only states that must outlive a worker run are written
 * ([PENDING], [WAITING_FOR_BATCH] between Paprika polls, [FAILED]). The rest can still appear
 * in rows written by older app versions.
 */
enum class PendingImportStatus(val value: String) {
    PENDING("pending"),
    FETCHING_METADATA("fetching_metadata"),
    METADATA_READY("metadata_ready"),
    PARSING("parsing"),
    SAVING("saving"),
    SUBMITTING_BATCH("submitting_batch"),
    WAITING_FOR_BATCH("waiting_for_batch"),
    FAILED("failed");

    companion object {
        private const val TAG = "PendingImportStatus"

        /** Unknown values (e.g. written by a newer app version) are treated as [PENDING]. */
        fun fromValue(value: String): PendingImportStatus =
            entries.firstOrNull { it.value == value } ?: PENDING.also {
                Log.w(TAG, "Unknown pending import status '$value'")
            }
    }
}

class PendingImportStatusConverter {
    @TypeConverter
    fun fromStatus(status: PendingImportStatus): String = status.value

    @TypeConverter
    fun toStatus(value: String): PendingImportStatus = PendingImportStatus.fromValue(value)
}
