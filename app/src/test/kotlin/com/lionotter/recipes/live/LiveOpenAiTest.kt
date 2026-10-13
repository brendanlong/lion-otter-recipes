package com.lionotter.recipes.live

import com.lionotter.recipes.data.ai.OpenAiRecipeParser
import com.lionotter.recipes.data.ai.OpenAiResponses
import com.lionotter.recipes.data.ai.RecipeHtmlExtractor
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.model.AiProviders
import com.lionotter.recipes.domain.port.ParseRequest
import com.lionotter.recipes.util.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Opt-in timing check of the real OpenAI API with a saved page. Skipped unless LIVE_OPENAI_KEY
 * and LIVE_OPENAI_PAGE (an HTML file) are set; LIVE_OPENAI_BODY_DIR also saves the request bodies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LiveOpenAiTest {
    @Test
    fun parse() = runBlocking {
        val key = System.getenv("LIVE_OPENAI_KEY")
        val page = System.getenv("LIVE_OPENAI_PAGE")
        assumeTrue(key != null && page != null)
        val client = HttpClient(Android) { install(HttpTimeout) }
        val parser = OpenAiRecipeParser(client, AppJson, OpenAiResponses(client, AppJson))
        val content = RecipeHtmlExtractor.extract(File(page!!).readText(), null).text
        println("cleaned content: ${content.length} chars")
        val models = System.getenv("LIVE_OPENAI_MODELS")?.split(',') ?: listOf(AiProviders.GPT_LUNA, AiProviders.GPT_SOL)
        for (model in models) {
            val request = ParseRequest(content, key!!, model, thinkingEnabled = false)
            System.getenv("LIVE_OPENAI_BODY_DIR")?.let { File(it, "$model.json").writeText(parser.buildBody(request, model).toString()) }
            val start = System.currentTimeMillis()
            val result = parser.parse(request)
            val seconds = (System.currentTimeMillis() - start) / 1000.0
            val summary = when (result) {
                is Outcome.Success -> "ok '${result.value.recipe.name}' in=${result.value.inputTokens} out=${result.value.outputTokens}"
                is Outcome.Failure -> "failed ${result.error}"
            }
            println("$model: ${seconds}s $summary")
        }
    }
}
