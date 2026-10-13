package com.lionotter.recipes.di

import com.lionotter.recipes.data.image.FirebaseImageStorage
import com.lionotter.recipes.data.image.RemoteImageStorage
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ImageModule {
    @Binds
    abstract fun bindRemoteImageStorage(impl: FirebaseImageStorage): RemoteImageStorage
}
