package com.lionotter.recipes.data.remote

import android.util.Log
import com.lionotter.recipes.data.ai.RecipeHtmlExtractor
import com.lionotter.recipes.domain.Outcome
import com.lionotter.recipes.domain.PageFetchFailure
import com.lionotter.recipes.domain.RecipeError
import com.lionotter.recipes.domain.ResourceLimits
import com.lionotter.recipes.domain.port.FetchedPage
import com.lionotter.recipes.domain.port.PageFetcher
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

/**
 * Downloads a recipe page. Many big recipe sites answer non-browser fetches with a 403 bot check,
 * so interactive imports capture the page in the in-app browser instead; this is used for "Import
 * without the browser", work enqueued by older versions, Paprika source images, and regenerating a
 * recipe whose source isn't a web URL.
 */
@Singleton
class WebScraperService @Inject constructor(
    private val httpClient: HttpClient
) : PageFetcher {
    override suspend fun fetchPage(url: String): Outcome<FetchedPage> {
        return try {
            val response = httpClient.get(url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            }

            if (!response.status.isSuccess()) {
                val error = statusError(response)
                Log.w(TAG, "Fetching $url failed: HTTP ${response.status.value}")
                return Outcome.Failure(error)
            }

            val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (contentLength != null && contentLength > MAX_PAGE_BYTES) {
                Log.w(TAG, "Page $url is too large ($contentLength bytes)")
                return Outcome.Failure(RecipeError.PageFetch(PageFetchFailure.TOO_LARGE))
            }

            val html = response.bodyAsText().let {
                if (it.length > ResourceLimits.MAX_PAGE_HTML_CHARS) it.take(ResourceLimits.MAX_PAGE_HTML_CHARS) else it
            }

            Outcome.Success(
                FetchedPage(
                    html = html,
                    imageUrl = RecipeHtmlExtractor.extractImageUrl(html)
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Network error fetching $url", e)
            Outcome.Failure(RecipeError.PageFetch(PageFetchFailure.NETWORK))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch $url", e)
            Outcome.Failure(RecipeError.PageFetch(PageFetchFailure.UNKNOWN))
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

    private suspend fun statusError(response: HttpResponse): RecipeError.PageFetch {
        val code = response.status.value
        val isBotCheck = response.headers["cf-mitigated"] != null ||
            (code == 403 || code == 503) && errorBodyPrefix(response)
                .let { it.contains("Just a moment", ignoreCase = true) || it.contains("cf-chl", ignoreCase = true) }
        val reason = when {
            isBotCheck -> PageFetchFailure.BOT_CHECK
            response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.Unauthorized ->
                PageFetchFailure.BLOCKED
            response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.Gone ->
                PageFetchFailure.NOT_FOUND
            response.status == HttpStatusCode.TooManyRequests -> PageFetchFailure.RATE_LIMITED
            code >= 500 -> PageFetchFailure.SERVER_ERROR
            else -> PageFetchFailure.HTTP_ERROR
        }
        return RecipeError.PageFetch(reason, code)
    }

    companion object {
        private const val TAG = "WebScraperService"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/141.0.0.0 Mobile Safari/537.36"
        private const val MAX_PAGE_BYTES = 10L * 1024 * 1024
    }
}
