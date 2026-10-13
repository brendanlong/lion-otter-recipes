package com.lionotter.recipes.domain.port

import com.lionotter.recipes.domain.Outcome

/** Downloads recipe web pages. */
interface PageFetcher {
    /** Fails with [RecipeError.PageFetch][com.lionotter.recipes.domain.RecipeError.PageFetch]. */
    suspend fun fetchPage(url: String): Outcome<FetchedPage>
}

data class FetchedPage(
    val html: String,
    /** The page's preview image (og:image and similar), if it has one. */
    val imageUrl: String? = null
)

/** Prepares a recipe web page for the AI. */
interface PageContentExtractor {
    /** The page's recipe data (JSON-LD) and readable text, bounded in size, plus its best image. */
    fun extract(html: String, sourceUrl: String?): PageContent

    fun extractTitle(html: String): String?
}

data class PageContent(val text: String, val imageUrl: String?)
