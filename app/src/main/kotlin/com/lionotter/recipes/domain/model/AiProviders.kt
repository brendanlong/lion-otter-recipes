package com.lionotter.recipes.domain.model

/** What a model makes. */
enum class ModelKind { TEXT, IMAGE }

/** What the user picks a model for; each role has its own setting and per-provider default. */
enum class ModelRole(val kind: ModelKind) {
    /** Imports (also Paprika batches). */
    IMPORT(ModelKind.TEXT),

    /** Edits, regeneration and tag cleanup. */
    EDIT(ModelKind.TEXT),
    IMAGE(ModelKind.IMAGE)
}

/** How a model compares with the provider's others, shown next to its name. */
enum class ModelTier { MOST_CAPABLE, BEST_QUALITY, BALANCED, FASTEST }

/** A model the app can use; [id] is the API model ID, unique across all providers. */
data class AiModel(
    val id: String,
    /** The product name, e.g. "Claude Sonnet 5.5" (not translated). */
    val name: String,
    val providerId: String,
    val kind: ModelKind,
    val tier: ModelTier
)

/**
 * An AI provider: one API key gives access to its [models]. [id] is persisted (stored keys,
 * provider order) and must never change. Adding a provider means adding it to [AiProviders.ALL]
 * and binding its implementations of the AI ports in `AiProviderModule`.
 */
class AiProvider(
    val id: String,
    /** The company name, e.g. "Anthropic" (not translated). */
    val name: String,
    /** Every API key of this provider starts with this. */
    val keyPrefix: String,
    /** Where the user gets an API key. */
    val keyUrl: String,
    /** In the order they are offered; the first ones are the most capable. */
    val models: List<AiModel>,
    private val defaults: Map<ModelRole, String>,
    /** Maps retired model IDs (by prefix) to the current model of the same family. */
    private val retiredModelPrefixes: List<Pair<String, String>> = emptyList()
) {
    init {
        require(models.all { it.providerId == id })
        require(defaults.all { (role, model) -> models.any { it.id == model && it.kind == role.kind } })
    }

    fun models(kind: ModelKind): List<AiModel> = models.filter { it.kind == kind }

    /** This provider's model for [role] when the user hasn't picked one; null if it has no model of that kind. */
    fun defaultModel(role: ModelRole): AiModel? = defaults[role]?.let { id -> models.first { it.id == id } }

    /** The model with this ID, or the current one replacing a retired ID; null if it isn't one of this provider's. */
    fun findModel(modelId: String): AiModel? =
        models.firstOrNull { it.id == modelId }
            ?: retiredModelPrefixes.firstOrNull { (prefix, _) -> modelId.startsWith(prefix) }
                ?.let { (_, current) -> models.first { it.id == current } }

    override fun toString(): String = "AiProvider($id)"
}

/** A model whose provider has an API key configured, with that key. */
data class ConfiguredModel(val model: AiModel, val apiKey: String) {
    override fun toString(): String = "ConfiguredModel(${model.id})"
}

/** The providers the app supports, and how the model for each role is chosen. */
object AiProviders {
    const val ANTHROPIC_ID = "anthropic"
    const val OPENAI_ID = "openai"

    const val CLAUDE_FABLE = "claude-fable-5-1"
    const val CLAUDE_OPUS = "claude-opus-5-5"
    const val CLAUDE_SONNET = "claude-sonnet-5-5"
    const val CLAUDE_HAIKU = "claude-haiku-5-5"

    const val GPT_ASTRA = "gpt-6-astra"
    const val GPT_SOL = "gpt-6.1-sol"
    const val GPT_LUNA = "gpt-6-luna"
    const val GPT_IMAGE_SUNBURST = "gpt-image-2.5-sunburst"
    const val GPT_IMAGE_FLARE = "gpt-image-2.5-flare"

    const val DEFAULT_THINKING_ENABLED = false

    val ANTHROPIC = AiProvider(
        id = ANTHROPIC_ID,
        name = "Anthropic",
        keyPrefix = "sk-ant-",
        keyUrl = "https://platform.claude.com/settings/keys",
        models = listOf(
            AiModel(CLAUDE_FABLE, "Claude Fable 5.1", ANTHROPIC_ID, ModelKind.TEXT, ModelTier.MOST_CAPABLE),
            AiModel(CLAUDE_OPUS, "Claude Opus 5.5", ANTHROPIC_ID, ModelKind.TEXT, ModelTier.BEST_QUALITY),
            AiModel(CLAUDE_SONNET, "Claude Sonnet 5.5", ANTHROPIC_ID, ModelKind.TEXT, ModelTier.BALANCED),
            AiModel(CLAUDE_HAIKU, "Claude Haiku 5.5", ANTHROPIC_ID, ModelKind.TEXT, ModelTier.FASTEST)
        ),
        defaults = mapOf(ModelRole.IMPORT to CLAUDE_SONNET, ModelRole.EDIT to CLAUDE_HAIKU),
        retiredModelPrefixes = listOf(
            "claude-fable" to CLAUDE_FABLE,
            "claude-opus" to CLAUDE_OPUS,
            "claude-sonnet" to CLAUDE_SONNET,
            "claude-haiku" to CLAUDE_HAIKU
        )
    )

    val OPENAI = AiProvider(
        id = OPENAI_ID,
        name = "OpenAI",
        keyPrefix = "sk-",
        keyUrl = "https://platform.openai.com/api-keys",
        models = listOf(
            AiModel(GPT_ASTRA, "GPT-6 Astra", OPENAI_ID, ModelKind.TEXT, ModelTier.MOST_CAPABLE),
            AiModel(GPT_SOL, "GPT-6.1 Sol", OPENAI_ID, ModelKind.TEXT, ModelTier.BALANCED),
            AiModel(GPT_LUNA, "GPT-6 Luna", OPENAI_ID, ModelKind.TEXT, ModelTier.FASTEST),
            AiModel(GPT_IMAGE_SUNBURST, "GPT Image 2.5 Sunburst", OPENAI_ID, ModelKind.IMAGE, ModelTier.BEST_QUALITY),
            AiModel(GPT_IMAGE_FLARE, "GPT Image 2.5 Flare", OPENAI_ID, ModelKind.IMAGE, ModelTier.FASTEST)
        ),
        defaults = mapOf(
            ModelRole.IMPORT to GPT_SOL,
            ModelRole.EDIT to GPT_LUNA,
            // Plenty for a recipe card, and faster and cheaper than Sunburst
            ModelRole.IMAGE to GPT_IMAGE_FLARE
        ),
        retiredModelPrefixes = listOf("gpt-6-sol" to GPT_SOL)
    )

    /** In the order the settings screen lists them. */
    val ALL: List<AiProvider> = listOf(ANTHROPIC, OPENAI)

    init {
        val ids = ALL.flatMap { provider -> provider.models.map { it.id } }
        require(ids.size == ids.toSet().size) { "Model IDs must be unique across providers" }
    }

    fun provider(id: String): AiProvider? = ALL.firstOrNull { it.id == id }

    /** The model with this ID (or the model replacing a retired ID), from any provider. */
    fun findModel(modelId: String?): AiModel? {
        if (modelId.isNullOrBlank()) return null
        return ALL.firstNotNullOfOrNull { it.findModel(modelId) }
    }

    /**
     * [modelId] with its provider's key, if that provider is in [apiKeys] (provider ID to key).
     * Null for unknown models and models whose provider has no key.
     */
    fun configuredModel(modelId: String?, apiKeys: Map<String, String>): ConfiguredModel? {
        val model = findModel(modelId) ?: return null
        return apiKeys[model.providerId]?.let { ConfiguredModel(model, it) }
    }

    /**
     * The model to use for [role]: the user's [selected] model if its provider has a key, otherwise
     * the default of the first provider in [apiKeys] (in the order the user added the keys) that
     * has a model for the role. Null when no configured provider has one.
     */
    fun resolve(role: ModelRole, selected: String?, apiKeys: Map<String, String>): ConfiguredModel? {
        configuredModel(selected, apiKeys)?.takeIf { it.model.kind == role.kind }?.let { return it }
        return apiKeys.firstNotNullOfOrNull { (providerId, key) ->
            provider(providerId)?.defaultModel(role)?.let { ConfiguredModel(it, key) }
        }
    }

    /** The models of providers with a key in [apiKeys], in the order the user added the keys. */
    fun availableModels(kind: ModelKind, apiKeys: Map<String, String>): List<AiModel> =
        apiKeys.keys.mapNotNull(::provider).flatMap { it.models(kind) }
}
