package com.lionotter.recipes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.lionotter.recipes.ui.navigation.NavGraph
import com.lionotter.recipes.ui.theme.LionOtterTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val sharedIntentViewModel: SharedIntentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedUrl = extractSharedUrl(intent)
        val recipeId = extractRecipeId(intent)

        if (sharedUrl != null) {
            lifecycleScope.launch {
                sharedIntentViewModel.onSharedUrlReceived(sharedUrl)
            }
        }

        setContent {
            LionOtterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavGraph(
                        sharedIntentViewModel = sharedIntentViewModel,
                        initialSharedUrl = sharedUrl,
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
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        return when (intent?.action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            }
            else -> null
        }
    }

    private fun extractRecipeId(intent: Intent?): String? {
        return intent?.getStringExtra("recipe_id")
    }
}
