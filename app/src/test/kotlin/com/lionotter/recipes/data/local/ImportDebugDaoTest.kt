package com.lionotter.recipes.data.local

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImportDebugDaoTest {

    private lateinit var db: RecipeDatabase
    private lateinit var dao: ImportDebugDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecipeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.importDebugDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entry(id: String, html: String?, createdAt: Long = 1L) = ImportDebugEntity(
        id = id,
        sourceUrl = "https://example.com/$id",
        originalHtml = html,
        cleanedContent = "cleaned",
        aiOutputJson = null,
        originalLength = html?.length ?: 0,
        cleanedLength = 7,
        inputTokens = 10,
        outputTokens = 20,
        aiModel = "model",
        thinkingEnabled = false,
        recipeId = null,
        recipeName = "Recipe $id",
        errorMessage = null,
        isError = false,
        durationMs = 100,
        createdAt = createdAt
    )

    @Test
    fun `summaries are ordered newest first and exclude content`() = runTest {
        dao.insertDebugEntry(entry("old", "<p>old</p>", createdAt = 1))
        dao.insertDebugEntry(entry("new", "<p>new</p>", createdAt = 2))

        val summaries = dao.getAllDebugSummaries().first()

        assertEquals(listOf("new", "old"), summaries.map { it.id })
        assertEquals("Recipe new", summaries[0].recipeName)
    }

    @Test
    fun `content can be read back in chunks`() = runTest {
        val html = (1..1000).joinToString("") { "line $it\n" }
        dao.insertDebugEntry(entry("e1", html))

        val length = dao.getContentLength("e1", "originalHtml")!!
        assertEquals(html.length, length)

        val chunkSize = 777
        val rebuilt = buildString {
            var start = 1
            while (start <= length) {
                append(dao.getContentChunk("e1", "originalHtml", start, chunkSize))
                start += chunkSize
            }
        }
        assertEquals(html, rebuilt)
    }

    @Test
    fun `null content has null length`() = runTest {
        dao.insertDebugEntry(entry("e1", html = null))

        assertNull(dao.getContentLength("e1", "originalHtml"))
        assertNull(dao.getContentLength("e1", "aiOutputJson"))
        assertEquals(7, dao.getContentLength("e1", "cleanedContent"))
    }

    @Test
    fun `missing entry returns null summary`() = runTest {
        assertNull(dao.getDebugSummaryById("missing"))
    }
}
