package com.lionotter.recipes.di

import android.content.Context
import androidx.room.Room
import com.lionotter.recipes.data.local.ImportDebugDao
import com.lionotter.recipes.data.local.PendingImportDao
import com.lionotter.recipes.data.local.RecipeDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

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
            .fallbackToDestructiveMigration()
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
