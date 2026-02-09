package com.lionotter.recipes.data.paprika

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PaprikaParserTest {

    private lateinit var parser: PaprikaParser
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Before
    fun setUp() {
        parser = PaprikaParser(json)
    }

    private val sampleRecipeJson = """
    {
        "uid": "7bf2762d-d6dc-4d56-bdbe-0de4d92df745",
        "created": "2024-01-15 10:30:00",
        "hash": "abc123def456",
        "name": "Classic Waffles",
        "description": "Light and crispy waffles perfect for weekend breakfast",
        "ingredients": "2 cups all-purpose flour\n2 tablespoons sugar\n1 tablespoon baking powder\n1/2 teaspoon salt\n2 large eggs\n1 3/4 cups milk\n1/2 cup melted butter\n1 teaspoon vanilla extract",
        "directions": "1. Preheat your waffle iron.\n2. In a large bowl, whisk together flour, sugar, baking powder, and salt.\n3. In a separate bowl, beat the eggs, then add milk, melted butter, and vanilla.\n4. Pour the wet ingredients into the dry ingredients and stir until just combined.\n5. Cook according to your waffle iron's instructions until golden and crispy.",
        "notes": "For extra crispy waffles, let the batter rest for 5 minutes before cooking.",
        "nutritional_info": "",
        "prep_time": "10 minutes",
        "cook_time": "20 minutes",
        "total_time": "30 minutes",
        "difficulty": "Easy",
        "servings": "about 8",
        "rating": 5,
        "source": "Seriouseats.com",
        "source_url": "https://www.seriouseats.com/classic-waffles",
        "image_url": "https://www.seriouseats.com/images/waffles.jpg",
        "photo": "087d4de4-abc123.jpg",
        "photo_large": null,
        "photo_hash": "xyz789",
        "photo_data": null,
        "categories": ["Breakfast", "Weekend"],
        "photos": []
    }
    """.trimIndent()

    private val minimalRecipeJson = """
    {
        "uid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        "created": "",
        "hash": "",
        "name": "Simple Toast",
        "description": "",
        "ingredients": "2 slices bread\nbutter",
        "directions": "Toast the bread. Spread butter on top.",
        "notes": "",
        "nutritional_info": "",
        "prep_time": "",
        "cook_time": "",
        "total_time": "",
        "difficulty": "",
        "servings": "",
        "rating": 0,
        "source": "",
        "source_url": "",
        "image_url": "",
        "photo": null,
        "photo_large": null,
        "photo_hash": "",
        "photo_data": null,
        "categories": [],
        "photos": []
    }
    """.trimIndent()

    private fun gzipCompress(data: String): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzip ->
            gzip.write(data.toByteArray(Charsets.UTF_8))
        }
        return baos.toByteArray()
    }

    private fun createPaprikaExport(recipes: Map<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            recipes.forEach { (name, jsonContent) ->
                val compressed = gzipCompress(jsonContent)
                zip.putNextEntry(ZipEntry("$name.paprikarecipe"))
                zip.write(compressed)
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    @Test
    fun `parseRecipeEntry parses gzip-compressed JSON`() {
        val compressed = gzipCompress(sampleRecipeJson)
        val recipe = parser.parseRecipeEntry(compressed)

        assertEquals("Classic Waffles", recipe.name)
        assertEquals("7bf2762d-d6dc-4d56-bdbe-0de4d92df745", recipe.uid)
        assertEquals("Light and crispy waffles perfect for weekend breakfast", recipe.description)
        assertEquals(5, recipe.rating)
        assertEquals("about 8", recipe.servings)
        assertEquals("10 minutes", recipe.prepTime)
        assertEquals("20 minutes", recipe.cookTime)
        assertEquals("30 minutes", recipe.totalTime)
        assertEquals("Easy", recipe.difficulty)
        assertEquals("Seriouseats.com", recipe.source)
        assertEquals("https://www.seriouseats.com/classic-waffles", recipe.sourceUrl)
        assertEquals("https://www.seriouseats.com/images/waffles.jpg", recipe.imageUrl)
        assertEquals(listOf("Breakfast", "Weekend"), recipe.categories)
        assertTrue(recipe.ingredients!!.contains("2 cups all-purpose flour"))
        assertTrue(recipe.directions!!.contains("Preheat your waffle iron"))
        assertTrue(recipe.notes!!.contains("extra crispy"))
    }

    @Test
    fun `parseExport parses ZIP containing multiple gzip-compressed recipes`() {
        val exportData = createPaprikaExport(
            mapOf(
                "Classic Waffles" to sampleRecipeJson,
                "Simple Toast" to minimalRecipeJson
            )
        )

        val recipes = parser.parseExport(ByteArrayInputStream(exportData))

        assertEquals(2, recipes.size)
        val recipeNames = recipes.map { it.name }.toSet()
        assertTrue(recipeNames.contains("Classic Waffles"))
        assertTrue(recipeNames.contains("Simple Toast"))
    }

    @Test
    fun `parseExport handles empty export`() {
        val exportData = createPaprikaExport(emptyMap())
        val recipes = parser.parseExport(ByteArrayInputStream(exportData))
        assertTrue(recipes.isEmpty())
    }

    @Test
    fun `parseExport skips non-paprikarecipe files`() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            // Add a valid recipe
            val compressed = gzipCompress(sampleRecipeJson)
            zip.putNextEntry(ZipEntry("Classic Waffles.paprikarecipe"))
            zip.write(compressed)
            zip.closeEntry()

            // Add a non-recipe file
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("not a recipe".toByteArray())
            zip.closeEntry()
        }

        val recipes = parser.parseExport(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(1, recipes.size)
        assertEquals("Classic Waffles", recipes[0].name)
    }

    @Test
    fun `parseRecipeEntry handles minimal recipe`() {
        val compressed = gzipCompress(minimalRecipeJson)
        val recipe = parser.parseRecipeEntry(compressed)

        assertEquals("Simple Toast", recipe.name)
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", recipe.uid)
        assertEquals("", recipe.description)
        assertEquals(0, recipe.rating)
        assertEquals("", recipe.servings)
        assertEquals("", recipe.prepTime)
        assertEquals("", recipe.cookTime)
        assertEquals("", recipe.source)
        assertEquals("", recipe.sourceUrl)
        assertEquals("", recipe.imageUrl)
        assertTrue(recipe.categories.isEmpty())
        assertTrue(recipe.photos.isEmpty())
    }

    @Test
    fun `formatForAi includes all relevant fields`() {
        val compressed = gzipCompress(sampleRecipeJson)
        val recipe = parser.parseRecipeEntry(compressed)
        val formatted = parser.formatForAi(recipe)

        assertTrue(formatted.contains("Recipe: Classic Waffles"))
        assertTrue(formatted.contains("Description: Light and crispy waffles"))
        assertTrue(formatted.contains("Servings: about 8"))
        assertTrue(formatted.contains("Prep Time: 10 minutes"))
        assertTrue(formatted.contains("Cook Time: 20 minutes"))
        assertTrue(formatted.contains("Total Time: 30 minutes"))
        assertTrue(formatted.contains("Difficulty: Easy"))
        assertTrue(formatted.contains("Ingredients:"))
        assertTrue(formatted.contains("- 2 cups all-purpose flour"))
        assertTrue(formatted.contains("Directions:"))
        assertTrue(formatted.contains("Preheat your waffle iron"))
        assertTrue(formatted.contains("Notes:"))
        assertTrue(formatted.contains("extra crispy"))
        assertTrue(formatted.contains("Categories: Breakfast, Weekend"))
        assertTrue(formatted.contains("Source: Seriouseats.com"))
    }

    @Test
    fun `formatForAi omits empty fields`() {
        val compressed = gzipCompress(minimalRecipeJson)
        val recipe = parser.parseRecipeEntry(compressed)
        val formatted = parser.formatForAi(recipe)

        assertTrue(formatted.contains("Recipe: Simple Toast"))
        assertTrue(formatted.contains("Ingredients:"))
        assertTrue(formatted.contains("Directions:"))
        // Empty fields should not appear
        assertTrue(!formatted.contains("Description:"))
        assertTrue(!formatted.contains("Servings:"))
        assertTrue(!formatted.contains("Prep Time:"))
        assertTrue(!formatted.contains("Cook Time:"))
        assertTrue(!formatted.contains("Total Time:"))
        assertTrue(!formatted.contains("Difficulty:"))
        assertTrue(!formatted.contains("Notes:"))
        assertTrue(!formatted.contains("Categories:"))
        assertTrue(!formatted.contains("Source:"))
    }

    @Test
    fun `parseRecipeEntry handles recipe with photos array`() {
        val recipeWithPhotos = """
        {
            "uid": "test-uid",
            "created": "2024-01-15 10:30:00",
            "hash": "",
            "name": "Recipe With Photos",
            "description": "",
            "ingredients": "ingredient 1",
            "directions": "step 1",
            "notes": "",
            "nutritional_info": "",
            "prep_time": "",
            "cook_time": "",
            "total_time": "",
            "difficulty": "",
            "servings": "",
            "rating": 0,
            "source": "",
            "source_url": "",
            "image_url": "",
            "photo": null,
            "photo_large": null,
            "photo_hash": "",
            "photo_data": "base64encodeddata",
            "categories": [],
            "photos": [
                {
                    "name": "1",
                    "filename": "FB95FEDD-test.jpg",
                    "hash": "52789FA",
                    "data": "base64photodata"
                }
            ]
        }
        """.trimIndent()

        val compressed = gzipCompress(recipeWithPhotos)
        val recipe = parser.parseRecipeEntry(compressed)

        assertEquals("Recipe With Photos", recipe.name)
        assertEquals("base64encodeddata", recipe.photoData)
        assertEquals(1, recipe.photos.size)
        assertEquals("FB95FEDD-test.jpg", recipe.photos[0].filename)
        assertEquals("base64photodata", recipe.photos[0].data)
    }

    @Test
    fun `formatForAi handles ingredients with newlines correctly`() {
        val compressed = gzipCompress(sampleRecipeJson)
        val recipe = parser.parseRecipeEntry(compressed)
        val formatted = parser.formatForAi(recipe)

        // Each ingredient should be on its own line, prefixed with "-"
        assertTrue(formatted.contains("- 2 cups all-purpose flour"))
        assertTrue(formatted.contains("- 2 tablespoons sugar"))
        assertTrue(formatted.contains("- 1 tablespoon baking powder"))
        assertTrue(formatted.contains("- 1/2 teaspoon salt"))
        assertTrue(formatted.contains("- 2 large eggs"))
    }

    @Test
    fun `parseRecipeEntry handles null string fields`() {
        val recipeWithNulls = """
        {
            "uid": "null-fields-test",
            "created": null,
            "hash": null,
            "name": "Null Fields Recipe",
            "description": null,
            "ingredients": "flour\nsugar",
            "directions": "Mix together.",
            "notes": null,
            "nutritional_info": null,
            "prep_time": null,
            "cook_time": null,
            "total_time": null,
            "difficulty": null,
            "servings": null,
            "rating": 0,
            "source": null,
            "source_url": null,
            "image_url": null,
            "photo": null,
            "photo_large": null,
            "photo_hash": null,
            "photo_data": null,
            "categories": [],
            "photos": []
        }
        """.trimIndent()

        val compressed = gzipCompress(recipeWithNulls)
        val recipe = parser.parseRecipeEntry(compressed)

        assertEquals("Null Fields Recipe", recipe.name)
        assertEquals("null-fields-test", recipe.uid)
        assertEquals(null, recipe.description)
        assertEquals(null, recipe.photoHash)
        assertEquals(null, recipe.source)
        assertEquals(null, recipe.sourceUrl)
        assertEquals(null, recipe.imageUrl)
        assertEquals(null, recipe.prepTime)
        assertEquals(null, recipe.cookTime)
        assertEquals(null, recipe.servings)
    }

    @Test
    fun `formatForAi handles null fields gracefully`() {
        val recipeWithNulls = """
        {
            "uid": "null-fields-test",
            "created": null,
            "hash": null,
            "name": "Null Fields Recipe",
            "description": null,
            "ingredients": "flour\nsugar",
            "directions": "Mix together.",
            "notes": null,
            "nutritional_info": null,
            "prep_time": null,
            "cook_time": null,
            "total_time": null,
            "difficulty": null,
            "servings": null,
            "rating": 0,
            "source": null,
            "source_url": null,
            "image_url": null,
            "photo": null,
            "photo_large": null,
            "photo_hash": null,
            "photo_data": null,
            "categories": [],
            "photos": []
        }
        """.trimIndent()

        val compressed = gzipCompress(recipeWithNulls)
        val recipe = parser.parseRecipeEntry(compressed)
        val formatted = parser.formatForAi(recipe)

        assertTrue(formatted.contains("Recipe: Null Fields Recipe"))
        assertTrue(formatted.contains("Ingredients:"))
        assertTrue(formatted.contains("- flour"))
        assertTrue(formatted.contains("- sugar"))
        assertTrue(formatted.contains("Directions:"))
        assertTrue(formatted.contains("Mix together."))
        // Null fields should not appear
        assertTrue(!formatted.contains("Description:"))
        assertTrue(!formatted.contains("Servings:"))
        assertTrue(!formatted.contains("Prep Time:"))
        assertTrue(!formatted.contains("Cook Time:"))
        assertTrue(!formatted.contains("Notes:"))
        assertTrue(!formatted.contains("Source:"))
    }

    @Test
    fun `parseExport reads example export from resources`() {
        // Load the example export from test resources
        val inputStream = javaClass.classLoader!!.getResourceAsStream("example_export.paprikarecipes")
        assertNotNull("Example export file should exist in test resources", inputStream)

        val recipes = parser.parseExport(inputStream!!)
        assertTrue("Should parse at least one recipe", recipes.isNotEmpty())

        val recipe = recipes[0]
        assertEquals("Classic Waffles", recipe.name)
        assertEquals("7bf2762d-d6dc-4d56-bdbe-0de4d92df745", recipe.uid)
    }
}
