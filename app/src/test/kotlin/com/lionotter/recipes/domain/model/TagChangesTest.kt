package com.lionotter.recipes.domain.model

import com.lionotter.recipes.domain.model.TagChange.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TagChangesTest {

    private val usage = tagUsage(
        listOf(
            listOf("Dessert", "Baking"),
            listOf("dessert", "Desserts"),
            listOf("Dessert", "recipe"),
            listOf("Sweets", "Quick")
        )
    )

    @Test
    fun `tag usage groups spellings, counts recipes once and sorts by use`() {
        assertEquals(
            listOf(
                TagUsage("Dessert", 3),
                TagUsage("Baking", 1),
                TagUsage("Desserts", 1),
                TagUsage("Quick", 1),
                TagUsage("recipe", 1),
                TagUsage("Sweets", 1)
            ),
            usage
        )
        assertEquals(listOf(TagUsage("Soup", 1)), tagUsage(listOf(listOf(" Soup", "soup", " "))))
    }

    @Test
    fun `vocabulary is the most used tags, capped`() {
        assertEquals(listOf("Dessert", "Baking"), tagVocabulary(listOf(listOf("Dessert", "Baking"), listOf("dessert")), 2))
        assertEquals(listOf("Dessert"), tagVocabulary(listOf(listOf("Dessert", "Baking"), listOf("dessert")), 1))
        assertEquals(emptyList<String>(), tagVocabulary(emptyList()))
    }

    @Test
    fun `plans renames, merges and deletions with recipe counts`() {
        val changes = planTagChanges(
            usage,
            listOf(
                TagMapping("Desserts", "dessert"),
                TagMapping("Sweets", "Dessert"),
                TagMapping("recipe", null),
                TagMapping("Quick", "Quick & easy")
            )
        )
        assertEquals(
            listOf(
                TagChange("Desserts", "Dessert", 1, Kind.MERGE),
                TagChange("Sweets", "Dessert", 1, Kind.MERGE),
                TagChange("recipe", null, 1, Kind.DELETE),
                TagChange("Quick", "Quick & easy", 1, Kind.RENAME)
            ),
            changes
        )
    }

    @Test
    fun `drops unknown tags, no-ops, blank targets and repeated mappings`() {
        val changes = planTagChanges(
            usage,
            listOf(
                TagMapping("Not a tag", "Dessert"),
                TagMapping("Dessert", "Dessert"),
                TagMapping(" baking ", "  "),
                TagMapping("Baking", "Bakes")
            )
        )
        assertEquals(listOf(TagChange("Baking", "Bakes", 1, Kind.RENAME)), changes)
    }

    @Test
    fun `a change of case is a rename`() {
        assertEquals(
            listOf(TagChange("recipe", "Recipe", 1, Kind.RENAME)),
            planTagChanges(usage, listOf(TagMapping("recipe", "Recipe")))
        )
    }

    @Test
    fun `chains are followed to their end`() {
        val changes = planTagChanges(
            usage,
            listOf(
                TagMapping("Sweets", "Desserts"),
                TagMapping("Desserts", "Dessert"),
                TagMapping("Quick", "recipe"),
                TagMapping("recipe", null)
            )
        )
        assertEquals(
            listOf(
                TagChange("Sweets", "Dessert", 1, Kind.MERGE),
                TagChange("Desserts", "Dessert", 1, Kind.MERGE),
                TagChange("Quick", null, 1, Kind.DELETE),
                TagChange("recipe", null, 1, Kind.DELETE)
            ),
            changes
        )
    }

    @Test
    fun `a merge into a tag that is renamed uses the new name`() {
        val changes = planTagChanges(
            usage,
            listOf(TagMapping("Sweets", "dessert"), TagMapping("Dessert", "Desserts & sweets"))
        )
        assertEquals(
            listOf(
                TagChange("Sweets", "Desserts & sweets", 1, Kind.RENAME),
                TagChange("Dessert", "Desserts & sweets", 3, Kind.RENAME)
            ),
            changes
        )
    }

    @Test
    fun `a cycle merges into its most used tag`() {
        val changes = planTagChanges(
            usage,
            listOf(TagMapping("Dessert", "Desserts"), TagMapping("Desserts", "Sweets"), TagMapping("Sweets", "dessert"))
        )
        assertEquals(
            listOf(TagChange("Desserts", "Dessert", 1, Kind.MERGE), TagChange("Sweets", "Dessert", 1, Kind.MERGE)),
            changes
        )
    }

    @Test
    fun `applying only part of a chain still uses the chain's end`() {
        val changes = planTagChanges(usage, listOf(TagMapping("Sweets", "Desserts"), TagMapping("Desserts", "Dessert")))
        val sweetsOnly = changes.filter { it.from == "Sweets" }.associateBy { it.key }
        assertEquals(listOf("Dessert", "Desserts"), applyTagChanges(listOf("Sweets", "Desserts"), sweetsOnly))
    }

    @Test
    fun `applying renames, merges without duplicates and deletes`() {
        val changes = planTagChanges(
            usage,
            listOf(TagMapping("Desserts", "Dessert"), TagMapping("recipe", null), TagMapping("Quick", "Fast"))
        ).associateBy { it.key }

        assertEquals(listOf("dessert"), applyTagChanges(listOf("dessert", "Desserts"), changes))
        assertEquals(listOf("Dessert"), applyTagChanges(listOf("Desserts", "Dessert"), changes))
        assertEquals(listOf("Dessert"), applyTagChanges(listOf("Dessert", " recipe"), changes))
        assertEquals(listOf("Sweets", "Fast"), applyTagChanges(listOf("Sweets", "quick"), changes))
    }

    @Test
    fun `recipes without a changed tag are returned untouched`() {
        val tags = listOf("Baking", "baking ")
        val changes = planTagChanges(usage, listOf(TagMapping("recipe", null))).associateBy { it.key }
        assertSame(tags, applyTagChanges(tags, changes))
    }

    @Test
    fun `favorites follow renames and merges and drop deleted tags`() {
        val changes = listOf(
            TagChange("Desserts", "Dessert", 1, TagChange.Kind.MERGE),
            TagChange("dinner", "Supper", 2, TagChange.Kind.RENAME),
            TagChange("recipe", null, 3, TagChange.Kind.DELETE)
        ).associateBy { it.key }

        assertEquals(
            setOf("Dessert", "Supper", "Baking"),
            setOf("desserts", "Dessert", "Dinner", "Recipe", "Baking").withTagChanges(changes)
        )
    }
}
