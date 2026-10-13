package com.lionotter.recipes.testutil

import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.model.AiModels
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.TagMapping
import com.lionotter.recipes.domain.port.AiSettings
import com.lionotter.recipes.domain.port.BatchItemOutcome
import com.lionotter.recipes.domain.port.BatchParseRequest
import com.lionotter.recipes.domain.port.BatchStatus
import com.lionotter.recipes.domain.port.FetchedPage
import com.lionotter.recipes.domain.port.ImageStore
import com.lionotter.recipes.domain.port.ImportDebugLog
import com.lionotter.recipes.domain.port.ImportDebugRecord
import com.lionotter.recipes.domain.port.ImportedImage
import com.lionotter.recipes.domain.port.PageContent
import com.lionotter.recipes.domain.port.PageContentExtractor
import com.lionotter.recipes.domain.port.PageFetcher
import com.lionotter.recipes.domain.port.PaprikaExportReader
import com.lionotter.recipes.domain.port.PaprikaExportRecipe
import com.lionotter.recipes.domain.port.ParseRequest
import com.lionotter.recipes.domain.port.ParsedRecipe
import com.lionotter.recipes.domain.port.ParsedRecipeWithUsage
import com.lionotter.recipes.domain.port.RecipeBatchParser
import com.lionotter.recipes.domain.port.RecipeParser
import com.lionotter.recipes.domain.port.TagCleanupRequest
import com.lionotter.recipes.domain.port.TagOrganizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException
import java.io.InputStream

/** In-memory fakes of the ports the AI use cases depend on. */

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
    var cancelResult: Outcome<Unit> = Outcome.Success(Unit)
    var statusPolls = 0

    /** Runs while the batch is being created, e.g. to cancel the caller. */
    var onCreate: suspend () -> Unit = {}
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
        onCreate()
        return createResult
    }

    override suspend fun getBatchStatus(batchId: String, apiKey: String): Outcome<BatchStatus> {
        statusPolls++
        return status
    }

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
        return cancelResult
    }
}

/** "Reads" [recipes], then reports each of [unreadableEntries] as unreadable. */
class FakePaprikaExportReader(
    private val recipes: List<PaprikaExportRecipe>,
    private val unreadableEntries: List<String> = emptyList()
) : PaprikaExportReader {
    var reads = 0

    override fun readRecipes(
        inputStream: InputStream,
        onError: (entryName: String, error: Exception) -> Unit,
        onRecipe: (PaprikaExportRecipe) -> Unit
    ) {
        reads++
        recipes.forEach(onRecipe)
        unreadableEntries.forEach { onError(it, IOException("Corrupt entry")) }
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
    override fun extractImageUrl(html: String): String? = imageUrl
}

class FakeAiSettings(
    apiKey: String? = "sk-ant-test",
    model: String = AiModels.DEFAULT_MODEL,
    thinkingEnabled: Boolean = false,
    importDebuggingEnabled: Boolean = false
) : AiSettings {
    val apiKeyFlow = MutableStateFlow(apiKey)
    val modelFlow = MutableStateFlow(model)
    val editModelFlow = MutableStateFlow(AiModels.DEFAULT_EDIT_MODEL)
    val thinkingFlow = MutableStateFlow(thinkingEnabled)
    val debuggingFlow = MutableStateFlow(importDebuggingEnabled)

    override val anthropicApiKey: Flow<String?> = apiKeyFlow
    override val aiModel: Flow<String> = modelFlow
    override val editModel: Flow<String> = editModelFlow
    override val thinkingEnabled: Flow<Boolean> = thinkingFlow
    override val importDebuggingEnabled: Flow<Boolean> = debuggingFlow
}

/**
 * Web downloads succeed unless the URL is in [failingUrls]; an `http://` URL is "upgraded" to
 * `https://`. Local and stored images are imported as is. Attached images get a `gs://` URL.
 */
class FakeImageStore(private val failingUrls: Set<String> = emptySet()) : ImageStore {
    val downloaded = mutableListOf<String>()

    /** Local URIs [importPickedImage] returns, by picked `content://` URI. */
    val pickedImages = mutableMapOf<String, String>()

    override suspend fun importPickedImage(contentUri: String): String? = pickedImages[contentUri]

    override suspend fun importFromUrl(imageUrl: String): ImportedImage? {
        if (imageUrl.startsWith("file://") || imageUrl.startsWith("gs://")) return ImportedImage(imageUrl, imageUrl)
        downloaded += imageUrl
        if (imageUrl in failingUrls) return null
        return ImportedImage(uri = localUri(imageUrl), sourceUrl = imageUrl.replaceFirst("http://", "https://"))
    }

    val stored = mutableListOf<String>()

    /** When set, [attach] fails with it. */
    var attachError: Throwable? = null

    /** (image, recipe, previous image) of each [attach]. */
    val attached = mutableListOf<Triple<String, String?, String?>>()

    override suspend fun attach(localUri: String, recipeId: String?, previousImageUrl: String?): String {
        attachError?.let { throw it }
        stored += localUri
        attached += Triple(localUri, recipeId, previousImageUrl)
        return if (localUri.startsWith("gs://")) localUri else "gs://bucket/" + localUri.substringAfterLast('/')
    }

    /** (image, recipe) of each [release]. */
    val released = mutableListOf<Pair<String, String>>()

    override fun release(imageUrl: String, recipeId: String) {
        released += imageUrl to recipeId
    }

    /** Sizes of the images saved with [saveImage]. */
    val savedStreams = mutableListOf<Int>()

    /** The local URI [saveImage] returns for an image of the given size. */
    var streamUri: (size: Int, extension: String) -> String = { _, extension -> "file:///images/stream$extension" }

    override suspend fun saveImage(inputStream: InputStream, extension: String): String? {
        val size = inputStream.use { it.readBytes() }.size
        savedStreams += size
        return streamUri(size, extension)
    }

    /** Local images copied with [copyLocalImage]. */
    val copied = mutableListOf<String>()

    override suspend fun copyLocalImage(localUri: String): String? {
        copied += localUri
        return localUri.substringBeforeLast('.') + "-copy." + localUri.substringAfterLast('.')
    }

    val deletedLocal = mutableListOf<String>()

    override suspend fun discardLocalImage(localUri: String) {
        deletedLocal += localUri
    }

    /** Files returned by [localImageFile], by image URL. */
    val localFiles = mutableMapOf<String, File>()

    override suspend fun localImageFile(imageUrl: String): File? = localFiles[imageUrl]

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

class FakeTagOrganizer(
    var result: Outcome<List<TagMapping>> = Outcome.Success(emptyList())
) : TagOrganizer {
    val requests = mutableListOf<TagCleanupRequest>()

    override suspend fun suggestTagChanges(request: TagCleanupRequest): Outcome<List<TagMapping>> {
        requests += request
        return result
    }
}
