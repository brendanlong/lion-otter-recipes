package com.lionotter.recipes.domain.util

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecipeHtmlExtractorTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private val graphPage = """
        <html><head>
        <title>Best Pancakes</title>
        <meta property="og:image" content="https://example.com/og.jpg">
        <script type="application/ld+json">
        {"@context":"https://schema.org","@graph":[
          {"@type":"WebPage","name":"page"},
          {"@type":["Recipe"],"name":"Pancakes","recipeIngredient":["1 cup flour","2 eggs"],
           "review":[{"reviewBody":"great"}],"image":["https://example.com/ld.jpg"]}
        ]}
        </script>
        <script type="application/ld+json">{ not valid json </script>
        </head><body>
        <article><h1>Pancakes</h1><p>Intro text.</p>
        <ul><li>1 cup flour</li><li>2 eggs</li></ul></article>
        </body></html>
    """.trimIndent()

    @Test
    fun `finds Recipe inside @graph and ignores malformed blocks`() {
        val recipes = RecipeHtmlExtractor.findJsonLdRecipes(Jsoup.parse(graphPage))
        assertEquals(1, recipes.size)
    }

    @Test
    fun `extract includes JSON-LD without noisy fields and keeps list items on separate lines`() {
        val content = RecipeHtmlExtractor.extract(graphPage, "https://example.com/pancakes")
        assertTrue(content.text.contains("recipeIngredient"))
        assertFalse(content.text.contains("reviewBody"))
        assertTrue(content.text.contains("- 1 cup flour\n- 2 eggs"))
        assertEquals("https://example.com/og.jpg", content.imageUrl)
    }

    @Test
    fun `falls back to JSON-LD image when there is no og image`() {
        val html = graphPage.replace("""<meta property="og:image" content="https://example.com/og.jpg">""", "")
        assertEquals("https://example.com/ld.jpg", RecipeHtmlExtractor.extract(html, null).imageUrl)
    }

    @Test
    fun `caps very large pages`() {
        val huge = "<html><body>" + "<p>${"word ".repeat(50)}</p>".repeat(5000) + "</body></html>"
        val content = RecipeHtmlExtractor.extract(huge, null)
        assertTrue(content.text.length <= RecipeHtmlExtractor.MAX_CONTENT_CHARS)
        assertTrue(content.text.endsWith(RecipeHtmlExtractor.TRUNCATION_MARKER))
    }

    @Test
    fun `htmlToText separates block elements`() {
        val body = Jsoup.parse("<div>Step one<br>Step two</div><p>Next <b>bold</b> word</p>").body()
        assertEquals("Step one\nStep two\nNext bold word", RecipeHtmlExtractor.htmlToText(body))
    }
}
