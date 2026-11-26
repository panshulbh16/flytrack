package app.flytrack.feature.billing.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.flytrack.R
import app.flytrack.core.model.UserProfile
import app.flytrack.feature.billing.ProRepository
import app.flytrack.feature.billing.ProSource
import app.flytrack.feature.billing.ProState
import app.flytrack.feature.billing.RestoreOutcome
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.theme.FlyTrackTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProSettingsUiState(val isRestoring: Boolean = false, val lastRestore: PaywallMessage? = null)

@HiltViewModel
class ProSettingsViewModel @Inject constructor(private val pro: ProRepository) : ViewModel() {
    private val state = MutableStateFlow(ProSettingsUiState())
    val uiState: StateFlow<ProSettingsUiState> = state.asStateFlow()

    val isBillingAvailable: Boolean get() = pro.isBillingAvailable

    fun restore() {
        if (state.value.isRestoring) return
        state.value = ProSettingsUiState(isRestoring = true)
        viewModelScope.launch {
            val message = when (val outcome = pro.restore()) {
                is RestoreOutcome.Restored -> if (outcome.hasPro) PaywallMessage.RESTORED else PaywallMessage.NOTHING_RESTORED
                RestoreOutcome.NotAvailable -> PaywallMessage.NOT_AVAILABLE
                RestoreOutcome.Offline -> PaywallMessage.OFFLINE
                is RestoreOutcome.Failed -> PaywallMessage.RESTORE_FAILED
            }
            state.value = ProSettingsUiState(isRestoring = false, lastRestore = message)
        }
    }
}

/** Settings block: plan, "Go Pro" (opens the paywall), restore purchases, manage subscription. */
@Composable
fun ProSettingsSection(modifier: Modifier = Modifier, viewModel: ProSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProSettingsContent(
        pro = LocalProState.current,
        state = state,
        billingAvailable = viewModel.isBillingAvailable,
        onGoPro = LocalPaywallLauncher.current::openPaywall,
        onRestore = viewModel::restore,
        modifier = modifier,
    )
}

@Composable
fun ProSettingsContent(
    pro: ProState,
    state: ProSettingsUiState,
    billingAvailable: Boolean,
    onGoPro: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val manageUrl = stringResource(R.string.billing_manage_url)
    Column(modifier) {
        SectionCaption(stringResource(R.string.billing_settings_section))
        Spacer(Modifier.height(8.dp))
        if (pro.isPro) {
            Text(stringResource(R.string.billing_pro_title), style = FlyTrackTheme.type.statusWordSmall)
            Text(
                text = proUntilText(pro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pro.subscriptionActive || pro.source == ProSource.SUBSCRIPTION || pro.source == ProSource.SERVER) {
                TextButton(onClick = { uriHandler.openUri(manageUrl) }) { Text(stringResource(R.string.billing_action_manage)) }
            }
        } else {
            Text(
                text = stringResource(R.string.billing_settings_free, UserProfile.FREE_TRACKING_LIMIT),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = stringResource(R.string.billing_settings_go_pro),
                onClick = onGoPro,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (billingAvailable) {
            TextButton(onClick = onRestore, enabled = !state.isRestoring) {
                Text(stringResource(R.string.billing_action_restore))
            }
        }
        state.lastRestore?.let { message ->
            Text(
                text = paywallMessageTextFor(message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
