package com.lionotter.recipes.data.local

import androidx.room.ColumnInfo

data class RecipeIdAndName(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String
)
