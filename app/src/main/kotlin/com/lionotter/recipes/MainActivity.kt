package com.lionotter.recipes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.domain.model.ThemeMode
import com.lionotter.recipes.ui.navigation.NavGraph
import com.lionotter.recipes.ui.theme.LionOtterTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val sharedIntentViewModel: SharedIntentViewModel by viewModels()

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedUrl = extractSharedUrl(intent)
        val sharedFileUri = extractFileUri(intent)
        val recipeId = extractRecipeId(intent)

        if (sharedUrl != null) {
            lifecycleScope.launch {
                sharedIntentViewModel.onSharedUrlReceived(sharedUrl)
            }
        }
        if (sharedFileUri != null) {
            lifecycleScope.launch {
                sharedIntentViewModel.onSharedFileReceived(sharedFileUri)
            }
        }

        setContent {
            val themeMode by settingsDataStore.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.AUTO)

            LionOtterTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavGraph(
                        sharedIntentViewModel = sharedIntentViewModel,
                        initialSharedUrl = sharedUrl,
                        initialFileUri = sharedFileUri,
                        recipeId = recipeId
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val sharedUrl = extractSharedUrl(intent)
        if (sharedUrl != null) {
            lifecycleScope.launch {
                sharedIntentViewModel.onSharedUrlReceived(sharedUrl)
            }
        }
        val sharedFileUri = extractFileUri(intent)
        if (sharedFileUri != null) {
            lifecycleScope.launch {
                sharedIntentViewModel.onSharedFileReceived(sharedFileUri)
            }
        }
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        return when (intent?.action) {
            Intent.ACTION_SEND -> {
                // Only extract text URL if there's no file stream (to avoid treating file shares as URL shares)
                if (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) != null) null
                else intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            }
            else -> null
        }
    }

    private fun extractFileUri(intent: Intent?): Uri? {
        return when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
    }

    private fun extractRecipeId(intent: Intent?): String? {
        return intent?.getStringExtra("recipe_id")
    }
}
