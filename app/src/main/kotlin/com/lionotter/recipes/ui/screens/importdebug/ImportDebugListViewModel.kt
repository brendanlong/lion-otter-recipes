package com.lionotter.recipes.ui.screens.importdebug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.local.ImportDebugEntity
import com.lionotter.recipes.data.repository.ImportDebugRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ImportDebugListViewModel @Inject constructor(
    importDebugRepository: ImportDebugRepository
) : ViewModel() {

    val debugEntries: StateFlow<List<ImportDebugEntity>> = importDebugRepository.getAllDebugEntries()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
}
