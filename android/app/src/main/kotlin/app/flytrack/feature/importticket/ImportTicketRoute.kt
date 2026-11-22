package app.flytrack.feature.importticket

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
 * STUB (foundation): the importer feature replaces this file.
 * Entry point of the ticket import screen. Content shared from other apps is waiting in
 * `SharedContentHolder` (inject it into the ViewModel and call `consume()`).
 *
 * @param onParsed called with what was found on the ticket; the app then opens the add-flight
 *   screen pre-filled. `flight` is flight number text ("6E 2341"), `date` an ISO local date
 *   ("2026-11-25"), `pnr` the booking reference; each may be null when not found.
 */
@Composable
fun ImportTicketRoute(
    onParsed: (flight: String?, date: String?, pnr: String?) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { FlyTrackTopBar(title = stringResource(R.string.stub_import_title), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyState(title = stringResource(R.string.stub_import_title), message = stringResource(R.string.stub_message))
        }
    }
}
