package com.lionotter.recipes.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale
import kotlin.time.Instant

class AmountMathTest {

    // --- formatQuantity ---

    @Test
    fun `values just below a whole number round up`() {
        assertEquals("2", formatQuantity(1.98))
        assertEquals("1", formatQuantity(0.33 * 3))
        assertEquals("3", formatQuantity(2.97))
    }

    @Test
    fun `eighths are shown as fractions`() {
        assertEquals("1/8", formatQuantity(0.125))
        assertEquals("1/8", formatQuantity(0.13))
        assertEquals("1 7/8", formatQuantity(1.875))
        assertEquals("3/8", formatQuantity(0.375))
    }

    @Test
    fun `tiny amounts are shown as decimals rather than zero`() {
        assertEquals("0.03", formatQuantity(0.03))
    }

    @Test
    fun `decimal formatting does not depend on device locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1", formatQuantity(0.999))
            assertEquals("2.5", formatWeightQuantity(2.5))
            assertEquals("0.25", formatWeightQuantity(0.25))
            assertEquals("2.5 g", formatAmount(2.5, "g"))
        } finally {
            Locale.setDefault(original)
        }
    }

    // --- formatLbOz / formatAmount ---

    @Test
    fun `lb oz never shows 16 oz`() {
        assertEquals("2 lb", formatLbOz(31.97))
        assertEquals("1 lb", formatAmount(15.7, "oz"))
        assertEquals("15 oz", formatAmount(15.4, "oz"))
        assertEquals("1 lb 15 oz", formatAmount(30.6, "oz"))
    }

    @Test
    fun `metric volume is rounded sensibly`() {
        assertEquals("237 mL", formatAmount(236.588, "mL"))
        assertEquals("4.9 mL", formatAmount(4.929, "ml"))
        assertEquals("1.2 L", formatAmount(1.183, "L"))
    }

    @Test
    fun `unit spellings are normalized for display`() {
        assertEquals("2 cups", formatAmount(2.0, "Cups"))
        assertEquals("1 tbsp", formatAmount(1.0, "Tbsp."))
        assertEquals("1/2 cup", formatAmount(0.5, "cup"))
        assertEquals("1 1/2 cups", formatAmount(1.5, "cup"))
        assertEquals("2 fl oz", formatAmount(2.0, "fl_oz"))
    }

    @Test
    fun `unknown units are pluralized by count`() {
        assertEquals("3 cloves", formatAmount(3.0, "cloves"))
        assertEquals("1 clove", formatAmount(1.0, "cloves"))
        assertEquals("2 pinches", formatAmount(2.0, "pinch"))
    }

    @Test
    fun `formatAmounts joins amounts with plus`() {
        assertEquals("1 cup + 50 g", formatAmounts(listOf(Amount(1.0, "cup"), Amount(50.0, "g"))))
        assertNull(formatAmounts(listOf(Amount(null, "cup"))))
        assertNull(formatAmounts(emptyList()))
    }

    // --- Unit normalization & conversion tables ---

    @Test
    fun `canonicalUnit normalizes common spellings`() {
        val expected = mapOf(
            "Tbsp." to "tbsp", "tablespoons" to "tbsp", "TBSP" to "tbsp", "tbs" to "tbsp",
            "teaspoon" to "tsp", "tsp." to "tsp",
            "Cup" to "cup", "cups" to "cup",
            "grams" to "g", "g." to "g", "gram" to "g",
            "lbs" to "lb", "pounds" to "lb", "ounces" to "oz", "oz." to "oz",
            "ml" to "mL", "ML" to "mL", "milliliters" to "mL", "l" to "L", "litres" to "L",
            "fl. oz." to "fl_oz", "fl oz" to "fl_oz", "fluid ounces" to "fl_oz",
            "kilograms" to "kg", "quarts" to "quart", "gallons" to "gal"
        )
        for ((input, canonical) in expected) {
            assertEquals("canonical of $input", canonical, canonicalUnit(input))
        }
        assertNull(canonicalUnit("clove"))
        assertNull(canonicalUnit("pinch"))
    }

    @Test
    fun `conversion factors are accurate`() {
        assertEquals(236.588, toBaseUnitValue(1.0, "Cup")!!, 0.001)
        assertEquals(14.787, toBaseUnitValue(1.0, "tablespoons")!!, 0.001)
        assertEquals(4.929, toBaseUnitValue(1.0, "tsp")!!, 0.001)
        assertEquals(29.574, toBaseUnitValue(1.0, "fl oz")!!, 0.001)
        assertEquals(28.3495, toBaseUnitValue(1.0, "oz")!!, 0.0001)
        assertEquals(453.592, toBaseUnitValue(1.0, "lbs")!!, 0.001)
        assertEquals(1000.0, toBaseUnitValue(1.0, "l")!!, 0.001)
        assertEquals(UnitCategory.VOLUME, unitType("ml"))
        assertEquals(UnitCategory.WEIGHT, unitType("Grams"))
    }

    // --- Readable unit choice when converting ---

    private fun format(value: Double, unit: String, volumeSystem: UnitSystem) =
        Ingredient(name = "water", amount = Amount(value, unit))
            .format(volumeSystem = volumeSystem, weightSystem = volumeSystem)

    @Test
    fun `metric to customary volume picks a measurable unit`() {
        assertEquals("1 cup water", format(250.0, "mL", UnitSystem.CUSTOMARY))
        assertEquals("1 tsp water", format(5.0, "mL", UnitSystem.CUSTOMARY))
        assertEquals("1 tbsp water", format(15.0, "mL", UnitSystem.CUSTOMARY))
        assertEquals("1/2 cup water", format(120.0, "mL", UnitSystem.CUSTOMARY))
        assertEquals("1/4 tsp water", format(1.25, "mL", UnitSystem.CUSTOMARY))
    }

    @Test
    fun `customary to metric volume shows whole mL`() {
        assertEquals("237 mL water", format(1.0, "cup", UnitSystem.METRIC))
        assertEquals("15 mL water", format(1.0, "tbsp", UnitSystem.METRIC))
        assertEquals("1.2 L water", format(5.0, "cup", UnitSystem.METRIC))
    }

    @Test
    fun `non-canonical unit spelling is converted and displayed canonically`() {
        assertEquals("2 cups water", format(2.0, "Cups", UnitSystem.CUSTOMARY))
        assertEquals("473 mL water", format(2.0, "Cups", UnitSystem.METRIC))
        assertEquals("1 lb water", format(1.0, "lbs", UnitSystem.CUSTOMARY))
    }

    // --- Recipe.aggregateIngredients ---

    private val now = Instant.fromEpochMilliseconds(0)

    private fun recipeOf(vararg steps: List<Ingredient>, yields: List<Int> = steps.map { 1 }) = Recipe(
        id = "r",
        name = "R",
        instructionSections = listOf(
            InstructionSection(
                steps = steps.mapIndexed { index, ingredients ->
                    InstructionStep(index + 1, "Step", ingredients, yields = yields[index])
                }
            )
        ),
        createdAt = now,
        updatedAt = now
    )

    private fun Recipe.aggregatedLines() =
        aggregateIngredients().single().ingredients.map { it.format(volumeSystem = UnitSystem.CUSTOMARY, weightSystem = UnitSystem.METRIC) }

    @Test
    fun `amount-less first occurrence does not lose the unit of later ones`() {
        val recipe = recipeOf(
            listOf(Ingredient(name = "salt", notes = "to taste")),
            listOf(Ingredient(name = "salt", amount = Amount(1.0, "tsp")))
        )
        assertEquals(listOf("1 tsp salt, to taste"), recipe.aggregatedLines())
    }

    @Test
    fun `weight and volume without density are kept separate`() {
        val recipe = recipeOf(
            listOf(Ingredient(name = "butter", amount = Amount(1.0, "cup"))),
            listOf(Ingredient(name = "butter", amount = Amount(50.0, "g")))
        )
        assertEquals(listOf("1 cup butter", "50 g butter"), recipe.aggregatedLines())
    }

    @Test
    fun `weight and volume with density are combined`() {
        val recipe = recipeOf(
            listOf(Ingredient(name = "butter", amount = Amount(1.0, "cup"), density = 0.96)),
            listOf(Ingredient(name = "butter", amount = Amount(48.0, "g"), density = 0.96))
        )
        assertEquals(listOf("1 1/4 cups butter"), recipe.aggregatedLines())
    }

    @Test
    fun `unit spellings are converted before summing`() {
        val recipe = recipeOf(
            listOf(Ingredient(name = "milk", amount = Amount(1.0, "Cup"))),
            listOf(Ingredient(name = "milk", amount = Amount(120.0, "ml")))
        )
        assertEquals(listOf("1 1/2 cups milk"), recipe.aggregatedLines())
    }

    @Test
    fun `same unit is kept and summed exactly`() {
        val recipe = recipeOf(
            listOf(Ingredient(name = "oil", amount = Amount(1.0 / 3, "cup"))),
            listOf(Ingredient(name = "oil", amount = Amount(1.0 / 3, "cup")))
        )
        assertEquals(listOf("2/3 cup oil"), recipe.aggregatedLines())
    }

    @Test
    fun `mixed units pick a readable unit`() {
        val recipe = recipeOf(
            listOf(Ingredient(name = "sugar", amount = Amount(2.0, "tsp"))),
            listOf(Ingredient(name = "sugar", amount = Amount(1.0, "tsp"))),
            listOf(Ingredient(name = "sugar", amount = Amount(1.0, "tbsp")))
        )
        assertEquals(listOf("2 tbsp sugar"), recipe.aggregatedLines())
    }

    @Test
    fun `unknown units and counts are not mixed with measured amounts`() {
        val recipe = recipeOf(
            listOf(Ingredient(name = "garlic", amount = Amount(2.0, "cloves"))),
            listOf(Ingredient(name = "garlic", amount = Amount(1.0, "tsp"))),
            listOf(Ingredient(name = "garlic", amount = Amount(1.0, "clove")))
        )
        assertEquals(listOf("1 tsp garlic", "3 cloves garlic"), recipe.aggregatedLines())
    }

    @Test
    fun `different modifiers are listed separately and the same modifier is summed`() {
        val recipe = recipeOf(
            listOf(Ingredient(name = "butter", amount = Amount(0.5, "cup"), modifier = "softened")),
            listOf(Ingredient(name = "butter", amount = Amount(2.0, "tbsp"), modifier = "melted")),
            listOf(Ingredient(name = "Butter", amount = Amount(0.25, "cup"), modifier = "Softened"))
        )
        assertEquals(
            listOf("3/4 cup butter, softened", "2 tbsp butter, melted"),
            recipe.aggregatedLines()
        )
    }

    @Test
    fun `yields multiply amounts`() {
        val recipe = recipeOf(
            listOf(Ingredient(name = "flour", amount = Amount(100.0, "g"))),
            yields = listOf(3)
        )
        assertEquals(listOf("300 g flour"), recipe.aggregatedLines())
    }

    @Test
    fun `alternates are aggregated too`() {
        fun salt(amount: Double) = Ingredient(
            name = "kosher salt",
            amount = Amount(amount, "tsp"),
            alternates = listOf(Ingredient(name = "table salt", amount = Amount(amount / 2, "tsp")))
        )
        val recipe = recipeOf(listOf(salt(1.0)), listOf(salt(1.0)))
        val aggregated = recipe.aggregateIngredients().single().ingredients.single()
        assertEquals(Amount(2.0, "tsp"), aggregated.amount)
        assertEquals(Amount(1.0, "tsp"), aggregated.alternates.single().amount)
    }

    // --- Grocery totals ---

    @Test
    fun `formatIngredientTotal sums same category`() {
        val milk = Ingredient(name = "milk", amount = Amount(1.0, "cup"))
        assertEquals("3 cups", formatIngredientTotal(listOf(milk to 2.0, milk to 1.0), UnitSystem.CUSTOMARY, UnitSystem.CUSTOMARY))
    }

    @Test
    fun `formatIngredientTotal converts volume to weight with density`() {
        val items = listOf(
            Ingredient(name = "flour", amount = Amount(1.0, "cup"), density = 0.51) to 1.0,
            Ingredient(name = "flour", amount = Amount(100.0, "g")) to 1.0
        )
        assertEquals("221 g", formatIngredientTotal(items, UnitSystem.METRIC, UnitSystem.METRIC))
    }

    @Test
    fun `formatIngredientTotal keeps volume when no density`() {
        val items = listOf(
            Ingredient(name = "flour", amount = Amount(1.0, "cup")) to 1.0,
            Ingredient(name = "flour", amount = Amount(100.0, "g")) to 1.0
        )
        assertEquals("1 cup + 100 g", formatIngredientTotal(items, UnitSystem.CUSTOMARY, UnitSystem.METRIC))
    }

    @Test
    fun `formatIngredientTotal includes unknown units and counts`() {
        val items = listOf(
            Ingredient(name = "garlic", amount = Amount(2.0, "cloves")) to 1.0,
            Ingredient(name = "garlic", amount = Amount(1.0, "tsp")) to 1.0,
            Ingredient(name = "garlic", amount = Amount(1.0)) to 1.0
        )
        assertEquals("1 tsp + 2 cloves + 1", formatIngredientTotal(items, UnitSystem.CUSTOMARY, UnitSystem.CUSTOMARY))
    }

    @Test
    fun `formatIngredientTotal sums counts and returns null without amounts`() {
        val eggs = Ingredient(name = "eggs", amount = Amount(3.0))
        assertEquals("5", formatIngredientTotal(listOf(eggs to 1.0, eggs.copy(amount = Amount(2.0)) to 1.0), UnitSystem.METRIC, UnitSystem.METRIC))
        val salt = Ingredient(name = "salt", notes = "to taste")
        assertNull(formatIngredientTotal(listOf(salt to 1.0, salt to 2.0), UnitSystem.METRIC, UnitSystem.METRIC))
    }
}
