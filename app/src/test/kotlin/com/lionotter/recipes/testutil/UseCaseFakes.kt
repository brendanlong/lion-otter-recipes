package com.lionotter.recipes.testutil

import com.lionotter.recipes.data.local.RecipeIdAndName
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.port.AiSettings
import com.lionotter.recipes.domain.port.BatchItemOutcome
import com.lionotter.recipes.domain.port.BatchParseRequest
import com.lionotter.recipes.domain.port.BatchStatus
import com.lionotter.recipes.domain.port.DownloadedImage
import com.lionotter.recipes.domain.port.FetchedPage
import com.lionotter.recipes.domain.port.ImageStore
import com.lionotter.recipes.domain.port.ImportDebugLog
import com.lionotter.recipes.domain.port.ImportDebugRecord
import com.lionotter.recipes.domain.port.PageContent
import com.lionotter.recipes.domain.port.PageContentExtractor
import com.lionotter.recipes.domain.port.PageFetcher
import com.lionotter.recipes.domain.port.ParseRequest
import com.lionotter.recipes.domain.port.ParsedRecipe
import com.lionotter.recipes.domain.port.ParsedRecipeWithUsage
import com.lionotter.recipes.domain.port.RecipeBatchParser
import com.lionotter.recipes.domain.port.RecipeParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.InputStream

/** In-memory fakes of the ports the AI use cases depend on. */

class FakeRecipeRepository(vararg initial: Recipe) : IRecipeRepository {
    val recipes = MutableStateFlow(initial.associateBy { it.id })
    val saved = mutableListOf<Recipe>()

    /** Overrides the count, to test the recipe limit without creating that many recipes. */
    var countOverride: Int? = null

    override fun getAllRecipes(): Flow<List<Recipe>> = recipes.map { it.values.toList() }
    override fun getRecipeById(id: String): Flow<Recipe?> = recipes.map { it[id] }
    override suspend fun getRecipeByIdOnce(id: String): Recipe? = recipes.value[id]

    override fun saveRecipe(recipe: Recipe) {
        saved += recipe
        recipes.value += recipe.id to recipe
    }

    override fun deleteRecipe(id: String) {
        recipes.value -= id
    }

    override fun setFavorite(id: String, isFavorite: Boolean) = update(id) { it.copy(isFavorite = isFavorite) }
    override fun setUserNotes(id: String, userNotes: String?) = update(id) { it.copy(userNotes = userNotes) }
    override fun setImageUrl(id: String, imageUrl: String?) = update(id) { it.copy(imageUrl = imageUrl) }
    override fun updateTitleAndUrl(id: String, name: String, sourceUrl: String?) =
        update(id) { it.copy(name = name, sourceUrl = sourceUrl) }

    override suspend fun getAllRecipeIdsAndNames(): List<RecipeIdAndName> =
        recipes.value.values.map { RecipeIdAndName(it.id, it.name) }

    override suspend fun getRecipeCount(): Int = countOverride ?: recipes.value.size

    private fun update(id: String, change: (Recipe) -> Recipe) {
        recipes.value[id]?.let { recipes.value += id to change(it) }
    }
}

class FakeRecipeParser(
    var result: Outcome<ParsedRecipeWithUsage> = Outcome.Success(parsedWithUsage())
) : RecipeParser {
    val requests = mutableListOf<ParseRequest>()

    /** Runs while the request is "in flight", e.g. to change data the use case reads again afterwards. */
    var onParse: suspend (ParseRequest) -> Unit = {}

    override suspend fun parse(request: ParseRequest): Outcome<ParsedRecipeWithUsage> {
        requests += request
        onParse(request)
        return result
    }
}

class FakeRecipeBatchParser : RecipeBatchParser {
    val created = mutableListOf<List<BatchParseRequest>>()
    val cancelled = mutableListOf<String>()
    var createResult: Outcome<String> = Outcome.Success("batch-1")
    var status: Outcome<BatchStatus> = Outcome.Success(BatchStatus(ended = true, succeeded = 0, processing = 0))
    var results: List<BatchItemOutcome> = emptyList()
    var resultsError: Outcome.Failure? = null

    override suspend fun createBatch(
        requests: List<BatchParseRequest>,
        apiKey: String,
        model: String,
        thinkingEnabled: Boolean
    ): Outcome<String> {
        created += requests
        return createResult
    }

    override suspend fun getBatchStatus(batchId: String, apiKey: String): Outcome<BatchStatus> = status

    override suspend fun forEachResult(
        batchId: String,
        apiKey: String,
        skipCustomIds: Set<String>,
        onResult: (BatchItemOutcome) -> Unit
    ): Outcome<Unit> {
        resultsError?.let { return it }
        results.filter { it.customId !in skipCustomIds }.forEach(onResult)
        return Outcome.Success(Unit)
    }

    override suspend fun cancelBatch(batchId: String, apiKey: String): Outcome<Unit> {
        cancelled += batchId
        return Outcome.Success(Unit)
    }
}

class FakePageFetcher(private val pages: Map<String, Outcome<FetchedPage>> = emptyMap()) : PageFetcher {
    val fetched = mutableListOf<String>()

    override suspend fun fetchPage(url: String): Outcome<FetchedPage> {
        fetched += url
        return pages[url] ?: throw AssertionError("Unexpected fetch of $url")
    }
}

/** "Extracts" a page by tagging its HTML, so tests can see what reached the AI. */
class FakePageContentExtractor(
    private val imageUrl: String? = null,
    private val title: String? = "Page title"
) : PageContentExtractor {
    override fun extract(html: String, sourceUrl: String?) = PageContent("extracted($html)", imageUrl)
    override fun extractTitle(html: String): String? = title
}

class FakeAiSettings(
    apiKey: String? = "sk-ant-test",
    model: String = AiModels.DEFAULT_MODEL,
    thinkingEnabled: Boolean = false,
    importDebuggingEnabled: Boolean = false
) : AiSettings {
    val apiKeyFlow = MutableStateFlow(apiKey)
    val modelFlow = MutableStateFlow(model)
    val thinkingFlow = MutableStateFlow(thinkingEnabled)
    val debuggingFlow = MutableStateFlow(importDebuggingEnabled)

    override val anthropicApiKey: Flow<String?> = apiKeyFlow
    override val aiModel: Flow<String> = modelFlow
    override val thinkingEnabled: Flow<Boolean> = thinkingFlow
    override val importDebuggingEnabled: Flow<Boolean> = debuggingFlow
}

/**
 * Downloads succeed unless the URL is in [failingUrls]; an `http://` URL is "upgraded" to
 * `https://`. Stored images get a `gs://` URL.
 */
class FakeImageStore(private val failingUrls: Set<String> = emptySet()) : ImageStore {
    val downloaded = mutableListOf<String>()
    val stored = mutableListOf<String>()

    override suspend fun downloadAndStoreWithResult(imageUrl: String): DownloadedImage? {
        downloaded += imageUrl
        if (imageUrl in failingUrls) return null
        return DownloadedImage(localUri = localUri(imageUrl), effectiveUrl = imageUrl.replaceFirst("http://", "https://"))
    }

    override suspend fun storeImage(localUri: String, previousImageUrl: String?, recipeId: String?): String {
        stored += localUri
        return "gs://bucket/" + localUri.substringAfterLast('/')
    }

    override suspend fun saveImageFromStream(inputStream: InputStream, extension: String): String? {
        inputStream.close()
        return "file:///images/stream$extension"
    }

    companion object {
        fun localUri(imageUrl: String) = "file:///images/" + imageUrl.substringAfterLast('/')
    }
}

class FakeImportDebugLog : ImportDebugLog {
    val entries = mutableListOf<ImportDebugRecord>()
    override suspend fun record(entry: ImportDebugRecord) {
        entries += entry
    }
}

fun parsedRecipe(name: String = "Banana Bread") = ParsedRecipe(
    name = name,
    story = "A family favorite.",
    servings = 8,
    prepTime = "15 minutes",
    cookTime = "1 hour",
    totalTime = "1 hour 15 minutes",
    instructionSections = listOf(
        InstructionSection(
            steps = listOf(
                InstructionStep(
                    stepNumber = 1,
                    instruction = "Mash the bananas.",
                    ingredients = listOf(Ingredient(name = "banana"))
                )
            )
        )
    ),
    equipment = listOf("loaf pan"),
    tags = listOf("bread")
)

fun parsedWithUsage(recipe: ParsedRecipe = parsedRecipe()) =
    ParsedRecipeWithUsage(recipe = recipe, inputTokens = 100, outputTokens = 200, aiOutputJson = "{\"success\":true}")
