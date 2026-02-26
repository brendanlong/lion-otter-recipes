package com.lionotter.recipes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.AuthState
import com.lionotter.recipes.data.remote.FirestoreService
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
    lateinit var authService: AuthService

    @Inject
    lateinit var firestoreService: FirestoreService

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Ensure user has a session (Google or local guest) on startup
        lifecycleScope.launch {
            authService.ensureSignedIn()
        }

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
            val context = LocalContext.current

            // Observe Firestore errors and show Toasts
            LaunchedEffect(Unit) {
                firestoreService.errors.collect { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }

            val themeMode by settingsDataStore.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.AUTO)
            val authState by authService.authState.collectAsState()

            LionOtterTheme(themeMode = themeMode) {
                when (authState) {
                    is AuthState.Loading -> {
                        // Show loading indicator while auth initializes
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    is AuthState.Guest, is AuthState.Google -> {
                        // Show main app for both guest and Google users
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
                if (IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) != null) null
                else intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            }
            else -> null
        }
    }

    private fun extractFileUri(intent: Intent?): Uri? {
        return when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            }
            else -> null
        }
    }

    private fun extractRecipeId(intent: Intent?): String? {
        return intent?.getStringExtra("recipe_id")
    }
}
