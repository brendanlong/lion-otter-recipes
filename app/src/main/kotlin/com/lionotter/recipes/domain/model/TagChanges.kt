package com.lionotter.recipes.domain.model

/** One suggested or requested change to a tag: rename it to [to], or delete it if [to] is null. */
data class TagMapping(val from: String, val to: String?)

/** A change to one of the user's tags, checked against the tags in use. */
data class TagChange(
    /** The tag as the user knows it (its most common spelling). */
    val from: String,
    /** The new name, or null to remove the tag from its recipes. */
    val to: String?,
    /** How many recipes carry [from]. */
    val recipeCount: Int,
    val kind: Kind
) {
    enum class Kind {
        /** The tag gets a name no other tag has (including a change of case). */
        RENAME,

        /** The tag is folded into another existing tag. */
        MERGE,
        DELETE
    }

    val key: String get() = tagKey(from)
}

/**
 * Turns [mappings] (e.g. the AI's suggestions) into changes to the tags in [usage]. Mappings for
 * tags nobody uses, and ones that change nothing, are dropped; a tag mapped twice keeps its first
 * mapping. Chains are followed (a → b, b → c becomes a → c and b → c; a chain ending in a
 * deletion deletes), so applying any subset of the changes never leaves a tag that another
 * change was meant to replace.
 */
fun planTagChanges(usage: List<TagUsage>, mappings: List<TagMapping>): List<TagChange> {
    val usageByKey = usage.associateBy { tagKey(it.name) }
    val targets = linkedMapOf<String, String?>()
    for (mapping in mappings) {
        val key = tagKey(mapping.from)
        if (key !in usageByKey || key in targets) continue
        targets[key] = mapping.to?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun resolve(start: String?): String? {
        var target = start
        val seen = mutableSetOf<String>()
        while (target != null) {
            val key = tagKey(target)
            if (key !in targets || !seen.add(key)) break
            val next = targets[key]
            // A tag mapped to (a spelling of) itself ends the chain there
            if (next != null && tagKey(next) == key) return next
            target = next
        }
        return target
    }

    return targets.mapNotNull { (key, to) ->
        val tag = usageByKey.getValue(key)
        val resolved = resolve(to)
        val kind = when {
            resolved == null -> TagChange.Kind.DELETE
            resolved == tag.name -> return@mapNotNull null
            tagKey(resolved) != key && tagKey(resolved) in usageByKey -> TagChange.Kind.MERGE
            else -> TagChange.Kind.RENAME
        }
        // A merge uses the target tag's current spelling, unless that tag is renamed too
        val name = resolved?.let { r ->
            if (kind == TagChange.Kind.MERGE && tagKey(r) !in targets) usageByKey.getValue(tagKey(r)).name else r
        }
        TagChange(from = tag.name, to = name, recipeCount = tag.recipeCount, kind = kind)
    }
}

/**
 * [tags] with [changes] (keyed by [TagChange.key]) applied: renamed and merged tags take their
 * new name, deleted ones are dropped, and duplicates (by [tagKey]) that result are removed.
 * Tags without a change are kept exactly as they are; a recipe none of the changes touch is
 * returned as is.
 */
fun applyTagChanges(tags: List<String>, changes: Map<String, TagChange>): List<String> {
    if (tags.none { tagKey(it) in changes }) return tags
    val seen = mutableSetOf<String>()
    return tags.mapNotNull { tag ->
        val change = changes[tagKey(tag)]
        val newTag = if (change == null) tag else change.to ?: return@mapNotNull null
        newTag.takeIf { tagKey(it).isNotEmpty() && seen.add(tagKey(it)) }
    }
}
