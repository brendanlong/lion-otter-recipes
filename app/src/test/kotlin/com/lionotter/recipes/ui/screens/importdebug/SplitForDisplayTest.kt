package com.lionotter.recipes.ui.screens.importdebug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitForDisplayTest {

    @Test
    fun `short text is a single chunk`() {
        assertEquals(listOf("abc"), splitForDisplay("abc", 10))
    }

    @Test
    fun `long single line is split at max size`() {
        val text = "x".repeat(25)
        val chunks = splitForDisplay(text, 10)
        assertEquals(listOf(10, 10, 5), chunks.map { it.length })
    }

    @Test
    fun `prefers breaking after newlines and drops the trailing newline`() {
        val text = "aaaaaaa\nbbbbbbb\nccc"
        val chunks = splitForDisplay(text, 10)
        assertEquals(listOf("aaaaaaa", "bbbbbbb", "ccc"), chunks)
    }

    @Test
    fun `chunks never exceed max size`() {
        val text = (1..500).joinToString("\n") { "line number $it" }
        val chunks = splitForDisplay(text, 100)
        assertTrue(chunks.all { it.length <= 100 })
        assertEquals(text, chunks.joinToString("\n"))
    }
}
