package com.lionotter.recipes.domain.util

import android.util.Log
import com.lionotter.recipes.data.remote.ImageDownloadService
import com.lionotter.recipes.data.repository.IMealPlanRepository
import com.lionotter.recipes.data.repository.IRecipeRepository
import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Instant

class ZipImportHelperTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    private val serializer = RecipeSerializer(json)
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private lateinit var recipeRepository: IRecipeRepository
    private lateinit var mealPlanRepository: IMealPlanRepository
    private lateinit var imageDownloadService: ImageDownloadService
    private lateinit var helper: ZipImportHelper
    private val savedRecipes = mutableListOf<Recipe>()
    private val savedMealPlans = mutableListOf<MealPlanEntry>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0

        recipeRepository = mockk(relaxed = true) {
            coEvery { getRecipeByIdOnce(any()) } answers { savedRecipes.firstOrNull { it.id == firstArg() } }
            coEvery { getRecipeCount() } answers { savedRecipes.size }
            val recipeSlot = slot<Recipe>()
            every { saveRecipe(capture(recipeSlot)) } answers { savedRecipes.add(recipeSlot.captured) }
        }
        mealPlanRepository = mockk(relaxed = true) {
            val mealSlot = slot<MealPlanEntry>()
            every { saveMealPlan(capture(mealSlot)) } answers { savedMealPlans.add(mealSlot.captured) }
        }
        imageDownloadService = mockk(relaxed = true) {
            coEvery { downloadAndStore(any()) } returns null
            coEvery { downloadImageIfNeeded(any()) } returns null
            every { saveImageFromStream(any(), any()) } answers {
                firstArg<java.io.InputStream>().readBytes()
                "file:///cache/image.jpg"
            }
            coEvery { storeImage(any(), any()) } answers { firstArg() }
        }
        helper = ZipImportHelper(recipeRepository, serializer, mealPlanRepository, json, imageDownloadService)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun recipe(id: String, name: String) = Recipe(
        id = id,
        name = name,
        createdAt = now,
        updatedAt = now
    )

    private fun mealPlan(id: String, recipeId: String) = MealPlanEntry(
        id = id,
        recipeId = recipeId,
        recipeName = "x",
        recipeImageUrl = null,
        date = LocalDate(2026, 1, 1),
        mealType = MealType.LUNCH,
        createdAt = now,
        updatedAt = now
    )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun recipeJson(recipe: Recipe) = serializer.serializeRecipe(recipe).recipeJson.toByteArray()

    private fun sampleZip(): ByteArray {
        val a = recipe("id-a", "Pancakes")
        val b = recipe("id-b", "Pancakes")
        return zipOf(
            "Pancakes (ida)/recipe.json" to recipeJson(a),
            "Pancakes (ida)/image.jpg" to byteArrayOf(1, 2, 3),
            "Pancakes (idb)/recipe.json" to recipeJson(b),
            "Broken/recipe.json" to "{not json".toByteArray(),
            "meal-plans/2026-01-01.json" to json.encodeToString(
                listOf(mealPlan("m1", "id-a"), mealPlan("m2", "id-b"), mealPlan("m3", "missing"))
            ).toByteArray()
        )
    }

    @Test
    fun `readRecipeIndex lists recipes and skips malformed entries`() {
        val index = helper.readRecipeIndex(ByteArrayInputStream(sampleZip()))
        assertEquals(listOf(ZipRecipeIndexEntry("id-a", "Pancakes"), ZipRecipeIndexEntry("id-b", "Pancakes")), index)
    }

    @Test
    fun `importFromZip imports all recipes and only meal plans for present recipes`() = runTest {
        val summary = helper.importFromZip(ByteArrayInputStream(sampleZip()), selectedRecipeIds = null)

        assertEquals(2, summary.importedCount)
        assertEquals(1, summary.failedCount)
        assertEquals(2, summary.mealPlansImported)
        assertEquals(setOf("m1", "m2"), savedMealPlans.map { it.id }.toSet())
        assertEquals("file:///cache/image.jpg", savedRecipes.first { it.id == "id-a" }.imageUrl)
    }

    @Test
    fun `importFromZip with selection skips unselected recipes and their meal plans`() = runTest {
        val summary = helper.importFromZip(ByteArrayInputStream(sampleZip()), selectedRecipeIds = setOf("id-b"))

        assertEquals(1, summary.importedCount)
        assertEquals(0, summary.failedCount)
        assertEquals(listOf("id-b"), savedRecipes.map { it.id })
        assertEquals(listOf("m2"), savedMealPlans.map { it.id })
        // The unselected recipe's image follows its recipe.json, so it is never written to disk.
        verify(exactly = 0) { imageDownloadService.saveImageFromStream(any(), any()) }
    }

    @Test
    fun `importFromZip skips recipes that already exist but keeps their meal plans`() = runTest {
        savedRecipes.add(recipe("id-a", "Pancakes"))

        val summary = helper.importFromZip(ByteArrayInputStream(sampleZip()), selectedRecipeIds = null)

        assertEquals(1, summary.importedCount)
        assertEquals(1, summary.skippedCount)
        assertEquals(2, summary.mealPlansImported)
    }

    @Test
    fun `export folder names are unique for recipes with the same name`() {
        val first = serializer.exportFolderName(recipe("11111111-aaaa", "Pancakes"))
        val second = serializer.exportFolderName(recipe("22222222-bbbb", "Pancakes"))
        assertNotEquals(first, second)
    }
}
