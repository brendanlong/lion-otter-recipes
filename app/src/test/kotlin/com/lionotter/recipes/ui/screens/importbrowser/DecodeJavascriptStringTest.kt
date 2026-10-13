package com.lionotter.recipes.ui.screens.importbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecodeJavascriptStringTest {

    @Test
    fun `a string result is unquoted and unescaped`() {
        // What WebView passes for document.documentElement.outerHTML
        val result = """"<html lang=\"en\"><head><title>Pasta & \"Sauce\"</title></head>""" +
            """\n<body class='a'>café \\ 50%\t— 🍝</body></html>""""

        assertEquals(
            "<html lang=\"en\"><head><title>Pasta & \"Sauce\"</title></head>\n" +
                "<body class='a'>café \\ 50%\t— 🍝</body></html>",
            decodeJavascriptString(result)
        )
    }

    @Test
    fun `script markup inside the page survives`() {
        val result = """"<script type=\"application/ld+json\">{\"@type\":\"Recipe\",\"name\":\"Bread\"}</script>""""

        assertEquals(
            """<script type="application/ld+json">{"@type":"Recipe","name":"Bread"}</script>""",
            decodeJavascriptString(result)
        )
    }

    @Test
    fun `an empty page is an empty string`() {
        assertEquals("", decodeJavascriptString("\"\""))
    }

    @Test
    fun `results that aren't strings are not page text`() {
        assertNull(decodeJavascriptString(null))
        assertNull(decodeJavascriptString(""))
        assertNull(decodeJavascriptString("null"))
        assertNull(decodeJavascriptString("42"))
        assertNull(decodeJavascriptString("{\"a\":1}"))
        assertNull(decodeJavascriptString("not json"))
    }

    @Test
    fun `a large page decodes`() {
        val body = "x".repeat(2_000_000)
        assertEquals("<p>$body</p>", decodeJavascriptString("\"\\u003Cp>$body\\u003C/p>\""))
    }
}
