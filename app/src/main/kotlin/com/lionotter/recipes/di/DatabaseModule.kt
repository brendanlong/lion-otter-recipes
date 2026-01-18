package com.lionotter.recipes.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lionotter.recipes.data.local.RecipeDao
import com.lionotter.recipes.data.local.RecipeDatabase
import com.lionotter.recipes.data.local.sync.PendingSyncOperationDao
import com.lionotter.recipes.data.local.sync.SyncStateDao
import com.lionotter.recipes.data.local.sync.SyncedRecipeDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Migration from version 1 to 2: Add sync tables for Google Drive continuous sync.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create synced_recipes table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS synced_recipes (
                    localRecipeId TEXT NOT NULL PRIMARY KEY,
                    driveFolderId TEXT NOT NULL,
                    driveJsonFileId TEXT NOT NULL,
                    driveVersion INTEGER NOT NULL,
                    driveModifiedTime INTEGER NOT NULL,
                    driveMd5Checksum TEXT NOT NULL,
                    lastSyncedAt INTEGER NOT NULL,
                    localModifiedAt INTEGER NOT NULL,
                    syncStatus TEXT NOT NULL,
                    FOREIGN KEY(localRecipeId) REFERENCES recipes(id) ON DELETE CASCADE
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_synced_recipes_localRecipeId ON synced_recipes(localRecipeId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_synced_recipes_driveFolderId ON synced_recipes(driveFolderId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_synced_recipes_driveJsonFileId ON synced_recipes(driveJsonFileId)")

            // Create sync_state table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS sync_state (
                    id INTEGER NOT NULL PRIMARY KEY,
                    syncEnabled INTEGER NOT NULL DEFAULT 0,
                    syncFolderId TEXT,
                    syncFolderName TEXT,
                    changesPageToken TEXT,
                    lastChangesSyncAt INTEGER,
                    lastFullSyncAt INTEGER,
                    lastSyncError TEXT
                )
            """)

            // Create pending_sync_operations table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS pending_sync_operations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    operationType TEXT NOT NULL,
                    localRecipeId TEXT,
                    driveFolderId TEXT,
                    driveFileId TEXT,
                    expectedDriveVersion INTEGER,
                    expectedDriveModifiedTime INTEGER,
                    createdAt INTEGER NOT NULL,
                    lastAttemptAt INTEGER,
                    attemptCount INTEGER NOT NULL DEFAULT 0,
                    lastError TEXT,
                    status TEXT NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_sync_operations_status ON pending_sync_operations(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_sync_operations_localRecipeId ON pending_sync_operations(localRecipeId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_sync_operations_operationType ON pending_sync_operations(operationType)")
        }
    }

    @Provides
    @Singleton
    fun provideRecipeDatabase(
        @ApplicationContext context: Context
    ): RecipeDatabase {
        return Room.databaseBuilder(
            context,
            RecipeDatabase::class.java,
            "recipes.db"
        )
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    @Provides
    @Singleton
    fun provideRecipeDao(database: RecipeDatabase): RecipeDao {
        return database.recipeDao()
    }

    @Provides
    @Singleton
    fun provideSyncedRecipeDao(database: RecipeDatabase): SyncedRecipeDao {
        return database.syncedRecipeDao()
    }

    @Provides
    @Singleton
    fun provideSyncStateDao(database: RecipeDatabase): SyncStateDao {
        return database.syncStateDao()
    }

    @Provides
    @Singleton
    fun providePendingSyncOperationDao(database: RecipeDatabase): PendingSyncOperationDao {
        return database.pendingSyncOperationDao()
    }
}
