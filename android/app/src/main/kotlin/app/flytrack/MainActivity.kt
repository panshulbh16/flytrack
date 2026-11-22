package app.flytrack

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import app.flytrack.core.push.PushContract
import app.flytrack.data.repo.SessionState
import app.flytrack.ui.components.BackendNotConfiguredBanner
import app.flytrack.ui.nav.FlyTrackNavHost
import app.flytrack.ui.nav.IncomingIntent
import app.flytrack.ui.nav.NavControllerNavigator
import app.flytrack.ui.theme.FlyTrackTheme
import dagger.hilt.android.AndroidEntryPoint

/** The single activity: hosts the NavHost and turns incoming intents (push taps, shares) into navigation. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh start handles the launch intent; after rotation it was already handled.
        if (savedInstanceState == null) viewModel.onIntent(intent.toIncomingIntent())
        setContent {
            FlyTrackTheme {
                FlyTrackShell(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.onIntent(intent.toIncomingIntent())
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
