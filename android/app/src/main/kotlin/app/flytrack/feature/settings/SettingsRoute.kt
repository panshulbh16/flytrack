package app.flytrack.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.flytrack.R
import app.flytrack.ui.components.EmptyState
import app.flytrack.ui.components.FlyTrackTopBar

/**
 * STUB (foundation): the push/settings feature replaces this file.
 * Entry point of the settings screen (account, notifications, privacy opt-ins).
 */
@Composable
fun SettingsRoute(onBack: () -> Unit) {
    Scaffold(topBar = { FlyTrackTopBar(title = stringResource(R.string.stub_settings_title), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyState(title = stringResource(R.string.stub_settings_title), message = stringResource(R.string.stub_message))
        }
    }
}
