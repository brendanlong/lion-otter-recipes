package com.lionotter.recipes.data.remote

import android.util.Log
import com.lionotter.recipes.domain.util.RecipeHtmlExtractor
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class ScrapedPage(
    val originalHtml: String,
    val imageUrl: String? = null
)

/**
 * A page fetch that failed, with a user-facing [message] and whether retrying later might help.
 */
class PageFetchException(
    message: String,
    val isTransient: Boolean,
    cause: Throwable? = null
) : Exception(message, cause)

@Singleton
class WebScraperService @Inject constructor(
    private val httpClient: HttpClient
) {
    suspend fun fetchPage(url: String): Result<ScrapedPage> {
        return try {
            val response = httpClient.get(url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            }

            if (!response.status.isSuccess()) {
                val error = statusError(response)
                Log.w(TAG, "Fetching $url failed: HTTP ${response.status.value}")
                return Result.failure(error)
            }

            val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (contentLength != null && contentLength > MAX_PAGE_BYTES) {
                return Result.failure(
                    PageFetchException("This page is too large to import ($contentLength bytes).", isTransient = false)
                )
            }

            val html = response.bodyAsText().let {
                if (it.length > MAX_PAGE_CHARS) it.take(MAX_PAGE_CHARS) else it
            }

            Result.success(ScrapedPage(
                originalHtml = html,
                imageUrl = RecipeHtmlExtractor.extractImageUrl(html)
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Network error fetching $url", e)
            Result.failure(PageFetchException("Couldn't load the page. Check your internet connection.", true, e))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch $url", e)
            Result.failure(PageFetchException("Couldn't load the page: ${e.message ?: e.javaClass.simpleName}", false, e))
        }
    }

    private suspend fun errorBodyPrefix(response: HttpResponse): String = try {
        response.bodyAsText().take(4096)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read the error response body", e)
        ""
    }

    private suspend fun statusError(response: HttpResponse): PageFetchException {
        val code = response.status.value
        val isBotCheck = response.headers["cf-mitigated"] != null ||
            (code == 403 || code == 503) && errorBodyPrefix(response)
                .let { it.contains("Just a moment", ignoreCase = true) || it.contains("cf-chl", ignoreCase = true) }
        return when {
            isBotCheck -> PageFetchException(
                "This site uses a bot check that blocked the request ($code). Try copying the recipe text instead.",
                isTransient = false
            )
            response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.Unauthorized ->
                PageFetchException("The site blocked the request ($code).", isTransient = false)
            response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.Gone ->
                PageFetchException("The page wasn't found ($code). Check the URL.", isTransient = false)
            response.status == HttpStatusCode.TooManyRequests ->
                PageFetchException("The site is rate limiting requests ($code). Try again later.", isTransient = true)
            code >= 500 ->
                PageFetchException("The site had a server error ($code). Try again later.", isTransient = true)
            else -> PageFetchException("The site returned an error ($code).", isTransient = false)
        }
    }

    companion object {
        private const val TAG = "WebScraperService"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/141.0.0.0 Mobile Safari/537.36"
        private const val MAX_PAGE_BYTES = 10L * 1024 * 1024
        private const val MAX_PAGE_CHARS = 5 * 1024 * 1024
    }
}
