package app.flytrack.feature.forwarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.flytrack.R
import app.flytrack.feature.billing.ui.LocalProState
import app.flytrack.feature.forecast.ProFeatureTeaser
import app.flytrack.feature.friends.RemoteResult
import app.flytrack.feature.friends.copyText
import app.flytrack.ui.components.BackendNotConfiguredState
import app.flytrack.ui.components.ErrorState
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.LoadingState
import app.flytrack.ui.components.OfflineState
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.theme.FlyTrackTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The forwarding address screen. */
sealed interface ForwardingUiState {
    /** Free user: what the feature does and the teaser. */
    data object Locked : ForwardingUiState
    data object Loading : ForwardingUiState
    data class Ready(val address: String) : ForwardingUiState
    data object Offline : ForwardingUiState
    data object NotConfigured : ForwardingUiState
    data object Failed : ForwardingUiState
}

@HiltViewModel
class EmailForwardingViewModel @Inject constructor(
    private val repository: ForwardingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ForwardingUiState>(ForwardingUiState.Loading)
    val uiState: StateFlow<ForwardingUiState> = _uiState.asStateFlow()

    /** The address is only asked for Pro users; a later upgrade on the paywall loads it then. */
    fun setPro(pro: Boolean) {
        if (!pro) {
            _uiState.value = ForwardingUiState.Locked
        } else if (_uiState.value !is ForwardingUiState.Ready) {
            load()
        }
    }

    fun retry() = load()

    private fun load() {
        _uiState.value = ForwardingUiState.Loading
        viewModelScope.launch {
            _uiState.value = when (val result = repository.address()) {
                is RemoteResult.Ok -> ForwardingUiState.Ready(result.value)
                RemoteResult.Offline -> ForwardingUiState.Offline
                RemoteResult.NotConfigured -> ForwardingUiState.NotConfigured
                is RemoteResult.Refused, is RemoteResult.Failed -> ForwardingUiState.Failed
            }
        }
    }
}

/** Settings → "Import by email" (P2 wave 2, section 2). Pro. */
@Composable
fun EmailForwardingRoute(onBack: () -> Unit) {
    val viewModel = hiltViewModel<EmailForwardingViewModel>()
    val pro = LocalProState.current.isPro
    LaunchedEffect(pro) { viewModel.setPro(pro) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    EmailForwardingScreen(state, onBack = onBack, onRetry = viewModel::retry)
}

@Composable
fun EmailForwardingScreen(state: ForwardingUiState, onBack: () -> Unit, onRetry: () -> Unit) {
    Scaffold(topBar = { FlyTrackTopBar(title = stringResource(R.string.forwarding_title), onBack = onBack) }) { padding ->
        when (state) {
            ForwardingUiState.Loading -> LoadingState(Modifier.fillMaxSize().padding(padding))
            ForwardingUiState.Offline -> OfflineState(Modifier.fillMaxSize().padding(padding), onRetry = onRetry)
            ForwardingUiState.NotConfigured -> BackendNotConfiguredState(Modifier.fillMaxSize().padding(padding))
            ForwardingUiState.Failed -> ErrorState(
                title = stringResource(R.string.forwarding_failed),
                modifier = Modifier.fillMaxSize().padding(padding),
                retryLabel = stringResource(R.string.action_retry),
                onRetry = onRetry,
            )
            ForwardingUiState.Locked, is ForwardingUiState.Ready -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    stringResource(R.string.forwarding_intro),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(20.dp))
                if (state is ForwardingUiState.Ready) {
                    AddressCard(state.address)
                } else {
                    ProFeatureTeaser(
                        title = stringResource(R.string.forwarding_teaser_title),
                        body = stringResource(R.string.forwarding_teaser_body),
                    )
                }
                Spacer(Modifier.height(24.dp))
                HowTo()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AddressCard(address: String) {
    val context = LocalContext.current
    val label = stringResource(R.string.forwarding_address_label)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionCaption(label)
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(address, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = stringResource(R.string.forwarding_action_copy),
                onClick = { copyText(context, label, address, R.string.forwarding_copied) },
                icon = painterResource(R.drawable.ic_mail),
            )
        }
    }
}

@Composable
private fun HowTo() {
    Text(
        stringResource(R.string.forwarding_howto_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    listOf(
        R.string.forwarding_step_1,
        R.string.forwarding_step_2,
        R.string.forwarding_step_3,
    ).forEachIndexed { index, step ->
        Row(Modifier.padding(top = 10.dp)) {
            Text("${index + 1}.", style = MaterialTheme.typography.bodyLarge, color = FlyTrackTheme.accentText)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(step), style = MaterialTheme.typography.bodyLarge)
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.forwarding_privacy),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview(showBackground = true)
@Composable
private fun EmailForwardingPreview() {
    FlyTrackTheme { EmailForwardingScreen(ForwardingUiState.Ready("trips+ab3kq7mz2x@in.heygate.app"), onBack = {}, onRetry = {}) }
}

@Preview(showBackground = true)
@Composable
private fun EmailForwardingLockedPreview() {
    FlyTrackTheme { EmailForwardingScreen(ForwardingUiState.Locked, onBack = {}, onRetry = {}) }
}
