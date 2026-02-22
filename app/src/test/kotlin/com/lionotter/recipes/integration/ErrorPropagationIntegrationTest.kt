package com.lionotter.recipes.integration

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Integration tests verifying error handling and edge cases in the real
 * repository layer backed by the real Firestore SDK.
 *
 * With the migration from Room to Firestore, malformed JSON tests are no longer
 * applicable (Firestore uses DTOs, not raw JSON). These tests focus on the
 * behaviors that are still relevant with the Firestore-backed repository.
 */
class ErrorPropagationIntegrationTest : FirestoreIntegrationTest() {

    // -----------------------------------------------------------------------
    // Non-existent recipe handling
    // -----------------------------------------------------------------------

    @Test
    fun `get non-existent recipe returns null`() {
        val result = runSuspending { recipeRepository.getRecipeByIdOnce("non-existent-id") }
        assertEquals(null, result)
    }

}
