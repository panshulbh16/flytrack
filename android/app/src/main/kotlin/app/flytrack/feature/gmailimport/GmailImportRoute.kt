package app.flytrack.feature.gmailimport

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.flytrack.R
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.ui.components.EmptyState
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.LoadingState
import app.flytrack.ui.components.OfflineState
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.SecondaryButton

/**
 * Entry point of the Gmail import screen.
 *
 * @param onOpenSuggestion a suggestion was tapped: open add-flight filled in from it
 * @param onImportManually the file/screenshot importer, offered when nothing was found
 * @param onTypeInstead an empty add-flight form
 */
@Composable
fun GmailImportRoute(
    onOpenSuggestion: (GmailSuggestion) -> Unit,
    onImportManually: () -> Unit,
    onTypeInstead: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: GmailImportViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val resolve = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onResolution(result.resultCode == Activity.RESULT_OK, result.data)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.resolutions.collect { pending -> resolve.launch(IntentSenderRequest.Builder(pending).build()) }
        }
    }

    GmailImportScreen(
        state = state,
        onConnect = viewModel::onConnect,
        onRetry = viewModel::onRetry,
        onOpenSuggestion = onOpenSuggestion,
        onImportManually = onImportManually,
        onTypeInstead = onTypeInstead,
        onBack = onBack,
    )
}

@Composable
fun GmailImportScreen(
    state: GmailImportUiState,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
    onOpenSuggestion: (GmailSuggestion) -> Unit,
    onImportManually: () -> Unit,
    onTypeInstead: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { FlyTrackTopBar(title = stringResource(R.string.gmail_title), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            when (state.scan) {
                GmailScanState.NotConnected, GmailScanState.Connecting ->
                    ConnectContent(state, R.string.gmail_action_connect, onConnect)
                GmailScanState.NeedsReconnect -> {
                    Text(
                        text = stringResource(R.string.gmail_reconnect_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(8.dp))
                    ConnectContent(state, R.string.gmail_action_reconnect, onConnect)
                }
                GmailScanState.Scanning -> LoadingState(label = stringResource(R.string.gmail_scanning))
                GmailScanState.Results -> SuggestionList(state.suggestions, onOpenSuggestion)
                GmailScanState.NoPending -> NoPendingContent(onImportManually, onTypeInstead)
                GmailScanState.MoreToRead -> {
                    EmptyState(title = stringResource(R.string.gmail_more_to_read), icon = painterResource(R.drawable.ic_mail))
                    PrimaryButton(
                        text = stringResource(R.string.gmail_action_scan_again),
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                GmailScanState.Offline -> {
                    OfflineState(onRetry = onRetry)
                    SuggestionList(state.suggestions, onOpenSuggestion)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The disclosure Google requires before consent, then the connect button. */
@Composable
private fun ConnectContent(state: GmailImportUiState, @StringRes action: Int, onConnect: () -> Unit) {
    val connecting = state.scan == GmailScanState.Connecting
    Text(
        text = stringResource(R.string.gmail_disclosure),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(20.dp))
    PrimaryButton(
        text = stringResource(action),
        onClick = onConnect,
        enabled = !connecting,
        loading = connecting,
        icon = painterResource(R.drawable.ic_mail),
        modifier = Modifier.fillMaxWidth(),
    )
    state.notice?.let { notice ->
        Spacer(Modifier.height(12.dp))
        Text(
            text = noticeText(notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun noticeText(notice: GmailConnectNotice): String = stringResource(
    when (notice) {
        GmailConnectNotice.Offline -> R.string.gmail_notice_offline
        GmailConnectNotice.NotConfigured -> R.string.gmail_notice_not_configured
        GmailConnectNotice.Failed -> R.string.gmail_notice_failed
    },
)

@Composable
private fun SuggestionList(suggestions: List<GmailSuggestion>, onOpen: (GmailSuggestion) -> Unit) {
    if (suggestions.isEmpty()) return
    Text(
        text = stringResource(R.string.gmail_results_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(8.dp))
    // ponytail: plain Column, the list is a handful of upcoming bookings; switch to LazyColumn if it ever grows large.
    suggestions.forEach { suggestion -> SuggestionRow(suggestion, onClick = { onOpen(suggestion) }) }
}

@Composable
private fun SuggestionRow(suggestion: GmailSuggestion, onClick: () -> Unit) {
    val from = suggestion.fromIata
    val to = suggestion.toIata
    val route = if (from != null && to != null) stringResource(R.string.gmail_route, from, to) else null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.gmail_flight_row, suggestion.flight, FlightTimeFormat.fullDate(suggestion.date)),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (route != null) {
                Text(route, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = suggestion.subject,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoPendingContent(onImportManually: () -> Unit, onTypeInstead: () -> Unit) {
    EmptyState(title = stringResource(R.string.gmail_empty_title), icon = painterResource(R.drawable.ic_mail))
    PrimaryButton(
        text = stringResource(R.string.gmail_action_import_manually),
        onClick = onImportManually,
        icon = painterResource(R.drawable.ic_document),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    SecondaryButton(
        text = stringResource(R.string.gmail_action_type),
        onClick = onTypeInstead,
        modifier = Modifier.fillMaxWidth(),
    )
}
