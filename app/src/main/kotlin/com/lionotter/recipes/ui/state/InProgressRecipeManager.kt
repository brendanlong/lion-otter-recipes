package com.lionotter.recipes.ui.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class InProgressRecipe(
    val id: String,
    val name: String
)

@Singleton
class InProgressRecipeManager @Inject constructor() {
    private val _inProgressRecipes = MutableStateFlow<Map<String, InProgressRecipe>>(emptyMap())
    val inProgressRecipes: StateFlow<Map<String, InProgressRecipe>> = _inProgressRecipes.asStateFlow()

    fun addInProgressRecipe(id: String, name: String) {
        val updated = _inProgressRecipes.value.toMutableMap()
        updated[id] = InProgressRecipe(id = id, name = name)
        _inProgressRecipes.value = updated
    }

    fun updateRecipeName(id: String, name: String) {
        val current = _inProgressRecipes.value[id]
        if (current != null) {
            val updated = _inProgressRecipes.value.toMutableMap()
            updated[id] = current.copy(name = name)
            _inProgressRecipes.value = updated
        }
    }

    fun removeInProgressRecipe(id: String) {
        if (!_inProgressRecipes.value.containsKey(id)) return
        val updated = _inProgressRecipes.value.toMutableMap()
        updated.remove(id)
        _inProgressRecipes.value = updated
    }

    fun clear() {
        _inProgressRecipes.value = emptyMap()
    }
}
