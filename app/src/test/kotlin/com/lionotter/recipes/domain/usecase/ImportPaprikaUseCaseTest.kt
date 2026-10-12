package com.lionotter.recipes.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ImportPaprikaUseCaseTest {

    @Test
    fun `batch recipe id is stable for the same import and custom id`() {
        assertEquals(
            ImportPaprikaUseCase.batchRecipeId("import-1", "recipe-0"),
            ImportPaprikaUseCase.batchRecipeId("import-1", "recipe-0")
        )
    }

    @Test
    fun `batch recipe id differs across recipes and imports`() {
        val id = ImportPaprikaUseCase.batchRecipeId("import-1", "recipe-0")
        assertNotEquals(id, ImportPaprikaUseCase.batchRecipeId("import-1", "recipe-1"))
        assertNotEquals(id, ImportPaprikaUseCase.batchRecipeId("import-2", "recipe-0"))
    }
}
