package com.lionotter.recipes.data.local

import androidx.room.TypeConverter
import com.lionotter.recipes.domain.RecipeError

/**
 * Stores a [RecipeError] as [RecipeError.encode]d text. Older versions stored an English message
 * in the same column; those read back as [RecipeError.Legacy].
 */
class RecipeErrorConverter {
    @TypeConverter
    fun fromError(error: RecipeError?): String? = error?.let(RecipeError::encode)

    @TypeConverter
    fun toError(value: String?): RecipeError? = value?.let { RecipeError.decode(it) ?: RecipeError.Legacy(it) }
}
