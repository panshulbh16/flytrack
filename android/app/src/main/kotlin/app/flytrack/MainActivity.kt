package app.flytrack

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import app.flytrack.core.push.PushContract
import app.flytrack.data.repo.Appearance
import app.flytrack.data.repo.SessionState
import app.flytrack.data.repo.SettingsStore
import app.flytrack.feature.gmailimport.GmailAppOpenCheck
import app.flytrack.ui.components.BackendNotConfiguredBanner
import app.flytrack.ui.nav.EXTRA_GMAIL_SUGGESTION_ID
import app.flytrack.ui.nav.FlyTrackNavHost
import app.flytrack.ui.nav.IncomingIntent
import app.flytrack.ui.nav.NavControllerNavigator
import app.flytrack.ui.theme.FlyTrackTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** The single activity: hosts the NavHost and turns incoming intents (push taps, shares) into navigation. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var settings: SettingsStore

    // Lazy: a build with Gmail import off never builds the scanner or its HTTP client.
    @Inject lateinit var gmailAppOpenCheck: dagger.Lazy<GmailAppOpenCheck>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh start handles the launch intent; after rotation it was already handled.
        if (savedInstanceState == null) viewModel.onIntent(intent.toIncomingIntent())
        setContent {
            val appearance by settings.appearance.collectAsStateWithLifecycle(initialValue = Appearance.SYSTEM)
            val darkTheme = when (appearance) {
                Appearance.SYSTEM -> isSystemInDarkTheme()
                Appearance.LIGHT -> false
                Appearance.DARK -> true
            }
            // System bar icons follow the app's appearance choice, not the phone's.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_NAV_SCRIM, DARK_NAV_SCRIM) { darkTheme },
                )
                onDispose {}
            }
            FlyTrackTheme(darkTheme = darkTheme) {
                FlyTrackShell(viewModel)
            }
        }
    }

    private companion object {
        // The same scrims androidx's enableEdgeToEdge() uses by default for 3-button navigation.
        val LIGHT_NAV_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_NAV_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.onIntent(intent.toIncomingIntent())
    }

    // Launch and every return to the foreground; GmailAppOpenCheck throttles itself to once an hour.
    override fun onStart() {
        super.onStart()
        if (BuildConfig.GMAIL_IMPORT_ENABLED) gmailAppOpenCheck.get().onForeground()
    }

    private fun Intent.toIncomingIntent(): IncomingIntent {
        val streams: List<Uri> = when (action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        return IncomingIntent(
            action = action,
            mimeType = type,
            flightId = getStringExtra(PushContract.KEY_FLIGHT_ID),
            text = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            streamUris = streams.map(Uri::toString),
            gmailSuggestionId = getStringExtra(EXTRA_GMAIL_SUGGESTION_ID),
        )
    }
}

@Composable
private fun FlyTrackShell(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val navigator = remember(navController) { NavControllerNavigator(navController) }
    val session by viewModel.session.collectAsStateWithLifecycle()

    LaunchedEffect(navigator) {
        viewModel.destinations.collect { destination ->
            when (destination) {
                is ShellDestination.Flight -> navigator.openFlight(destination.flightId)
                ShellDestination.ImportTicket -> navigator.openImportTicket()
                is ShellDestination.AddFromGmail -> navigator.openAddFlightFromGmail(destination.suggestion)
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (session == SessionState.NotConfigured) {
            Column {
                BackendNotConfiguredBanner(Modifier.windowInsetsPadding(WindowInsets.statusBars))
                // The banner already sits below the status bar, so the screens must not add that inset again.
                Box(Modifier.consumeWindowInsets(WindowInsets.statusBars)) {
                    FlyTrackNavHost(navController = navController, navigator = navigator)
                }
            }
        } else {
            FlyTrackNavHost(navController = navController, navigator = navigator)
        }
    }
}
