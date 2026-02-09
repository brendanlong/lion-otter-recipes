package com.lionotter.recipes.ui.state

import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lionotter.recipes.di.ApplicationScope
import com.lionotter.recipes.worker.RecipeImportWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class InProgressRecipe(
    val id: String,
    val name: String
)

@Singleton
class InProgressRecipeManager @Inject constructor(
    private val workManager: WorkManager,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val _inProgressRecipes = MutableStateFlow<Map<String, InProgressRecipe>>(emptyMap())
    val inProgressRecipes: StateFlow<Map<String, InProgressRecipe>> = _inProgressRecipes.asStateFlow()

    init {
        observeImportWorkStatus()
    }

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

    /**
     * Observe WorkManager status for all recipe imports. This runs in the application scope
     * so it is always active regardless of which screens are visible, ensuring orphaned
     * in-progress entries are cleaned up when work completes.
     */
    private fun observeImportWorkStatus() {
        appScope.launch {
            workManager.getWorkInfosByTagFlow(RecipeImportWorker.TAG_RECIPE_IMPORT)
                .collect { workInfos ->
                    workInfos.forEach { workInfo ->
                        val importId = workInfo.progress.getString(RecipeImportWorker.KEY_IMPORT_ID)
                            ?: workInfo.outputData.getString(RecipeImportWorker.KEY_IMPORT_ID)

                        when (workInfo.state) {
                            WorkInfo.State.RUNNING -> {
                                val recipeName = workInfo.progress.getString(RecipeImportWorker.KEY_RECIPE_NAME)
                                if (recipeName != null && importId != null) {
                                    updateRecipeName(importId, recipeName)
                                }
                            }
                            WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                                if (importId != null) {
                                    removeInProgressRecipe(importId)
                                }
                            }
                            else -> {}
                        }
                    }
                }
        }
    }
}
