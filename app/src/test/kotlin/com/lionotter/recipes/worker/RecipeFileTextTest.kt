package com.lionotter.recipes.worker

import com.lionotter.recipes.domain.model.RecipeFileFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeFileTextTest {

    private val bom = Char(0xFEFF).toString()

    @Test
    fun `text is UTF-8 by default`() {
        assertEquals("Crème brûlée", RecipeFileText.decode("Crème brûlée".toByteArray(), RecipeFileFormat.TEXT))
    }

    @Test
    fun `byte order marks are honored and removed`() {
        assertEquals("Crème", RecipeFileText.decode("${bom}Crème".toByteArray(Charsets.UTF_8), RecipeFileFormat.TEXT))
        assertEquals("Crème", RecipeFileText.decode("${bom}Crème".toByteArray(Charsets.UTF_16LE), RecipeFileFormat.TEXT))
        assertEquals("Crème", RecipeFileText.decode("${bom}Crème".toByteArray(Charsets.UTF_16BE), RecipeFileFormat.HTML))
    }

    @Test
    fun `an HTML file's declared charset is used`() {
        val html = """<html><head><meta charset="windows-1252"><title>Crème</title></head></html>"""
        assertEquals(html, RecipeFileText.decode(html.toByteArray(Charsets.ISO_8859_1), RecipeFileFormat.HTML))

        val httpEquiv = """<meta http-equiv="Content-Type" content="text/html; charset=ISO-8859-1">Crème"""
        assertEquals(httpEquiv, RecipeFileText.decode(httpEquiv.toByteArray(Charsets.ISO_8859_1), RecipeFileFormat.HTML))
    }

    @Test
    fun `an unknown declared charset falls back to UTF-8`() {
        val html = """<meta charset="not-a-charset">Crème"""
        assertEquals(html, RecipeFileText.decode(html.toByteArray(), RecipeFileFormat.HTML))
    }

    @Test
    fun `a charset declared in a text file is ignored`() {
        val text = """<meta charset="windows-1252"> Crème"""
        assertEquals(text, RecipeFileText.decode(text.toByteArray(), RecipeFileFormat.TEXT))
    }
}
