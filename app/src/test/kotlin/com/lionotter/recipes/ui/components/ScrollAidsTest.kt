package com.lionotter.recipes.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollAidsTest {
    @Test
    fun `the latest registered screen gets the page turn`() {
        val pageTurns = PageTurns()
        val turnedBy = mutableListOf<String>()
        pageTurns.register { turnedBy += "list:$it"; true }
        val unregisterDetail = pageTurns.register { turnedBy += "detail:$it"; true }

        assertTrue(pageTurns.turn(1))
        unregisterDetail()
        assertTrue(pageTurns.turn(-1))

        assertEquals(listOf("detail:1", "list:-1"), turnedBy)
    }

    @Test
    fun `with no screen to scroll, the volume buttons keep their job`() {
        val pageTurns = PageTurns()
        assertFalse(pageTurns.turn(1))

        val unregister = pageTurns.register { false }
        assertFalse(pageTurns.turn(1))
        unregister()
        assertFalse(pageTurns.turn(1))
    }

    @Test
    fun `a page turn leaves the last lines in view`() {
        assertEquals(1750f, pageScrollDistance(height = 2000, direction = 1))
        assertEquals(-1750f, pageScrollDistance(height = 2000, direction = -1))
    }

    @Test
    fun `the thumb is as long as the visible share of the content`() {
        val atTop = thumbSpan(offset = 0, content = 4000, viewport = 1000, track = 1000f, minLength = 10f)
        assertEquals(ThumbSpan(start = 0f, length = 250f), atTop)

        val atEnd = thumbSpan(offset = 3000, content = 4000, viewport = 1000, track = 1000f, minLength = 10f)
        assertEquals(ThumbSpan(start = 750f, length = 250f), atEnd)
    }

    @Test
    fun `a long list's thumb keeps its minimum length and still reaches the end`() {
        val atEnd = thumbSpan(offset = 99_000, content = 100_000, viewport = 1000, track = 1000f, minLength = 50f)
        assertEquals(ThumbSpan(start = 950f, length = 50f), atEnd)
    }

    @Test
    fun `content that fits has no thumb`() {
        assertNull(thumbSpan(offset = 0, content = 800, viewport = 1000, track = 1000f, minLength = 10f))
        assertNull(thumbSpan(offset = 0, content = 1000, viewport = 0, track = 1000f, minLength = 10f))
    }

    @Test
    fun `overscrolled offsets stay on the track`() {
        val span = thumbSpan(offset = 5000, content = 4000, viewport = 1000, track = 1000f, minLength = 10f)
        assertEquals(ThumbSpan(start = 750f, length = 250f), span)
    }
}
