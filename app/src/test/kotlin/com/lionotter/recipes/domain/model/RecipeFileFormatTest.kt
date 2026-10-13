package com.lionotter.recipes.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeFileFormatTest {

    @Test
    fun `the file name's extension decides the format`() {
        assertEquals(RecipeFileFormat.TEXT, RecipeFileFormat.detect("Bread.md", null))
        assertEquals(RecipeFileFormat.TEXT, RecipeFileFormat.detect("notes/bread.MARKDOWN", null))
        assertEquals(RecipeFileFormat.TEXT, RecipeFileFormat.detect("bread.txt", "application/octet-stream"))
        assertEquals(RecipeFileFormat.HTML, RecipeFileFormat.detect("Bread.HTML", null))
        assertEquals(RecipeFileFormat.HTML, RecipeFileFormat.detect("bread.htm", "text/plain"))
    }

    @Test
    fun `without a known extension the type decides`() {
        assertEquals(RecipeFileFormat.TEXT, RecipeFileFormat.detect("msf:123", "text/markdown"))
        assertEquals(RecipeFileFormat.TEXT, RecipeFileFormat.detect(null, "text/plain; charset=utf-8"))
        assertEquals(RecipeFileFormat.HTML, RecipeFileFormat.detect(null, "text/html"))
        assertEquals(RecipeFileFormat.HTML, RecipeFileFormat.detect("page", "TEXT/HTML; charset=windows-1252"))
    }

    @Test
    fun `recipe archives and other files are not recipe files`() {
        assertNull(RecipeFileFormat.detect("export.lorecipes", "application/zip"))
        assertNull(RecipeFileFormat.detect("My Recipes.paprikarecipes", "application/octet-stream"))
        assertNull(RecipeFileFormat.detect(null, "application/zip"))
        assertNull(RecipeFileFormat.detect("song.mp3", "audio/mpeg"))
        assertNull(RecipeFileFormat.detect(null, null))
    }

    @Test
    fun `text files have a smaller size limit than HTML files`() {
        assertEquals(200 * 1024L, RecipeFileFormat.TEXT.maxBytes)
        assertEquals(5 * 1024 * 1024L, RecipeFileFormat.HTML.maxBytes)
    }

    @Test
    fun `photos and PDFs are recipe files`() {
        assertEquals(RecipeFileFormat.IMAGE, RecipeFileFormat.detect("photo.jpg", "image/jpeg"))
        assertEquals(RecipeFileFormat.IMAGE, RecipeFileFormat.detect("IMG_0001.HEIC", "application/octet-stream"))
        assertEquals(RecipeFileFormat.IMAGE, RecipeFileFormat.detect("1000000123", "image/webp"))
        assertEquals(RecipeFileFormat.PDF, RecipeFileFormat.detect("Bread.PDF", null))
        assertEquals(RecipeFileFormat.PDF, RecipeFileFormat.detect(null, "application/pdf"))
        assertEquals(10 * 1024 * 1024L, RecipeFileFormat.PDF.maxBytes)
    }
}
