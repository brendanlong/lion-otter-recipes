package com.lionotter.recipes.architecture

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Enforces the layer rules from DESIGN.md ("Dependency Rule" / "Guardrails") by scanning the
 * imports (and fully qualified references) of every main source file.
 *
 * [KNOWN_VIOLATIONS] lists today's violations so the test passes; refactors should shrink it.
 * The test also fails when an entry is no longer needed, so the list can only get shorter.
 */
class LayerDependencyTest {

    private data class Rule(val layer: String, val forbidden: List<String>, val allowed: List<String> = emptyList())

    private data class Violation(val file: String, val reference: String) {
        override fun toString() = "\"$file\" to \"$reference\""
    }

    private val rules = listOf(
        // The domain is plain Kotlin: no Android, SDK or data-layer types.
        Rule(
            layer = "domain",
            forbidden = listOf(ANYTHING),
            allowed = listOf(
                "kotlin.",
                "kotlinx.",
                "java.",
                "javax.inject.",
                "com.lionotter.recipes.domain.",
                // Plain-Kotlin helpers shared with the other layers (checked by the "util" rule).
                "com.lionotter.recipes.util.",
                // Pure-JVM linear programming solver used by GetTagsUseCase.
                "org.ojalgo.",
            ),
        ),
        Rule(
            layer = "util",
            forbidden = listOf(ANYTHING),
            allowed = listOf("kotlin.", "kotlinx.", "java.", "javax.inject.", "com.lionotter.recipes.util."),
        ),
        Rule(
            layer = "ui",
            forbidden = listOf("androidx.work.", "com.lionotter.recipes.data.local."),
        ),
        Rule(
            layer = "data",
            forbidden = listOf("com.lionotter.recipes.ui.", "com.lionotter.recipes.worker.", "com.lionotter.recipes.imports."),
        ),
        Rule(
            layer = "imports",
            forbidden = listOf("com.lionotter.recipes.ui."),
        ),
        Rule(
            layer = "worker",
            forbidden = listOf("com.lionotter.recipes.ui.", "com.lionotter.recipes.imports."),
        ),
    )

    @Test
    fun `layers only depend on what they are allowed to`() {
        val found = findViolations()
        val known = KNOWN_VIOLATIONS.map { (file, ref) -> Violation(file, ref) }.toSet()

        val newViolations = found - known
        val stale = known - found

        val message = buildString {
            if (newViolations.isNotEmpty()) {
                appendLine("New layer violations (see DESIGN.md \"Guardrails\"):")
                newViolations.sortedBy { it.toString() }.forEach { appendLine("  $it") }
                appendLine(
                    "Move the dependency behind a domain interface/port (or into the right layer). " +
                        "Only if that is out of scope, add the lines above to KNOWN_VIOLATIONS in " +
                        "LayerDependencyTest with a reason in the PR."
                )
            }
            if (stale.isNotEmpty()) {
                appendLine("These KNOWN_VIOLATIONS entries are fixed; delete them from LayerDependencyTest:")
                stale.sortedBy { it.toString() }.forEach { appendLine("  $it") }
            }
        }
        if (message.isNotEmpty()) fail(message)
    }

    @Test
    fun `scanner sees the source tree`() {
        assertTrue("No sources found under ${sourceRoot.absolutePath}", sourceFiles().count() > 50)
    }

    private val sourceRoot = File("src/main/kotlin/com/lionotter/recipes")

    private fun sourceFiles() = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }

    private fun findViolations(): Set<Violation> {
        val violations = mutableSetOf<Violation>()
        for (file in sourceFiles()) {
            val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath
            val rule = rules.firstOrNull { relative.startsWith("${it.layer}/") } ?: continue
            for (reference in references(file)) {
                val isForbidden = rule.forbidden.any { reference.startsWith(it) } &&
                    rule.allowed.none { reference.startsWith(it) }
                if (isForbidden) violations += Violation(relative, reference)
            }
        }
        return violations
    }

    /** Imported names plus fully qualified names used in code (outside comments). */
    private fun references(file: File): Set<String> {
        val refs = mutableSetOf<String>()
        for (rawLine in file.readLines()) {
            val line = rawLine.trim()
            val import = IMPORT.matchEntire(line)
            when {
                import != null -> refs += import.groupValues[1]
                line.startsWith("package ") -> Unit
                line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") -> Unit
                else -> QUALIFIED.findAll(line.substringBefore("//")).forEach { refs += it.value }
            }
        }
        return refs
    }

    companion object {
        /** Matches every reference; use with an `allowed` list. */
        private const val ANYTHING = ""

        private val IMPORT = Regex("""import\s+([\w.]+(?:\.\*)?)(\s+as\s+\w+)?""")
        private val QUALIFIED =
            Regex("""(?<![\w.])(android|androidx|com\.anthropic|com\.google\.firebase|com\.lionotter\.recipes)(\.\w+)+""")

        /** Violations that exist today. Remove entries as the code is fixed; don't add new ones. */
        private val KNOWN_VIOLATIONS = listOf(
            // ui -> data.local
            "ui/screens/importdebug/ImportDebugDetailScreen.kt" to "com.lionotter.recipes.data.local.ImportDebugSummary",
            "ui/screens/importdebug/ImportDebugDetailViewModel.kt" to "com.lionotter.recipes.data.local.ImportDebugDao",
            "ui/screens/importdebug/ImportDebugDetailViewModel.kt" to "com.lionotter.recipes.data.local.ImportDebugSummary",
            "ui/screens/importdebug/ImportDebugListScreen.kt" to "com.lionotter.recipes.data.local.ImportDebugSummary",
            "ui/screens/importdebug/ImportDebugListViewModel.kt" to "com.lionotter.recipes.data.local.ImportDebugDao",
            "ui/screens/importdebug/ImportDebugListViewModel.kt" to "com.lionotter.recipes.data.local.ImportDebugSummary",
        )
    }
}
