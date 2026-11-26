package app.flytrack.feature.flights

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Entry point of the flight detail screen; [flightId] is `flights.id`. */
@Composable
fun FlightDetailRoute(
    flightId: String,
    onBack: () -> Unit,
) {
    val viewModel = hiltViewModel<FlightDetailViewModel, FlightDetailViewModel.Factory>(key = flightId) { factory ->
        factory.create(flightId)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.isClosed) {
        if (state.isClosed) onBack()
    }

    FlightDetailScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onAlertChange = viewModel::setAlertEnabled,
        onTrack = viewModel::track,
        onArchiveChange = viewModel::setArchived,
        onUntrack = viewModel::untrack,
        onEditBooking = viewModel::openBookingEditor,
        onPnrChange = viewModel::onPnrChange,
        onSeatChange = viewModel::onSeatChange,
        onSaveBooking = viewModel::saveBooking,
        onDismissBookingEditor = viewModel::dismissBookingEditor,
        onMessageShown = viewModel::messageShown,
        onCabinChange = viewModel::onCabinChange,
        onShare = viewModel::share,
        onShareHandled = viewModel::shareHandled,
    )
}
