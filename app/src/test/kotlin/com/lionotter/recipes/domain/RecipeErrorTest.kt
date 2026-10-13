package com.lionotter.recipes.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeErrorTest {

    @Test
    fun `every error round trips through its encoding`() {
        allErrors().forEach { error ->
            assertEquals(error, RecipeError.decode(RecipeError.encode(error)))
        }
    }

    @Test
    fun `encodings are stable`() {
        // Persisted in WorkManager output and the import queue; must keep decoding.
        assertEquals("""{"type":"no_api_key"}""", RecipeError.encode(RecipeError.NoApiKey))
        assertEquals(
            RecipeError.PageFetch(PageFetchFailure.BLOCKED, 403),
            RecipeError.decode("""{"type":"page_fetch","reason":"BLOCKED","httpStatus":403}""")
        )
        assertEquals(
            RecipeError.Ai(AiFailure.OVERLOADED, null),
            RecipeError.decode("""{"type":"ai","reason":"OVERLOADED"}""")
        )
        assertEquals(RecipeError.LimitReached(5, 10), RecipeError.decode("""{"type":"limit_reached","count":5,"limit":10}"""))
        assertEquals(RecipeError.NotARecipe("why"), RecipeError.decode("""{"type":"not_a_recipe","reason":"why"}"""))
        assertEquals(
            RecipeError.Archive(ArchiveFailure.MISSING),
            RecipeError.decode("""{"type":"archive","reason":"MISSING"}""")
        )
    }

    @Test
    fun `text that isn't an encoded error decodes to null`() {
        assertNull(RecipeError.decode("The site blocked the request (403)."))
        assertNull(RecipeError.decode("""{"type":"from_a_newer_version"}"""))
        assertNull(RecipeError.decode("""{"type":"ai","reason":"NEW_REASON"}"""))
    }

    @Test
    fun `long AI explanations are shortened when encoded`() {
        val decoded = RecipeError.decode(RecipeError.encode(RecipeError.NotARecipe("x".repeat(5000))))
        assertEquals(1000, (decoded as RecipeError.NotARecipe).reason.length)
    }

    @Test
    fun `transient failures are retryable`() {
        assertTrue(RecipeError.PageFetch(PageFetchFailure.NETWORK).retryable)
        assertTrue(RecipeError.Ai(AiFailure.RATE_LIMITED, 429).retryable)
        assertFalse(RecipeError.PageFetch(PageFetchFailure.BLOCKED, 403).retryable)
        assertFalse(RecipeError.Ai(AiFailure.INVALID_API_KEY, 401).retryable)
        assertFalse(RecipeError.NoApiKey.retryable)
    }

    @Test
    fun `exceptions map to their recipe error`() {
        val error = RecipeError.Archive(ArchiveFailure.TOO_LARGE)
        assertEquals(error, RecipeErrorException(error).toRecipeError())
        assertEquals(RecipeError.Unexpected, IllegalStateException("boom").toRecipeError())
        assertEquals(RecipeError.Unexpected, (null as Throwable?).toRecipeError())
    }

    companion object {
        fun allErrors(): List<RecipeError> = listOf(
            RecipeError.NoApiKey,
            RecipeError.NoSourceUrl,
            RecipeError.RecipeNotFound,
            RecipeError.LimitReached(1000, 1000),
            RecipeError.NotARecipe("This is a blog post about kitchens."),
            RecipeError.Interrupted,
            RecipeError.CannotRetry,
            RecipeError.Unexpected,
            RecipeError.Legacy("Old message")
        ) + PageFetchFailure.entries.flatMap { listOf(RecipeError.PageFetch(it, 503), RecipeError.PageFetch(it)) } +
            AiFailure.entries.flatMap { listOf(RecipeError.Ai(it, 500), RecipeError.Ai(it)) } +
            ArchiveFailure.entries.map { RecipeError.Archive(it) } +
            ImportFileFailure.entries.map { RecipeError.ImportFile(it) } +
            listOf(
                RecipeError.ImportFile(ImportFileFailure.TOO_LARGE, 200 * 1024L),
                RecipeError.ImportFile(ImportFileFailure.TOO_LARGE, 5 * 1024 * 1024L),
                RecipeError.ImportFile(ImportFileFailure.TOO_MANY_PAGES, limit = 20)
            )
    }

    @Test
    fun `log descriptions say what failed`() {
        assertEquals("Not a recipe: a poem", RecipeError.NotARecipe("a poem").logDescription())
        assertEquals("AI request failed: RATE_LIMITED (HTTP 429)", RecipeError.Ai(AiFailure.RATE_LIMITED, 429).logDescription())
        assertEquals("AI request failed: TRUNCATED", RecipeError.Ai(AiFailure.TRUNCATED).logDescription())
        assertEquals(
            "Page fetch failed: BLOCKED (HTTP 403)",
            RecipeError.PageFetch(PageFetchFailure.BLOCKED, 403).logDescription()
        )
    }
}
