package com.lionotter.recipes.di

import com.lionotter.recipes.data.ai.AnthropicRecipeParser
import com.lionotter.recipes.data.ai.AnthropicTagOrganizer
import com.lionotter.recipes.data.ai.OpenAiImageGenerator
import com.lionotter.recipes.data.ai.OpenAiRecipeParser
import com.lionotter.recipes.data.ai.OpenAiTagOrganizer
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.port.RecipeBatchParser
import com.lionotter.recipes.domain.port.RecipeImageGenerator
import com.lionotter.recipes.domain.port.RecipeParser
import com.lionotter.recipes.domain.port.TagOrganizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/**
 * Each AI provider's implementations of the AI ports, keyed by provider ID. A provider needs an
 * implementation for every kind of model it offers (text: parser, batch parser and tag organizer).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AiProviderModule {
    @Binds
    @IntoMap
    @StringKey(AiProviders.ANTHROPIC_ID)
    abstract fun bindAnthropicParser(impl: AnthropicRecipeParser): RecipeParser

    @Binds
    @IntoMap
    @StringKey(AiProviders.ANTHROPIC_ID)
    abstract fun bindAnthropicBatchParser(impl: AnthropicRecipeParser): RecipeBatchParser

    @Binds
    @IntoMap
    @StringKey(AiProviders.ANTHROPIC_ID)
    abstract fun bindAnthropicTagOrganizer(impl: AnthropicTagOrganizer): TagOrganizer

    @Binds
    @IntoMap
    @StringKey(AiProviders.OPENAI_ID)
    abstract fun bindOpenAiParser(impl: OpenAiRecipeParser): RecipeParser

    @Binds
    @IntoMap
    @StringKey(AiProviders.OPENAI_ID)
    abstract fun bindOpenAiBatchParser(impl: OpenAiRecipeParser): RecipeBatchParser

    @Binds
    @IntoMap
    @StringKey(AiProviders.OPENAI_ID)
    abstract fun bindOpenAiTagOrganizer(impl: OpenAiTagOrganizer): TagOrganizer

    @Binds
    @IntoMap
    @StringKey(AiProviders.OPENAI_ID)
    abstract fun bindOpenAiImageGenerator(impl: OpenAiImageGenerator): RecipeImageGenerator
}
