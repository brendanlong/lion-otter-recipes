package com.lionotter.recipes.domain.usecase

import com.lionotter.recipes.domain.model.Amount
import com.lionotter.recipes.domain.model.Ingredient
import com.lionotter.recipes.domain.model.IngredientUsageStatus
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

    /**
     * Helper to get the ingredient usage for an unnamed section (name = null).
     * Most tests use unnamed sections.
     */
    private fun Map<String?, Map<String, IngredientUsageStatus>>.unnamed(): Map<String, IngredientUsageStatus> {
        return this[null] ?: emptyMap()
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
            measurementPreference = MeasurementPreference.DEFAULT,
            volumeSystem = UnitSystem.CUSTOMARY,
            weightSystem = UnitSystem.METRIC
        )

        val flour = result.unnamed()["flour"]
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
            measurementPreference = MeasurementPreference.WEIGHT,
            volumeSystem = UnitSystem.CUSTOMARY,
            weightSystem = UnitSystem.METRIC
        )

        val flour = result.unnamed()["flour"]
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
            measurementPreference = MeasurementPreference.DEFAULT,
            volumeSystem = UnitSystem.CUSTOMARY,
            weightSystem = UnitSystem.METRIC
        )

        val flour = result.unnamed()["flour"]
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
            measurementPreference = MeasurementPreference.DEFAULT,
            volumeSystem = UnitSystem.CUSTOMARY,
            weightSystem = UnitSystem.METRIC
        )

        val sugar = result.unnamed()["sugar"]
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
            measurementPreference = MeasurementPreference.DEFAULT,
            volumeSystem = UnitSystem.CUSTOMARY,
            weightSystem = UnitSystem.METRIC
        )

        val sugar = result.unnamed()["sugar"]
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
            measurementPreference = MeasurementPreference.DEFAULT,
            volumeSystem = UnitSystem.CUSTOMARY,
            weightSystem = UnitSystem.METRIC
        )

        val eggs = result.unnamed()["eggs"]
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
            measurementPreference = MeasurementPreference.DEFAULT,
            volumeSystem = UnitSystem.CUSTOMARY,
            weightSystem = UnitSystem.METRIC
        )

        val butter = result.unnamed()["butter"]
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
            measurementPreference = MeasurementPreference.DEFAULT,
            volumeSystem = UnitSystem.CUSTOMARY,
            weightSystem = UnitSystem.METRIC
        )

        val salt = result.unnamed()["salt"]
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
            measurementPreference = MeasurementPreference.DEFAULT,
            volumeSystem = UnitSystem.CUSTOMARY,
            weightSystem = UnitSystem.METRIC
        )

        val flour = result.unnamed()["flour"]
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
            measurementPreference = MeasurementPreference.DEFAULT,
            volumeSystem = UnitSystem.CUSTOMARY,
            weightSystem = UnitSystem.METRIC
        )

        val flour = result.unnamed()["flour"]
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
            measurementPreference = MeasurementPreference.DEFAULT,
            volumeSystem = UnitSystem.CUSTOMARY,
            weightSystem = UnitSystem.METRIC
        )

        val flour = result.unnamed()["flour"]
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
            measurementPreference = MeasurementPreference.DEFAULT,
            volumeSystem = UnitSystem.CUSTOMARY,
            weightSystem = UnitSystem.METRIC
        )

        val salt = result.unnamed()["salt"]
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
            measurementPreference = MeasurementPreference.DEFAULT,
            volumeSystem = UnitSystem.CUSTOMARY,
            weightSystem = UnitSystem.METRIC
        )

        val kosherSalt = result.unnamed()["kosher salt"]
        assertNotNull(kosherSalt)
        assertFalse(kosherSalt!!.isFullyUsed)
        // Total: 1.5 tsp (7.3935 mL), used: 1 tsp (4.929 mL), remaining: 0.5 tsp (2.4645 mL)
        val remainingMl = toBase(kosherSalt.remainingAmount!!, kosherSalt.remainingUnit!!)
        assertEquals(2.4645, remainingMl, 0.5)

        val tableSalt = result.unnamed()["table salt"]
        assertNotNull(tableSalt)
        assertFalse(tableSalt!!.isFullyUsed)
        // Total: 0.75 tsp (3.697 mL), used: 0.5 tsp (2.4645 mL), remaining: 0.25 tsp (1.232 mL)
        val altRemainingMl = toBase(tableSalt.remainingAmount!!, tableSalt.remainingUnit!!)
        assertEquals(1.232, altRemainingMl, 0.5)
    }

    // --- Bug #246: Per-section isolation ---

    @Test
    fun `checking off ingredient in one section does not affect another section`() {
        // Issue #246: Section A has 1 tbsp salt, Section B has 1 tsp salt.
        // Checking off the 1 tsp salt in Section B should not affect Section A.
        val recipe = recipe(listOf(
            section(
                name = "Section A",
                steps = listOf(
                    step(1, listOf(Ingredient(name = "salt", amount = Amount(1.0, "tbsp"), density = 1.22)))
                )
            ),
            section(
                name = "Section B",
                steps = listOf(
                    step(2, listOf(Ingredient(name = "salt", amount = Amount(1.0, "tsp"), density = 1.22)))
                )
            )
        ))

        // Check off the salt in Section B (section index 1, step 0, ingredient 0)
        val usedKeys = setOf(createInstructionIngredientKey(1, 0, 0))

        val result = useCase.execute(
            recipe = recipe,
            usedInstructionIngredients = usedKeys,
            scale = 1.0,
            measurementPreference = MeasurementPreference.DEFAULT,
            volumeSystem = UnitSystem.CUSTOMARY,
            weightSystem = UnitSystem.METRIC
        )

        // Section A's salt should be completely unaffected
        val sectionA = result["Section A"]
        assertNotNull("Section A should exist in results", sectionA)
        val saltA = sectionA!!["salt"]
        assertNotNull("Salt should exist in Section A", saltA)
        assertFalse("Section A salt should NOT be fully used", saltA!!.isFullyUsed)
        assertEquals("Section A salt should have 0 used", 0.0, saltA.usedAmount, 0.01)
        // Total should be 1 tbsp (14.787 mL)
        val totalMlA = toBase(saltA.totalAmount!!, saltA.unit!!)
        assertEquals(14.787, totalMlA, 0.5)

        // Section B's salt should be fully used
        val sectionB = result["Section B"]
        assertNotNull("Section B should exist in results", sectionB)
        val saltB = sectionB!!["salt"]
        assertNotNull("Salt should exist in Section B", saltB)
        assertTrue("Section B salt should be fully used", saltB!!.isFullyUsed)
    }

    @Test
    fun `same ingredient in multiple sections tracked independently`() {
        // Two sections each with flour; checking off flour in section 1 should
        // not affect section 2's remaining amount.
        val recipe = recipe(listOf(
            section(
                name = "Dough",
                steps = listOf(
                    step(1, listOf(Ingredient(name = "flour", amount = Amount(2.0, "cup"), density = 0.51))),
                    step(2, listOf(Ingredient(name = "flour", amount = Amount(1.0, "cup"), density = 0.51)))
                )
            ),
            section(
                name = "Glaze",
                steps = listOf(
                    step(3, listOf(Ingredient(name = "flour", amount = Amount(1.0, "tbsp"), density = 0.51)))
                )
            )
        ))

        // Check off step 1 flour in the Dough section (section 0, step 0, ingredient 0)
        val usedKeys = setOf(createInstructionIngredientKey(0, 0, 0))

        val result = useCase.execute(
            recipe = recipe,
            usedInstructionIngredients = usedKeys,
            scale = 1.0,
            measurementPreference = MeasurementPreference.DEFAULT,
            volumeSystem = UnitSystem.CUSTOMARY,
            weightSystem = UnitSystem.METRIC
        )

        // Dough section: total 3 cups, used 2 cups, remaining 1 cup
        val dough = result["Dough"]
        assertNotNull(dough)
        val flourDough = dough!!["flour"]
        assertNotNull(flourDough)
        assertFalse(flourDough!!.isFullyUsed)
        val remainingDoughMl = toBase(flourDough.remainingAmount!!, flourDough.remainingUnit!!)
        assertEquals(236.588, remainingDoughMl, 1.0) // ~1 cup

        // Glaze section: total 1 tbsp, nothing used, remaining 1 tbsp
        val glaze = result["Glaze"]
        assertNotNull(glaze)
        val flourGlaze = glaze!!["flour"]
        assertNotNull(flourGlaze)
        assertFalse(flourGlaze!!.isFullyUsed)
        assertEquals(0.0, flourGlaze.usedAmount, 0.01)
        val totalGlazeMl = toBase(flourGlaze.totalAmount!!, flourGlaze.unit!!)
        assertEquals(14.787, totalGlazeMl, 0.5) // ~1 tbsp
    }

    @Test
    fun `results keyed by section name for named sections`() {
        val recipe = recipe(listOf(
            section(
                name = "Sauce",
                steps = listOf(step(1, listOf(Ingredient(name = "garlic", amount = Amount(2.0, null)))))
            ),
            section(
                name = "Pasta",
                steps = listOf(step(2, listOf(Ingredient(name = "garlic", amount = Amount(1.0, null)))))
            )
        ))

        val result = useCase.execute(
            recipe = recipe,
            usedInstructionIngredients = emptySet(),
            scale = 1.0,
            measurementPreference = MeasurementPreference.DEFAULT,
            volumeSystem = UnitSystem.CUSTOMARY,
            weightSystem = UnitSystem.METRIC
        )

        assertTrue("Result should contain Sauce section", result.containsKey("Sauce"))
        assertTrue("Result should contain Pasta section", result.containsKey("Pasta"))
        assertFalse("Result should not contain null section", result.containsKey(null))
    }
}
