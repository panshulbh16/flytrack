package app.flytrack.feature.importticket

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.flytrack.R
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.LoadingState
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.SecondaryButton
import kotlinx.datetime.LocalDate

/**
 * Entry point of the ticket import screen.
 *
 * @param onParsed flight number text, ISO date, booking reference and how the ticket arrived
 * (`pdf`, `screenshot` or `share`). Each may be null. The app opens add-flight with those values.
 */
@Composable
fun ImportTicketRoute(
    onParsed: (flight: String?, date: String?, pnr: String?, source: String?) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: ImportTicketViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnParsed by rememberUpdatedState(onParsed)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    is ImportEvent.Parsed -> currentOnParsed(event.flight, event.date, event.pnr, event.source)
                }
            }
        }
    }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onPicked(listOf(uri.toString()), null)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onPicked(listOf(uri.toString()), "image/*")
    }

    ImportTicketScreen(
        state = state,
        onBack = onBack,
        onChooseFile = { openDocument.launch(arrayOf("application/pdf", "text/plain", "image/*")) },
        onChooseImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onSelect = viewModel::onSelect,
        onCloudConsent = viewModel::onCloudConsent,
        onTryCloud = viewModel::onTryCloud,
        onContinue = viewModel::onContinue,
        onTypeInstead = { currentOnParsed(null, null, state.pnr, state.source.wire) },
    )
}

@Composable
fun ImportTicketScreen(
    state: ImportUiState,
    onBack: () -> Unit,
    onChooseFile: () -> Unit,
    onChooseImage: () -> Unit,
    onSelect: (Int) -> Unit,
    onCloudConsent: (Boolean) -> Unit,
    onTryCloud: () -> Unit,
    onContinue: () -> Unit,
    onTypeInstead: () -> Unit,
) {
    Scaffold(topBar = { FlyTrackTopBar(title = stringResource(R.string.import_title), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            when (state.phase) {
                ImportPhase.Reading -> LoadingState(label = stringResource(R.string.import_reading))
                ImportPhase.Idle -> IdleContent(onChooseFile, onChooseImage)
                ImportPhase.Choose -> ChooseContent(state, onSelect, onContinue)
                ImportPhase.Empty -> EmptyContent(state, onChooseFile, onChooseImage, onCloudConsent, onTryCloud, onTypeInstead)
            }
            state.notice?.let { notice ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = noticeText(notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun IdleContent(onChooseFile: () -> Unit, onChooseImage: () -> Unit) {
    Text(
        text = stringResource(R.string.import_intro),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(20.dp))
    PrimaryButton(
        text = stringResource(R.string.import_action_file),
        onClick = onChooseFile,
        icon = painterResource(R.drawable.ic_document),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    SecondaryButton(
        text = stringResource(R.string.import_action_image),
        onClick = onChooseImage,
        icon = painterResource(R.drawable.ic_image),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ChooseContent(state: ImportUiState, onSelect: (Int) -> Unit, onContinue: () -> Unit) {
    if (state.flights.size > 1) {
        Text(
            text = stringResource(R.string.import_choose),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
    }
    state.pnr?.let { pnr ->
        Text(
            text = stringResource(R.string.import_pnr, pnr),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
    state.flights.forEachIndexed { index, flight ->
        FlightChoice(flight, selected = index == state.selected, onClick = { onSelect(index) })
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton(
        text = stringResource(R.string.import_action_use),
        onClick = onContinue,
        enabled = state.selectedFlight != null && !state.busy,
        loading = state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FlightChoice(flight: ParsedFlight, selected: Boolean, onClick: () -> Unit) {
    val date = flight.date?.let { FlightTimeFormat.fullDate(it) } ?: stringResource(R.string.import_date_unknown)
    val route = if (flight.fromIata != null && flight.toIata != null) {
        stringResource(R.string.import_route, flight.fromIata, flight.toIata)
    } else {
        null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                text = stringResource(R.string.import_flight_row, flight.flight.display, date),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (route != null) {
                Text(route, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyContent(
    state: ImportUiState,
    onChooseFile: () -> Unit,
    onChooseImage: () -> Unit,
    onCloudConsent: (Boolean) -> Unit,
    onTryCloud: () -> Unit,
    onTypeInstead: () -> Unit,
) {
    Text(
        text = stringResource(R.string.import_empty_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = stringResource(R.string.import_empty_message),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(R.string.import_cloud_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.import_cloud_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = state.consent, onCheckedChange = onCloudConsent)
    }
    Spacer(Modifier.height(8.dp))
    PrimaryButton(
        text = stringResource(R.string.import_action_cloud),
        onClick = onTryCloud,
        enabled = state.consent && !state.busy,
        loading = state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    SecondaryButton(
        text = stringResource(R.string.import_action_type),
        onClick = onTypeInstead,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    SecondaryButton(
        text = stringResource(R.string.import_action_file),
        onClick = onChooseFile,
        icon = painterResource(R.drawable.ic_document),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    SecondaryButton(
        text = stringResource(R.string.import_action_image),
        onClick = onChooseImage,
        icon = painterResource(R.drawable.ic_image),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun noticeText(notice: ImportNotice): String = stringResource(
    when (notice) {
        ImportNotice.Truncated -> R.string.import_notice_truncated
        ImportNotice.CloudFailed -> R.string.import_notice_cloud_failed
        ImportNotice.RateLimited -> R.string.import_notice_rate_limited
        ImportNotice.Offline -> R.string.import_notice_offline
        ImportNotice.NotConfigured -> R.string.import_notice_not_configured
        ImportNotice.NothingFound -> R.string.import_notice_nothing
    },
)
