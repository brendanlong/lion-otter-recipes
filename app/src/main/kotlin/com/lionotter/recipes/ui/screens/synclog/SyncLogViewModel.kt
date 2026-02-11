package com.lionotter.recipes.ui.screens.synclog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.local.SyncLogEntity
import com.lionotter.recipes.data.repository.SyncLogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncLogViewModel @Inject constructor(
    private val repository: SyncLogRepository
) : ViewModel() {

    val logs: StateFlow<List<SyncLogEntity>> = repository.getAllLogs()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearAllLogs()
        }
    }
}
