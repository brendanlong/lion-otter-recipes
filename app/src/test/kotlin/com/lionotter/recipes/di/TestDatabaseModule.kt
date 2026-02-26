package com.lionotter.recipes.di

import android.content.Context
import androidx.room.Room
import com.lionotter.recipes.data.local.ImportDebugDao
import com.lionotter.recipes.data.local.PendingImportDao
import com.lionotter.recipes.data.local.PendingMigrationDao
import com.lionotter.recipes.data.local.RecipeDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DatabaseModule::class])
object TestDatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RecipeDatabase =
        Room.inMemoryDatabaseBuilder(context, RecipeDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    @Provides
    @Singleton
    fun providePendingImportDao(db: RecipeDatabase): PendingImportDao = db.pendingImportDao()

    @Provides
    @Singleton
    fun provideImportDebugDao(db: RecipeDatabase): ImportDebugDao = db.importDebugDao()

    @Provides
    @Singleton
    fun providePendingMigrationDao(db: RecipeDatabase): PendingMigrationDao = db.pendingMigrationDao()
}
