package com.lionotter.recipes

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.lionotter.recipes.data.local.SettingsDataStore
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.AuthState
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.domain.model.ThemeMode
import com.lionotter.recipes.notification.RecipeNotificationHelper
import com.lionotter.recipes.ui.navigation.NavGraph
import com.lionotter.recipes.ui.theme.LionOtterTheme
import com.lionotter.recipes.ui.theme.isDarkTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Navigation bar scrims matching androidx.activity's defaults for button navigation. */
private val LightNavigationBarScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DarkNavigationBarScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

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

        // Only handle the launch intent on a fresh start. On recreation (rotation, process
        // death restore) it was already handled, and a launch from Recents redelivers the
        // original share intent, which would otherwise import the same thing again.
        val launchedFromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !launchedFromHistory) {
            handleIntent(intent)
        }

        val authResolvedAtStart = authService.authState.value !is AuthState.Loading

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
            val darkTheme = themeMode.isDarkTheme()

            // Match system bar icon colors to the app theme, which may differ from the system's.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        Color.TRANSPARENT,
                        Color.TRANSPARENT
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        LightNavigationBarScrim,
                        DarkNavigationBarScrim
                    ) { darkTheme }
                )
                onDispose {}
            }

            // Only show the loading screen while auth is first resolved. Later transitions
            // through Loading (sign-out, account deletion) keep the navigation graph alive
            // so the back stack and screen state aren't thrown away.
            val authResolved by produceState(initialValue = authResolvedAtStart) {
                authService.authState.first { it !is AuthState.Loading }
                value = true
            }

            LionOtterTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (authResolved) {
                        NavGraph(sharedIntentViewModel = sharedIntentViewModel)
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        intentToEvent(intent)?.let(sharedIntentViewModel::onEvent)
    }

    private fun intentToEvent(intent: Intent): AppIntentEvent? {
        intent.getStringExtra(RecipeNotificationHelper.EXTRA_RECIPE_ID)?.let {
            return AppIntentEvent.OpenRecipe(it)
        }
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { AppIntentEvent.ImportFile(it) }
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val isTextShare = intent.type?.startsWith("text/") == true
                when {
                    stream != null && !isTextShare -> AppIntentEvent.ImportFile(stream)
                    !text.isNullOrBlank() ->
                        extractFirstUrl(text)?.let { AppIntentEvent.ImportUrl(it) }
                            ?: AppIntentEvent.NoUrlInSharedText
                    isTextShare -> AppIntentEvent.NoUrlInSharedText
                    else -> null
                }
            }
            else -> null
        }
    }
}
