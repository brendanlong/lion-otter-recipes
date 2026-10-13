package com.lionotter.recipes.di

import com.lionotter.recipes.data.ai.AnthropicRecipeParser
import com.lionotter.recipes.data.ai.AnthropicImagePromptWriter
import com.lionotter.recipes.data.ai.AnthropicTagOrganizer
import com.lionotter.recipes.data.ai.OpenAiImageGenerator
import com.lionotter.recipes.data.ai.RecipeHtmlExtractor
import com.lionotter.recipes.data.image.RecipeImageStore
import com.lionotter.recipes.data.local.DataStoreGroceryListStateStore
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.paprika.PaprikaParser
import com.lionotter.recipes.data.remote.WebScraperService
import com.lionotter.recipes.data.repository.ImportDebugRepository
import com.lionotter.recipes.domain.port.AiSettings
import com.lionotter.recipes.domain.port.AppSettings
import com.lionotter.recipes.domain.port.GroceryListStateStore
import com.lionotter.recipes.domain.port.ImagePromptWriter
import com.lionotter.recipes.domain.port.ImageStore
import com.lionotter.recipes.domain.port.ImportDebugLog
import com.lionotter.recipes.domain.port.PageContentExtractor
import com.lionotter.recipes.domain.port.PageFetcher
import com.lionotter.recipes.domain.port.PaprikaExportReader
import com.lionotter.recipes.domain.port.RecipeBatchParser
import com.lionotter.recipes.domain.port.RecipeImageGenerator
import com.lionotter.recipes.domain.port.RecipeParser
import com.lionotter.recipes.domain.port.TagOrganizer
import com.lionotter.recipes.domain.port.TagPreferences
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the domain ports to their data-layer implementations. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PortModule {
    @Binds
    abstract fun bindRecipeParser(impl: AnthropicRecipeParser): RecipeParser

    @Binds
    abstract fun bindRecipeBatchParser(impl: AnthropicRecipeParser): RecipeBatchParser

    @Binds
    abstract fun bindRecipeImageGenerator(impl: OpenAiImageGenerator): RecipeImageGenerator

    @Binds
    abstract fun bindImagePromptWriter(impl: AnthropicImagePromptWriter): ImagePromptWriter

    @Binds
    abstract fun bindTagOrganizer(impl: AnthropicTagOrganizer): TagOrganizer

    @Binds
    abstract fun bindPageFetcher(impl: WebScraperService): PageFetcher

    @Binds
    abstract fun bindAiSettings(impl: SettingsDataStore): AiSettings

    @Binds
    abstract fun bindAppSettings(impl: SettingsDataStore): AppSettings

    @Binds
    abstract fun bindTagPreferences(impl: SettingsDataStore): TagPreferences

    @Binds
    abstract fun bindGroceryListStateStore(impl: DataStoreGroceryListStateStore): GroceryListStateStore

    @Binds
    abstract fun bindPaprikaExportReader(impl: PaprikaParser): PaprikaExportReader

    @Binds
    abstract fun bindImageStore(impl: RecipeImageStore): ImageStore

    @Binds
    abstract fun bindImportDebugLog(impl: ImportDebugRepository): ImportDebugLog

    companion object {
        @Provides
        fun providePageContentExtractor(): PageContentExtractor = RecipeHtmlExtractor
    }
}
