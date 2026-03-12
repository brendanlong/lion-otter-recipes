package com.lionotter.recipes

import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * A shared file with its detected type.
 */
data class SharedFile(
    val uri: Uri,
    /** Non-null for text files (.md, .txt, .html); null for ZIP archives */
    val textFileType: String?
)

@HiltViewModel
class SharedIntentViewModel @Inject constructor() : ViewModel() {
    private val _sharedUrl = MutableSharedFlow<String?>(replay = 0)
    val sharedUrl: SharedFlow<String?> = _sharedUrl

    private val _sharedFile = MutableSharedFlow<SharedFile>(replay = 0)
    val sharedFile: SharedFlow<SharedFile> = _sharedFile

    suspend fun onSharedUrlReceived(url: String?) {
        if (url != null) {
            _sharedUrl.emit(url)
        }
    }

    suspend fun onSharedFileReceived(uri: Uri, textFileType: String? = null) {
        _sharedFile.emit(SharedFile(uri, textFileType))
    }
}
