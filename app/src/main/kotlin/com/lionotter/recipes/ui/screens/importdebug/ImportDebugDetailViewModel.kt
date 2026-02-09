package com.lionotter.recipes.ui.screens.importdebug

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lionotter.recipes.data.local.ImportDebugEntity
import com.lionotter.recipes.data.repository.ImportDebugRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ImportDebugDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val importDebugRepository: ImportDebugRepository
) : ViewModel() {

    private val entryId: String = checkNotNull(savedStateHandle["debugEntryId"])

    private val _entry = MutableStateFlow<ImportDebugEntity?>(null)
    val entry: StateFlow<ImportDebugEntity?> = _entry.asStateFlow()

    init {
        viewModelScope.launch {
            _entry.value = importDebugRepository.getDebugEntryById(entryId)
        }
    }
}
