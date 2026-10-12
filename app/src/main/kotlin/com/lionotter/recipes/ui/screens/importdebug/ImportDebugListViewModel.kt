package com.lionotter.recipes.ui.screens.importdebug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.local.ImportDebugDao
import com.lionotter.recipes.data.local.ImportDebugSummary
import com.lionotter.recipes.util.ErrorLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ImportDebugListViewModel @Inject constructor(
    importDebugDao: ImportDebugDao
) : ViewModel() {

    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /** Debug entry summaries (without the large content columns), or null while loading. */
    val debugEntries: StateFlow<List<ImportDebugSummary>?> = importDebugDao.getAllDebugSummaries()
        .catch { e ->
            ErrorLog.e(TAG, "Failed to load import debug entries", e)
            _loadFailed.value = true
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    companion object {
        private const val TAG = "ImportDebugListVM"
    }
}
