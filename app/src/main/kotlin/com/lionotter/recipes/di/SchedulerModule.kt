package com.lionotter.recipes.di

import com.lionotter.recipes.data.image.ImageUploadScheduler
import com.lionotter.recipes.worker.WorkManagerImageUploadScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SchedulerModule {
    @Binds
    abstract fun bindImageUploadScheduler(impl: WorkManagerImageUploadScheduler): ImageUploadScheduler
}
