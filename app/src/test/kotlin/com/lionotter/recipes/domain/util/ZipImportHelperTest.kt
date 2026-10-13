package com.lionotter.recipes.domain.util

import com.lionotter.recipes.domain.model.MealPlanEntry
import com.lionotter.recipes.domain.model.MealType
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.testutil.FakeImageStore
import com.lionotter.recipes.testutil.FakeMealPlanRepository
import com.lionotter.recipes.testutil.FakeRecipeRepository
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    private val recipeRepository = FakeRecipeRepository()
    private val mealPlanRepository = FakeMealPlanRepository()
    private val imageStore = FakeImageStore()
    private val helper = ZipImportHelper(recipeRepository, serializer, mealPlanRepository, json, imageStore)
    private val savedRecipes get() = recipeRepository.saved
    private val savedMealPlans get() = mealPlanRepository.entries.value.values

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
        assertEquals("gs://bucket/stream.jpg", savedRecipes.first { it.id == "id-a" }.imageUrl)
        assertEquals(listOf(3), imageStore.savedStreams)
    }

    @Test
    fun `importFromZip with selection skips unselected recipes and their meal plans`() = runTest {
        val summary = helper.importFromZip(ByteArrayInputStream(sampleZip()), selectedRecipeIds = setOf("id-b"))

        assertEquals(1, summary.importedCount)
        assertEquals(0, summary.failedCount)
        assertEquals(listOf("id-b"), savedRecipes.map { it.id })
        assertEquals(listOf("m2"), savedMealPlans.map { it.id })
        // The unselected recipe's image follows its recipe.json, so it is never written to disk.
        assertEquals(emptyList<Int>(), imageStore.savedStreams)
    }

    @Test
    fun `importFromZip skips recipes that already exist but keeps their meal plans`() = runTest {
        recipeRepository.recipes.value = mapOf("id-a" to recipe("id-a", "Pancakes"))

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
