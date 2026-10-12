package com.lionotter.recipes.data.local

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.room.testing.MigrationTestHelper
import com.lionotter.recipes.di.DatabaseModule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Runs the Room migrations against the exported schemas in app/schemas and checks existing
 * rows survive. Add a test step here with every new migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecipeDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(schemaInstrumentation(), RecipeDatabase::class.java)

    @Test
    fun `migrates 10 to 11 to 12 keeping the import queue and debug data`() {
        helper.createDatabase(TEST_DB, 10).use { db ->
            db.execSQL(
                "INSERT INTO pending_imports (id, url, name, imageUrl, status, workManagerId, errorMessage, createdAt) " +
                    "VALUES ('import-1', 'https://example.com/pie', 'Pie', NULL, 'failed', 'work-1', 'boom', 1000)"
            )
            db.execSQL(
                "INSERT INTO import_debug (id, sourceUrl, originalHtml, cleanedContent, aiOutputJson, " +
                    "originalLength, cleanedLength, inputTokens, outputTokens, aiModel, thinkingEnabled, " +
                    "recipeId, recipeName, errorMessage, isError, durationMs, createdAt) " +
                    "VALUES ('debug-1', 'https://example.com/pie', '<html/>', 'pie', '{}', 7, 3, 10, 20, " +
                    "'model', 1, 'recipe-1', 'Pie', NULL, 0, 1234, 2000)"
            )
            db.execSQL(
                "INSERT INTO recipes (id, name, ingredientSectionsJson, instructionSectionsJson, equipmentJson, " +
                    "tagsJson, createdAt, updatedAt, isFavorite) VALUES ('r', 'Old', '[]', '[]', '[]', '[]', 0, 0, 0)"
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 11, true, RecipeDatabaseMigrations.MIGRATION_10_11).use { db ->
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('recipes', 'meal_plans')")
                .use { assertFalse("Firestore-era tables should be dropped", it.moveToFirst()) }
        }

        helper.runMigrationsAndValidate(TEST_DB, 12, true, RecipeDatabaseMigrations.MIGRATION_11_12).use { db ->
            db.query("SELECT batchMode, recipeName FROM import_debug WHERE id = 'debug-1'").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(0, it.getInt(0))
                assertEquals("Pie", it.getString(1))
            }
        }
    }

    @Test
    fun `app database opens a version 10 database`() = runTest {
        helper.createDatabase(TEST_DB, 10).use { db ->
            db.execSQL(
                "INSERT INTO pending_imports (id, url, name, imageUrl, status, workManagerId, errorMessage, createdAt) " +
                    "VALUES ('import-1', 'https://example.com/pie', 'Pie', NULL, 'pending', NULL, NULL, 1000)"
            )
            db.execSQL(
                "INSERT INTO pending_imports (id, url, name, imageUrl, status, workManagerId, errorMessage, createdAt) " +
                    "VALUES ('import-2', '', 'Paprika', NULL, 'waiting_for_batch', NULL, NULL, 2000)"
            )
        }

        val database = DatabaseModule.provideDatabase(RuntimeEnvironment.getApplication(), TEST_DB)
        try {
            val pending = database.pendingImportDao().getPendingImportById("import-1")
            assertNotNull(pending)
            assertEquals("Pie", pending?.name)
            assertEquals(PendingImportStatus.PENDING, pending?.status)
            assertEquals(
                PendingImportStatus.WAITING_FOR_BATCH,
                database.pendingImportDao().getPendingImportById("import-2")?.status
            )
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"

        /**
         * MigrationTestHelper reads `<database class>/<version>.json` from the test context's
         * assets. Robolectric unit tests have no separate test APK, so serve the files straight
         * from app/schemas (the unit test working directory is the app module).
         */
        fun schemaInstrumentation(): Instrumentation {
            val target = RuntimeEnvironment.getApplication()
            val schemaAssets = mockk<AssetManager> {
                every { open(any()) } answers { File(SCHEMA_DIR, firstArg<String>()).inputStream() }
            }
            val schemaContext = object : ContextWrapper(target) {
                override fun getAssets(): AssetManager = schemaAssets
            }
            return object : Instrumentation() {
                override fun getContext(): Context = schemaContext
                override fun getTargetContext(): Context = target
            }
        }

        private val SCHEMA_DIR = File("schemas")
    }
}
