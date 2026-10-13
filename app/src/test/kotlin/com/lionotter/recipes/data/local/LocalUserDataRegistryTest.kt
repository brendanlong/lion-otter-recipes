package com.lionotter.recipes.data.local

import com.lionotter.recipes.data.image.LocalImageFiles
import com.lionotter.recipes.data.repository.ImportDebugRepository
import com.lionotter.recipes.di.LocalUserDataModule
import com.lionotter.recipes.imports.ImportQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.reflect.KClass

/**
 * Keeps the per-user local data registry (`LocalUserDataModule`) complete: every main source file
 * that persists data on the device must be listed in [COVERAGE], either as cleared by a registered
 * [PerUserLocalData] or with the reason it isn't user data. A new store therefore fails this test
 * until it is registered (or deliberately left out). See DESIGN.md "Authentication and Account
 * Lifecycle".
 */
class LocalUserDataRegistryTest {

    private sealed interface Coverage

    /** Cleared on sign-out / account deletion by [store], which must be bound in `LocalUserDataModule`. */
    private data class ClearedBy(val store: KClass<out PerUserLocalData>) : Coverage

    /** Deliberately not in the registry. */
    private data class NotRegistered(val reason: String) : Coverage

    @Test
    fun `every file that persists data is accounted for`() {
        val persisting = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> PERSISTENCE.containsMatchIn(file.readText()) }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .toSet()
        check(persisting.size > 5) { "Scanner found too few files under ${sourceRoot.absolutePath}" }

        val unlisted = persisting - COVERAGE.keys
        val stale = COVERAGE.keys - persisting
        val message = buildString {
            if (unlisted.isNotEmpty()) {
                appendLine("These files persist data but aren't in LocalUserDataRegistryTest.COVERAGE:")
                unlisted.sorted().forEach { appendLine("  $it") }
                appendLine(
                    "If they hold a user's data, implement PerUserLocalData, bind it in LocalUserDataModule " +
                        "and list it as ClearedBy; otherwise list it as NotRegistered with the reason."
                )
            }
            if (stale.isNotEmpty()) {
                appendLine("These COVERAGE entries no longer persist anything; remove them:")
                stale.sorted().forEach { appendLine("  $it") }
            }
        }
        if (message.isNotEmpty()) fail(message)
    }

    @Test
    fun `the registry binds exactly the stores that clear user data`() {
        val bound = LocalUserDataModule::class.java.declaredMethods
            .filter { it.returnType == PerUserLocalData::class.java }
            .map { it.parameterTypes.single().kotlin }
            .toSet()
        val expected = COVERAGE.values.filterIsInstance<ClearedBy>().map { it.store }.toSet()

        assertEquals(expected, bound)
    }

    private val sourceRoot = File("src/main/kotlin/com/lionotter/recipes")

    private companion object {
        /** APIs that keep data on the device beyond the process. */
        val PERSISTENCE = Regex(
            """preferencesDataStore\(|dataStore\(|getSharedPreferences\(|withSharedPref\(|@Dao\b|@Database\b|""" +
                """\bfilesDir\b|\bcacheDir\b|noBackupFilesDir|getDatabasePath|openFileOutput|\bdataDir\b|persistentCacheSettings"""
        )

        val COVERAGE: Map<String, Coverage> = mapOf(
            "data/image/LocalImageFiles.kt" to ClearedBy(LocalImageFiles::class),
            "data/local/DataStoreGroceryListStateStore.kt" to ClearedBy(DataStoreGroceryListStateStore::class),
            "data/local/PendingImportDao.kt" to ClearedBy(ImportQueue::class),
            // The import directories; one-off work inputs are deleted by the work using them.
            "worker/ImportInputStore.kt" to ClearedBy(ImportQueue::class),
            "data/local/ImportDebugDao.kt" to ClearedBy(ImportDebugRepository::class),
            // The API key; the other settings are device preferences and are kept.
            "data/local/SettingsDataStore.kt" to ClearedBy(SettingsDataStore::class),
            "data/local/RecipeDatabase.kt" to NotRegistered("only holds the DAOs' tables, which are cleared through them"),
            "data/local/TinkEncryption.kt" to NotRegistered("the device's encryption keyset, not user data"),
            "data/remote/AuthService.kt" to NotRegistered("the guest UID, which AuthSession.signOutAndForgetGuest forgets"),
            "data/remote/FirestoreService.kt" to
                NotRegistered("Firestore's cache, which AccountLifecycle clears through FirestoreCache"),
            "ui/screens/importdebug/ImportDebugDetailViewModel.kt" to
                NotRegistered("temporary files handed to the share sheet, in cacheDir"),
            "ui/screens/recipedetail/RecipeDetailViewModel.kt" to
                NotRegistered("temporary files handed to the share sheet, in cacheDir"),
        )
    }
}
