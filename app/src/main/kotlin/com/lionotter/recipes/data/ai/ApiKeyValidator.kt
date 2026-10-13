package com.lionotter.recipes.data.ai

import com.lionotter.recipes.domain.model.AiProvider
import com.lionotter.recipes.domain.model.AiProviders

/** Why an API key can't be used. */
sealed interface ApiKeyProblem {
    data object Empty : ApiKeyProblem
    data object BadPrefix : ApiKeyProblem

    /** The key has the more specific prefix of another provider, e.g. an Anthropic key entered as an OpenAI key. */
    data class OtherProvider(val provider: AiProvider) : ApiKeyProblem
}

object ApiKeyValidator {
    /** Checks the format of an API key for [provider]. Returns null if it looks valid. */
    fun validate(apiKey: String, provider: AiProvider, providers: List<AiProvider> = AiProviders.ALL): ApiKeyProblem? {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) return ApiKeyProblem.Empty
        if (!trimmed.startsWith(provider.keyPrefix)) return ApiKeyProblem.BadPrefix
        // OpenAI's prefix is a prefix of Anthropic's
        val moreSpecific = providers.firstOrNull {
            it.id != provider.id && it.keyPrefix.length > provider.keyPrefix.length && trimmed.startsWith(it.keyPrefix)
        }
        return moreSpecific?.let { ApiKeyProblem.OtherProvider(it) }
    }
}
