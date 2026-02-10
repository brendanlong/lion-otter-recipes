package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.InstructionSection
import com.lionotter.recipes.domain.model.InstructionStep
import com.lionotter.recipes.domain.model.MeasurementPreference
import com.lionotter.recipes.domain.model.Recipe
import com.lionotter.recipes.domain.model.UnitSystem
import com.lionotter.recipes.domain.model.createInstructionIngredientKey
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CalculateIngredientUsageUseCaseTest {

    private lateinit var useCase: CalculateIngredientUsageUseCase

    private val now = Instant.fromEpochMilliseconds(0)

    private fun recipe(sections: List<InstructionSection>) = Recipe(
        id = "test",
        name = "Test Recipe",
        instructionSections = sections,
        createdAt = now,
        updatedAt = now
    )

    private fun section(steps: List<InstructionStep>, name: String? = null) =
        InstructionSection(name = name, steps = steps)

    private fun step(stepNumber: Int, ingredients: List<Ingredient>, yields: Int = 1) =
        InstructionStep(stepNumber = stepNumber, instruction = "Step $stepNumber", ingredients = ingredients, yields = yields)

    // Conversion factors matching Recipe.kt
    private val TO_ML = mapOf(
        "mL" to 1.0, "L" to 1000.0, "tsp" to 4.929, "tbsp" to 14.787,
        "cup" to 236.588, "fl_oz" to 29.574, "pint" to 473.176, "quart" to 946.353, "gal" to 3785.41
    )
    private val TO_GRAMS = mapOf("mg" to 0.001, "g" to 1.0, "kg" to 1000.0, "oz" to 28.3495, "lb" to 453.592)

    /** Convert a display amount to base units (mL or grams) for unit-agnostic assertions. */
    private fun toBase(value: Double, unit: String): Double {
        return TO_ML[unit]?.let { value * it }
            ?: TO_GRAMS[unit]?.let { value * it }
            ?: error("Unknown unit: $unit")
    }

    @Before
    fun setup() {
        useCase = CalculateIngredientUsageUseCase()
    }

    // --- Bug #137: Mixed volume units not summed correctly ---

    @Test
    fun `remaining is correct when flour uses cups in one step and tbsp in another`() {
        // Issue #137: Step 1 uses 3.33 cups flour, Step 2 uses 1 tbsp flour.
        // After checking step 1, remaining should be ~1 tbsp (14.787 mL),
        // NOT ~1 cup (236.588 mL) which was the old buggy behavior.
        val recipe = recipe(listOf(section(listOf(
            step(1, listOf(Ingredient(name = "flour", amount = Amount(3.33, "cup"), density = 0.51))),
            step(2, listOf(Ingredient(name = "flour", amount = Amount(1.0, "tbsp"), density = 0.51)))
        ))))

        val usedKeys = setOf(createInstructionIngredientKey(0, 0, 0))

        val result = useCase.execute(
            recipe = recipe,
            usedInstructionIngredients = usedKeys,
            scale = 1.0,
            measurementPreference = MeasurementPreference.DEFAULT
        )

        val flour = result["flour"]
        assertNotNull(flour)
        assertFalse(flour!!.isFullyUsed)
        // Remaining in base units should be ~14.787 mL (1 tbsp), regardless of display unit chosen
        val remainingMl = toBase(flour.remainingAmount!!, flour.remainingUnit!!)
        assertEquals(14.787, remainingMl, 1.0)
    }

    @Test
    fun `remaining is correct with mixed units in weight mode`() {
        val recipe = recipe(listOf(section(listOf(
            step(1, listOf(Ingredient(name = "flour", amount = Amount(3.33, "cup"), density = 0.51))),
            step(2, listOf(Ingredient(name = "flour", amount = Amount(1.0, "tbsp"), density = 0.51)))
        ))))

        val usedKeys = setOf(createInstructionIngredientKey(0, 0, 0))

        val result = useCase.execute(
            recipe = recipe,
            usedInstructionIngredients = usedKeys,
            scale = 1.0,
            measurementPreference = MeasurementPreference.WEIGHT
        )

        val flour = result["flour"]
        assertNotNull(flour)
        assertFalse(flour!!.isFullyUsed)
        // 1 tbsp flour in weight: 14.787 mL * 0.51 g/mL ≈ 7.54g
        val remainingGrams = toBase(flour.remainingAmount!!, flour.remainingUnit!!)
        assertEquals(7.54, remainingGrams, 1.0)
    }

    @Test
    fun `fully used when all steps checked off with mixed units`() {
        val recipe = recipe(listOf(section(listOf(
            step(1, listOf(Ingredient(name = "flour", amount = Amount(3.33, "cup"), density = 0.51))),
            step(2, listOf(Ingredient(name = "flour", amount = Amount(1.0, "tbsp"), density = 0.51)))
        ))))

        val usedKeys = setOf(
            createInstructionIngredientKey(0, 0, 0),
            createInstructionIngredientKey(0, 1, 0)
        )

        val result = useCase.execute(
            recipe = recipe,
            usedInstructionIngredients = usedKeys,
            scale = 1.0,
            measurementPreference = MeasurementPreference.DEFAULT
        )

        val flour = result["flour"]
        assertNotNull(flour)
        assertTrue(flour!!.isFullyUsed)
    }

    // --- Basic functionality ---

    @Test
    fun `no usage when nothing checked off`() {
        val recipe = recipe(listOf(section(listOf(
            step(1, listOf(Ingredient(name = "sugar", amount = Amount(1.0, "cup"), density = 0.84)))
        ))))

        val result = useCase.execute(
            recipe = recipe,
            usedInstructionIngredients = emptySet(),
            scale = 1.0,
            measurementPreference = MeasurementPreference.DEFAULT
        )

        val sugar = result["sugar"]
        assertNotNull(sugar)
        assertFalse(sugar!!.isFullyUsed)
        assertEquals(0.0, sugar.usedAmount, 0.01)
    }

    @Test
    fun `single ingredient fully used`() {
        val recipe = recipe(listOf(section(listOf(
            step(1, listOf(Ingredient(name = "sugar", amount = Amount(1.0, "cup"), density = 0.84)))
        ))))

        val usedKeys = setOf(createInstructionIngredientKey(0, 0, 0))

        val result = useCase.execute(
            recipe = recipe,
            usedInstructionIngredients = usedKeys,
            scale = 1.0,
            measurementPreference = MeasurementPreference.DEFAULT
        )

        val sugar = result["sugar"]
        assertNotNull(sugar)
        assertTrue(sugar!!.isFullyUsed)
    }

    // --- Count items (no unit) ---

    @Test
    fun `count items work correctly without units`() {
        val recipe = recipe(listOf(section(listOf(
            step(1, listOf(Ingredient(name = "eggs", amount = Amount(2.0, null)))),
            step(2, listOf(Ingredient(name = "eggs", amount = Amount(1.0, null))))
        ))))

        val usedKeys = setOf(createInstructionIngredientKey(0, 0, 0))

        val result = useCase.execute(
            recipe = recipe,
            usedInstructionIngredients = usedKeys,
            scale = 1.0,
            measurementPreference = MeasurementPreference.DEFAULT
        )

        val eggs = result["eggs"]
        assertNotNull(eggs)
        assertFalse(eggs!!.isFullyUsed)
        // Count items have no unit category, so remaining display is null
        // but the base math should still be correct: total 3, used 2, remaining 1
        assertNull(eggs.unit)
    }

    // --- Same unit across steps ---

    @Test
    fun `same unit across steps sums correctly`() {
        val recipe = recipe(listOf(section(listOf(
            step(1, listOf(Ingredient(name = "butter", amount = Amount(2.0, "tbsp"), density = 0.96))),
            step(2, listOf(Ingredient(name = "butter", amount = Amount(3.0, "tbsp"), density = 0.96)))
        ))))

        val usedKeys = setOf(createInstructionIngredientKey(0, 0, 0))

        val result = useCase.execute(
            recipe = recipe,
            usedInstructionIngredients = usedKeys,
            scale = 1.0,
            measurementPreference = MeasurementPreference.DEFAULT
        )

        val butter = result["butter"]
        assertNotNull(butter)
        assertFalse(butter!!.isFullyUsed)
        // Total: 5 tbsp (73.935 mL), used: 2 tbsp (29.574 mL), remaining: 3 tbsp (44.361 mL)
        val remainingMl = toBase(butter.remainingAmount!!, butter.remainingUnit!!)
        assertEquals(44.361, remainingMl, 1.0)
    }

    // --- Scaling ---

    @Test
    fun `scaling doubles the amounts correctly`() {
        val recipe = recipe(listOf(section(listOf(
            step(1, listOf(Ingredient(name = "salt", amount = Amount(1.0, "tsp"), density = 1.22)))
        ))))

        val result = useCase.execute(
            recipe = recipe,
            usedInstructionIngredients = emptySet(),
            scale = 2.0,
            measurementPreference = MeasurementPreference.DEFAULT
        )

        val salt = result["salt"]
        assertNotNull(salt)
        // 2 tsp total at scale 2 → 9.858 mL → bestUnit picks tsp: 2.0
        val totalMl = toBase(salt!!.totalAmount!!, salt.unit!!)
        assertEquals(9.858, totalMl, 0.1)
    }

    // --- Yields multiplier ---

    @Test
    fun `step yields multiplier is applied`() {
        val recipe = recipe(listOf(section(listOf(
            step(1, listOf(Ingredient(name = "flour", amount = Amount(1.0, "cup"), density = 0.51)), yields = 3)
        ))))

        val result = useCase.execute(
            recipe = recipe,
            usedInstructionIngredients = emptySet(),
            scale = 1.0,
            measurementPreference = MeasurementPreference.DEFAULT
        )

        val flour = result["flour"]
        assertNotNull(flour)
        // 3 cups total → 709.764 mL
        val totalMl = toBase(flour!!.totalAmount!!, flour.unit!!)
        assertEquals(709.764, totalMl, 1.0)
    }

    // --- Weight ingredients with mixed units ---

    @Test
    fun `remaining correct for weight ingredients with grams and kg`() {
        val recipe = recipe(listOf(section(listOf(
            step(1, listOf(Ingredient(name = "flour", amount = Amount(1.0, "kg"), density = 0.51))),
            step(2, listOf(Ingredient(name = "flour", amount = Amount(50.0, "g"), density = 0.51)))
        ))))

        val usedKeys = setOf(createInstructionIngredientKey(0, 0, 0))

        val result = useCase.execute(
            recipe = recipe,
            usedInstructionIngredients = usedKeys,
            scale = 1.0,
            measurementPreference = MeasurementPreference.DEFAULT
        )

        val flour = result["flour"]
        assertNotNull(flour)
        assertFalse(flour!!.isFullyUsed)
        // Remaining should be 50g
        val remainingGrams = toBase(flour.remainingAmount!!, flour.remainingUnit!!)
        assertEquals(50.0, remainingGrams, 1.0)
    }

    // --- Case insensitive matching ---

    @Test
    fun `ingredient names are case insensitive`() {
        val recipe = recipe(listOf(section(listOf(
            step(1, listOf(Ingredient(name = "Flour", amount = Amount(2.0, "cup"), density = 0.51))),
            step(2, listOf(Ingredient(name = "flour", amount = Amount(1.0, "cup"), density = 0.51)))
        ))))

        val usedKeys = setOf(createInstructionIngredientKey(0, 0, 0))

        val result = useCase.execute(
            recipe = recipe,
            usedInstructionIngredients = usedKeys,
            scale = 1.0,
            measurementPreference = MeasurementPreference.DEFAULT
        )

        val flour = result["flour"]
        assertNotNull(flour)
        assertFalse(flour!!.isFullyUsed)
        // Total: 3 cups (709.764 mL), used: 2 cups (473.176 mL), remaining: 1 cup (236.588 mL)
        val remainingMl = toBase(flour.remainingAmount!!, flour.remainingUnit!!)
        assertEquals(236.588, remainingMl, 1.0)
    }

    // --- Ingredient with null amount ---

    @Test
    fun `ingredient with null amount tracked correctly`() {
        val recipe = recipe(listOf(section(listOf(
            step(1, listOf(Ingredient(name = "salt", notes = "to taste")))
        ))))

        val usedKeys = setOf(createInstructionIngredientKey(0, 0, 0))

        val result = useCase.execute(
            recipe = recipe,
            usedInstructionIngredients = usedKeys,
            scale = 1.0,
            measurementPreference = MeasurementPreference.DEFAULT
        )

        val salt = result["salt"]
        assertNotNull(salt)
        // Null amount means totalAmount is null; usedAmount is 0 since there's no numeric value
        assertNull(salt!!.totalAmount)
    }

    // --- Alternates ---

    @Test
    fun `alternates are tracked separately`() {
        val recipe = recipe(listOf(section(listOf(
            step(1, listOf(
                Ingredient(
                    name = "kosher salt",
                    amount = Amount(1.0, "tsp"),
                    density = 0.54,
                    alternates = listOf(
                        Ingredient(name = "table salt", amount = Amount(0.5, "tsp"), density = 1.22)
                    )
                )
            )),
            step(2, listOf(
                Ingredient(
                    name = "kosher salt",
                    amount = Amount(0.5, "tsp"),
                    density = 0.54,
                    alternates = listOf(
                        Ingredient(name = "table salt", amount = Amount(0.25, "tsp"), density = 1.22)
                    )
                )
            ))
        ))))

        val usedKeys = setOf(createInstructionIngredientKey(0, 0, 0))

        val result = useCase.execute(
            recipe = recipe,
            usedInstructionIngredients = usedKeys,
            scale = 1.0,
            measurementPreference = MeasurementPreference.DEFAULT
        )

        val kosherSalt = result["kosher salt"]
        assertNotNull(kosherSalt)
        assertFalse(kosherSalt!!.isFullyUsed)
        // Total: 1.5 tsp (7.3935 mL), used: 1 tsp (4.929 mL), remaining: 0.5 tsp (2.4645 mL)
        val remainingMl = toBase(kosherSalt.remainingAmount!!, kosherSalt.remainingUnit!!)
        assertEquals(2.4645, remainingMl, 0.5)

        val tableSalt = result["table salt"]
        assertNotNull(tableSalt)
        assertFalse(tableSalt!!.isFullyUsed)
        // Total: 0.75 tsp (3.697 mL), used: 0.5 tsp (2.4645 mL), remaining: 0.25 tsp (1.232 mL)
        val altRemainingMl = toBase(tableSalt.remainingAmount!!, tableSalt.remainingUnit!!)
        assertEquals(1.232, altRemainingMl, 0.5)
    }
}
