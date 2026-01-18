package com.lionotter.recipes.data.local.sync

import androidx.room.TypeConverter

/**
 * Type converters for sync-related enums in Room database.
 */
class SyncConverters {

    @TypeConverter
    fun fromSyncStatus(status: SyncStatus): String = status.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)

    @TypeConverter
    fun fromSyncOperationType(type: SyncOperationType): String = type.name

    @TypeConverter
    fun toSyncOperationType(value: String): SyncOperationType = SyncOperationType.valueOf(value)

    @TypeConverter
    fun fromOperationStatus(status: OperationStatus): String = status.name

    @TypeConverter
    fun toOperationStatus(value: String): OperationStatus = OperationStatus.valueOf(value)
}
