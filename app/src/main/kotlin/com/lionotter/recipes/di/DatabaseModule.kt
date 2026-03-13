package com.lionotter.recipes.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lionotter.recipes.data.local.ImportDebugDao
import com.lionotter.recipes.data.local.PendingImportDao
import com.lionotter.recipes.data.local.PendingMigrationDao
import com.lionotter.recipes.data.local.RecipeDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS recipes")
        db.execSQL("DROP TABLE IF EXISTS meal_plans")
    }
}

private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE import_debug ADD COLUMN batchMode INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS pending_migration (
                id TEXT NOT NULL PRIMARY KEY,
                type TEXT NOT NULL,
                json TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )"""
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RecipeDatabase {
        return Room.databaseBuilder(
            context,
            RecipeDatabase::class.java,
            "recipes.db"
        )
            .addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
            .build()
    }

    @Provides
    @Singleton
    fun provideImportDebugDao(database: RecipeDatabase): ImportDebugDao {
        return database.importDebugDao()
    }

    @Provides
    @Singleton
    fun providePendingImportDao(database: RecipeDatabase): PendingImportDao {
        return database.pendingImportDao()
    }

    @Provides
    @Singleton
    fun providePendingMigrationDao(database: RecipeDatabase): PendingMigrationDao {
        return database.pendingMigrationDao()
    }
}
