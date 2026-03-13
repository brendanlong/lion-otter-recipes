package com.lionotter.recipes.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable staging table for guest-to-Google migration data.
 *
 * Before any destructive operation (Firestore cache clear), guest recipes
 * and meal plans are serialized to JSON and written here. After signing
 * in as the Google user and enabling network, entries are deserialized
 * and written to the Google user's Firestore, then deleted from Room.
 *
 * If the app crashes mid-migration, entries survive in Room and can be
 * retried on next launch.
 */
@Entity(tableName = "pending_migration")
data class PendingMigrationEntity(
    @PrimaryKey val id: String,
    val type: String,
    val json: String,
    val createdAt: Long
) {
    companion object {
        const val TYPE_RECIPE = "recipe"
        const val TYPE_MEAL_PLAN = "meal_plan"
    }
}
