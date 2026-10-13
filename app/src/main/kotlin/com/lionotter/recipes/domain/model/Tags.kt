package com.lionotter.recipes.domain.model

/** Identifies a tag regardless of case and surrounding whitespace (" Dessert" == "dessert"). */
fun tagKey(tag: String): String = tag.trim().lowercase()

/** True if this recipe carries [tag], compared by [tagKey]. */
fun Recipe.hasTag(tag: String): Boolean {
    val key = tagKey(tag)
    return tags.any { tagKey(it) == key }
}

/** True if [query] is blank or appears (ignoring case) in the recipe's name or one of its tags. */
fun Recipe.matchesSearch(query: String): Boolean =
    query.isBlank() ||
        name.contains(query, ignoreCase = true) ||
        tags.any { it.contains(query, ignoreCase = true) }

/** A tag (in its most common spelling) and how many recipes carry it. */
data class TagUsage(val name: String, val recipeCount: Int)

/**
 * The tags in [tagLists] (one list per recipe), grouped by [tagKey], most used first. Each is
 * named by its most common spelling, preferring a capitalized one on ties (as the filter chips do).
 */
fun tagUsage(tagLists: Collection<List<String>>): List<TagUsage> {
    val recipeCounts = linkedMapOf<String, Int>()
    val spellings = mutableMapOf<String, MutableMap<String, Int>>()
    for (tags in tagLists) {
        for (tag in tags.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(::tagKey)) {
            val key = tagKey(tag)
            recipeCounts[key] = (recipeCounts[key] ?: 0) + 1
            val counts = spellings.getOrPut(key) { mutableMapOf() }
            counts[tag] = (counts[tag] ?: 0) + 1
        }
    }
    return recipeCounts
        .map { (key, count) ->
            val name = spellings.getValue(key).entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }
                        .thenByDescending { it.key.first().isUpperCase() }
                        .thenBy { it.key }
                )
                .first().key
            TagUsage(name, count)
        }
        .sortedWith(compareByDescending<TagUsage> { it.recipeCount }.thenBy { tagKey(it.name) })
}

/** How many of the user's tags the AI is told about when parsing a recipe. */
const val MAX_TAG_VOCABULARY = 100

/** The user's most used tags, for asking the AI to reuse them rather than invent new ones. */
fun tagVocabulary(tagLists: Collection<List<String>>, limit: Int = MAX_TAG_VOCABULARY): List<String> =
    tagUsage(tagLists).take(limit).map { it.name }
