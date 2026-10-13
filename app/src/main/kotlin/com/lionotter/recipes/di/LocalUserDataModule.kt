package com.lionotter.recipes.di

import com.lionotter.recipes.data.image.LocalImageFiles
import com.lionotter.recipes.data.local.DataStoreGroceryListStateStore
import com.lionotter.recipes.data.local.PerUserLocalData
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.repository.ImportDebugRepository
import com.lionotter.recipes.imports.ImportQueue
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * The registry of per-user data on the device: `AccountLifecycle` clears every store bound here
 * on sign-out and account deletion, by [PerUserLocalData.scope]. `LocalUserDataRegistryTest`
 * checks that every class persisting data is bound here or deliberately left out.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LocalUserDataModule {
    @Binds
    @IntoSet
    abstract fun bindLocalImageFiles(impl: LocalImageFiles): PerUserLocalData

    @Binds
    @IntoSet
    abstract fun bindGroceryListState(impl: DataStoreGroceryListStateStore): PerUserLocalData

    @Binds
    @IntoSet
    abstract fun bindImportQueue(impl: ImportQueue): PerUserLocalData

    @Binds
    @IntoSet
    abstract fun bindImportDebug(impl: ImportDebugRepository): PerUserLocalData

    @Binds
    @IntoSet
    abstract fun bindApiKey(impl: SettingsDataStore): PerUserLocalData
}
