package com.lionotter.recipes

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lionotter.recipes.data.remote.AuthService
import com.lionotter.recipes.data.remote.AuthState
import com.lionotter.recipes.data.remote.FirestoreService
import com.lionotter.recipes.domain.model.ThemeMode
import com.lionotter.recipes.domain.port.AppSettings
import com.lionotter.recipes.messages.resolve
import com.lionotter.recipes.messages.toUserMessage
import com.lionotter.recipes.notification.RecipeNotificationHelper
import com.lionotter.recipes.ui.components.CollectEvents
import com.lionotter.recipes.ui.components.LocalPageTurns
import com.lionotter.recipes.ui.components.PageTurns
import com.lionotter.recipes.ui.navigation.NavGraph
import com.lionotter.recipes.ui.theme.AppMotion
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
class RecipesActivity : ComponentActivity() {
    private val sharedIntentViewModel: SharedIntentViewModel by viewModels()

    @Inject
    lateinit var authService: AuthService

    @Inject
    lateinit var firestoreService: FirestoreService

    @Inject
    lateinit var settings: AppSettings

    private val motion by lazy { AppMotion(applicationContext) }
    private val pageTurns = PageTurns()
    private var volumeKeysScroll = false

    /** The volume button whose press turned a page: its repeats and release are ours too. */
    private var pagingKey: Int? = null

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
        if (savedInstanceState == null) {
            lifecycleScope.launch { matchLauncherIconToTheme() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.volumeKeysScroll.collect { volumeKeysScroll = it }
            }
        }

        val authResolvedAtStart = authService.authState.value !is AuthState.Loading

        // The window's recomposer, but with the app's animation speed (AppMotion) rather than
        // only the system's.
        val recomposer = window.decorView.createLifecycleAwareWindowRecomposer(motion, lifecycle)
        setContent(parent = recomposer) {
            val context = LocalContext.current
            val resources = LocalResources.current

            // Background sync failures, wherever the user is (queued while the app is stopped)
            CollectEvents(firestoreService.failures) { failure ->
                Toast.makeText(context, failure.toUserMessage().resolve(resources), Toast.LENGTH_SHORT).show()
            }

            // Nothing until the theme is read, so an e-ink screen doesn't flash the default theme.
            val themeMode = settings.themeMode
                .collectAsStateWithLifecycle(initialValue = null).value ?: return@setContent
            val darkTheme = themeMode.isDarkTheme()
            SideEffect { motion.enabled = themeMode != ThemeMode.EINK }

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
                CompositionLocalProvider(LocalPageTurns provides pageTurns) {
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
    }

    override fun onResume() {
        super.onResume()
        motion.refresh()
    }

    /**
     * On a fresh start from the launcher, shows the launcher icon that goes with the theme
     * ([matchLauncherIcon]). Only then: swapping hides the other entry, and Android closes the
     * tasks started from a hidden entry, so a theme picked in Settings changes the icon the next
     * time the app starts.
     */
    private suspend fun matchLauncherIconToTheme() {
        matchLauncherIcon(eink = settings.themeMode.first() == ThemeMode.EINK)
    }

    /** With the setting on, the volume buttons scroll the screen showing by a page. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val direction = pageDirection(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) {
            pagingKey = keyCode.takeIf { volumeKeysScroll && pageTurns.turn(direction) }
        }
        return pagingKey == keyCode || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (pageDirection(keyCode) == null || pagingKey != keyCode) {
            return super.onKeyUp(keyCode, event)
        }
        pagingKey = null
        return true
    }

    private fun pageDirection(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> 1
        KeyEvent.KEYCODE_VOLUME_UP -> -1
        else -> null
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
