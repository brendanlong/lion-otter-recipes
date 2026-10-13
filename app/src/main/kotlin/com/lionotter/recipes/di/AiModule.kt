package com.lionotter.recipes.di

import com.lionotter.recipes.data.ai.AnthropicRecipeParser
import com.lionotter.recipes.data.ai.RecipeHtmlExtractor
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.remote.WebScraperService
import com.lionotter.recipes.data.repository.ImportDebugRepository
import com.lionotter.recipes.domain.port.AiSettings
import com.lionotter.recipes.domain.port.ImageStore
import com.lionotter.recipes.domain.port.ImportDebugLog
import com.lionotter.recipes.domain.port.PageContentExtractor
import com.lionotter.recipes.domain.port.PageFetcher
import com.lionotter.recipes.domain.port.RecipeBatchParser
import com.lionotter.recipes.domain.port.RecipeParser
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the ports the import, edit and regenerate use cases depend on. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {
    @Binds
    abstract fun bindRecipeParser(impl: AnthropicRecipeParser): RecipeParser

    @Binds
    abstract fun bindRecipeBatchParser(impl: AnthropicRecipeParser): RecipeBatchParser

    @Binds
    abstract fun bindPageFetcher(impl: WebScraperService): PageFetcher

    @Binds
    abstract fun bindAiSettings(impl: SettingsDataStore): AiSettings

    @Binds
    abstract fun bindImageStore(impl: ImageDownloadService): ImageStore

    @Binds
    abstract fun bindImportDebugLog(impl: ImportDebugRepository): ImportDebugLog

    companion object {
        @Provides
        fun providePageContentExtractor(): PageContentExtractor = RecipeHtmlExtractor
    }
}
