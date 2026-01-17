package com.lionotter.recipes

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SharedIntentViewModel @Inject constructor() : ViewModel() {
    private val _sharedUrl = MutableSharedFlow<String?>(replay = 0)
    val sharedUrl: SharedFlow<String?> = _sharedUrl

    suspend fun onSharedUrlReceived(url: String?) {
        if (url != null) {
            _sharedUrl.emit(url)
        }
    }
}
