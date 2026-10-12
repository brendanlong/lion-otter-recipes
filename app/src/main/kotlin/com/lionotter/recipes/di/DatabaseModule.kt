package com.lionotter.recipes.di

import android.content.Context
import androidx.room.Room
import com.lionotter.recipes.data.local.ImportDebugDao
import com.lionotter.recipes.data.local.PendingImportDao
import com.lionotter.recipes.data.local.RecipeDatabase
import com.lionotter.recipes.data.local.RecipeDatabaseMigrations
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    private const val DATABASE_NAME = "recipes.db"

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RecipeDatabase =
        provideDatabase(context, DATABASE_NAME)

    internal fun provideDatabase(context: Context, name: String): RecipeDatabase {
        return Room.databaseBuilder(
            context,
            RecipeDatabase::class.java,
            name
        )
            .addMigrations(*RecipeDatabaseMigrations.ALL)
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, *RecipeDatabaseMigrations.DESTRUCTIVE_FROM)
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
}
