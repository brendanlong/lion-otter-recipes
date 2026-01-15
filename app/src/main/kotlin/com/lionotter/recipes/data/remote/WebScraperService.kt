package com.lionotter.recipes.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

data class ScrapedPage(
    val originalHtml: String,
    val extractedContent: String,
    val imageUrl: String? = null
)

@Singleton
class WebScraperService @Inject constructor(
    private val httpClient: HttpClient
) {
    suspend fun fetchPage(url: String): Result<ScrapedPage> {
        return try {
            val response = httpClient.get(url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.5")
            }
            val html = response.bodyAsText()

            // Extract image URL from meta tags
            val imageUrl = extractImageUrl(html)

            // Extract article content using Readability4J
            val readability = Readability4J(url, html)
            val article = readability.parse()

            // Build extracted content with title for context
            val title = article.title
            val content = article.textContent?.takeIf { it.isNotBlank() }
                ?: article.content
                ?: html // Fall back to original HTML if extraction fails

            val extractedContent = buildString {
                if (!title.isNullOrBlank()) {
                    appendLine("Title: $title")
                    appendLine()
                }
                append(content)
            }

            Result.success(ScrapedPage(
                originalHtml = html,
                extractedContent = extractedContent,
                imageUrl = imageUrl
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractImageUrl(html: String): String? {
        return try {
            val doc = Jsoup.parse(html)

            // Try Open Graph image first
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[name=og:image]")?.attr("content")
                    ?.takeIf { it.isNotBlank() }
                // Fall back to Twitter image
                ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")
                    ?.takeIf { it.isNotBlank() }
                // Fall back to generic image meta tag
                ?: doc.selectFirst("meta[name=image]")?.attr("content")
                    ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
