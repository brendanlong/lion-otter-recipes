package com.lionotter.recipes.di

import android.content.Context
import androidx.room.Room
import com.lionotter.recipes.data.local.ImportDebugDao
import com.lionotter.recipes.data.local.MealPlanDao
import com.lionotter.recipes.data.local.PendingImportDao
import com.lionotter.recipes.data.local.RecipeDao
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
            .addMigrations(
                RecipeDatabase.MIGRATION_1_2,
                RecipeDatabase.MIGRATION_2_3,
                RecipeDatabase.MIGRATION_3_4,
                RecipeDatabase.MIGRATION_4_5,
                RecipeDatabase.MIGRATION_5_6,
                RecipeDatabase.MIGRATION_6_7,
                RecipeDatabase.MIGRATION_7_8
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideRecipeDao(database: RecipeDatabase): RecipeDao {
        return database.recipeDao()
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
    fun provideMealPlanDao(database: RecipeDatabase): MealPlanDao {
        return database.mealPlanDao()
    }
}
