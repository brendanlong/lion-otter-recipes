package com.lionotter.recipes.di

import com.lionotter.recipes.imports.ContentResolverImportFileInspector
import com.lionotter.recipes.imports.ImportFileInspector
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ImportModule {
    @Binds
    abstract fun bindImportFileInspector(impl: ContentResolverImportFileInspector): ImportFileInspector
}
