package com.lionotter.recipes.domain.util

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/**
 * Turns a recipe web page into compact text for the AI: schema.org Recipe JSON-LD (when the page
 * has it) plus the readable article text with line and list boundaries preserved.
 */
object RecipeHtmlExtractor {
    private const val TAG = "RecipeHtmlExtractor"

    /** Upper bound on characters sent to the AI for one page. */
    const val MAX_CONTENT_CHARS = 100_000
    private const val MAX_JSON_LD_CHARS = 30_000
    private const val MIN_ARTICLE_CHARS = 500
    const val TRUNCATION_MARKER = "\n[content truncated]"

    /** JSON-LD keys that are large and irrelevant for parsing a recipe. */
    private val JSON_LD_NOISE_KEYS = setOf(
        "review", "aggregateRating", "video", "comment", "interactionStatistic",
        "publisher", "author", "@context", "potentialAction", "mainEntityOfPage"
    )

    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "header", "footer", "main", "aside",
        "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "tr", "table",
        "blockquote", "pre", "figure", "figcaption", "dl", "dt", "dd", "br", "hr"
    )

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    data class PageContent(val text: String, val imageUrl: String?)

    fun extract(html: String, sourceUrl: String?): PageContent {
        val doc = try {
            Jsoup.parse(html, sourceUrl ?: "")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse HTML, sending truncated raw text", e)
            return PageContent(truncate(html, MAX_CONTENT_CHARS), null)
        }

        val recipes = findJsonLdRecipes(doc)
        val jsonLd = recipes.takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { cleanJsonLd(it).toString() }
            ?.let { truncate(it, MAX_JSON_LD_CHARS) }

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title().takeIf { it.isNotBlank() }

        val text = buildString {
            if (!title.isNullOrBlank()) {
                appendLine("Title: $title")
                appendLine()
            }
            if (jsonLd != null) {
                appendLine("Structured recipe data (schema.org JSON-LD):")
                appendLine(jsonLd)
                appendLine()
                appendLine("Page text:")
            }
            val budget = MAX_CONTENT_CHARS - length
            append(truncate(extractArticleText(html, sourceUrl, doc, budget), budget))
        }

        val imageUrl = extractImageUrl(doc) ?: recipes.firstNotNullOfOrNull { jsonLdImage(it) }
        return PageContent(text, imageUrl)
    }

    fun extractImageUrl(html: String): String? = try {
        extractImageUrl(Jsoup.parse(html))
    } catch (e: Exception) {
        Log.w(TAG, "Failed to extract image URL from HTML", e)
        null
    }

    fun extractImageUrl(doc: Document): String? =
        listOf("meta[property=og:image]", "meta[name=og:image]", "meta[name=twitter:image]", "meta[name=image]")
            .firstNotNullOfOrNull { selector ->
                doc.selectFirst(selector)?.attr("content")?.takeIf { it.isNotBlank() }
            }

    fun extractTitle(html: String): String? = try {
        val doc = Jsoup.parse(html)
        doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title().takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to extract page title from HTML", e)
        null
    }

    internal fun findJsonLdRecipes(doc: Document): List<JsonObject> {
        val results = mutableListOf<JsonObject>()
        for (script in doc.select("script[type=application/ld+json]")) {
            val element = try {
                lenientJson.parseToJsonElement(script.data().trim())
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed JSON-LD block", e)
                continue
            }
            collectRecipes(element, results)
        }
        return results
    }

    private fun collectRecipes(element: JsonElement, out: MutableList<JsonObject>) {
        when (element) {
            is JsonArray -> element.forEach { collectRecipes(it, out) }
            is JsonObject -> {
                if (isRecipeType(element["@type"])) {
                    out.add(element)
                } else {
                    element["@graph"]?.let { collectRecipes(it, out) }
                    element["mainEntity"]?.let { collectRecipes(it, out) }
                }
            }
            else -> {}
        }
    }

    private fun isRecipeType(type: JsonElement?): Boolean = when (type) {
        is JsonPrimitive -> type.contentOrNull.equals("Recipe", ignoreCase = true)
        is JsonArray -> type.any { (it as? JsonPrimitive)?.contentOrNull.equals("Recipe", ignoreCase = true) }
        else -> false
    }

    private fun cleanJsonLd(recipe: JsonObject): JsonObject =
        JsonObject(recipe.filterKeys { it !in JSON_LD_NOISE_KEYS })

    private fun jsonLdImage(recipe: JsonObject): String? {
        fun fromElement(element: JsonElement?): String? = when (element) {
            is JsonPrimitive -> element.contentOrNull?.takeIf { it.startsWith("http") }
            is JsonArray -> element.firstNotNullOfOrNull { fromElement(it) }
            is JsonObject -> (element["url"] as? JsonPrimitive)?.jsonPrimitive?.contentOrNull
            else -> null
        }
        return fromElement(recipe["image"])
    }

    /**
     * The whole page text (minus scripts, navigation and similar chrome) when it fits in [budget].
     * Readability is only used to shrink pages that don't fit, because it drops content it treats
     * as clutter, including ingredient tables and recipe cards.
     */
    private fun extractArticleText(html: String, sourceUrl: String?, doc: Document, budget: Int): String {
        val body = doc.body().clone()
        body.select("script, style, noscript, nav, footer, header, iframe, svg, form").remove()
        val fullText = htmlToText(body)
        if (fullText.length <= budget) return fullText

        val readable = try {
            val article = Readability4J(sourceUrl ?: "https://example.com", html).parse()
            article.content?.let { htmlToText(Jsoup.parseBodyFragment(it).body()) }
        } catch (e: Exception) {
            Log.w(TAG, "Readability extraction failed, using full page text", e)
            null
        }
        return if (readable != null && readable.length >= MIN_ARTICLE_CHARS) readable else fullText
    }

    /**
     * Convert HTML to plain text, keeping paragraph, list-item and table-row boundaries, with
     * table cells separated by " | " (jsoup's text() collapses everything onto one line, which
     * merges ingredients together).
     */
    fun htmlToText(root: Element): String {
        val sb = StringBuilder()
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when (node) {
                    is TextNode -> {
                        val text = node.text()
                        if (text.isNotBlank()) {
                            if (text.first().isWhitespace() && sb.isNotEmpty() && !sb.last().isWhitespace()) {
                                sb.append(' ')
                            }
                            sb.append(text.trim())
                            if (text.last().isWhitespace()) sb.append(' ')
                        } else if (text.isNotEmpty() && sb.isNotEmpty() && !sb.last().isWhitespace()) {
                            sb.append(' ')
                        }
                    }
                    is Element -> {
                        val tag = node.normalName()
                        if (tag in BLOCK_TAGS) newLine(sb)
                        if (tag == "li") sb.append("- ")
                        if ((tag == "td" || tag == "th") && sb.isNotEmpty() && sb.last() != '\n') sb.append(" | ")
                    }
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() in BLOCK_TAGS) newLine(sb)
            }
        }, root)
        return sb.toString()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "-" }
            .joinToString("\n")
    }

    private fun newLine(sb: StringBuilder) {
        if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
    }

    private fun truncate(text: String, max: Int): String {
        if (max <= 0) return ""
        if (text.length <= max) return text
        Log.w(TAG, "Truncating page content from ${text.length} to $max characters")
        return text.take((max - TRUNCATION_MARKER.length).coerceAtLeast(0)) + TRUNCATION_MARKER
    }
}
