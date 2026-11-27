package app.flytrack.feature.tripit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.core.time.relativeTime
import app.flytrack.feature.billing.ui.LocalPaywallLauncher
import app.flytrack.feature.billing.ui.LocalProState
import app.flytrack.feature.billing.ui.LocalUpsellsHidden
import app.flytrack.feature.billing.ui.ProBadge
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.SecondaryButton
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.components.relativeTimeText
import kotlin.time.Clock

/**
 * Settings → TripIt: connect (Custom Tab), status, "Sync now", "Disconnect". The deep link
 * `heygate://tripit/connected` (see [TripItDeepLinks]) and every return to the screen refresh the status.
 * Hidden while the server has no TripIt key. Draws its own trailing hairline.
 */
@Composable
fun TripItSettingsSection(modifier: Modifier = Modifier, viewModel: TripItViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val paywall = LocalPaywallLauncher.current
    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()
    var noBrowser by remember { mutableStateOf(false) }

    // Back from the Custom Tab without the redirect (closed it), or from anywhere else.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(state.openUrl) {
        val url = state.openUrl ?: return@LaunchedEffect
        noBrowser = !openInCustomTab(context, url, toolbarColor)
        viewModel.authorizeOpened()
    }
    LaunchedEffect(state.showPaywall) {
        if (state.showPaywall) {
            paywall.openPaywall()
            viewModel.paywallShown()
        }
    }

    if (state.row == TripItRowState.Hidden || state.row == TripItRowState.Loading) return
    TripItSectionContent(
        state = state,
        isPro = LocalProState.current.isPro,
        upsellsHidden = LocalUpsellsHidden.current,
        noBrowser = noBrowser,
        onConnect = {
            noBrowser = false
            viewModel.connect()
        },
        onOpenPaywall = paywall::openPaywall,
        onSync = viewModel::sync,
        onDisconnect = viewModel::disconnect,
        onRetry = viewModel::refresh,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripItSectionContent(
    state: TripItUiState,
    isPro: Boolean,
    upsellsHidden: Boolean,
    noBrowser: Boolean,
    onConnect: () -> Unit,
    onOpenPaywall: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionCaption(stringResource(R.string.tripit_section))
        Spacer(Modifier.height(8.dp))
        when (val row = state.row) {
            TripItRowState.Disconnected -> {
                Body(stringResource(R.string.tripit_connect_message))
                Spacer(Modifier.height(12.dp))
                when {
                    isPro -> PrimaryButton(
                        text = stringResource(R.string.tripit_connect),
                        onClick = onConnect,
                        loading = state.isConnecting,
                        enabled = !state.isBusy,
                        icon = painterResource(R.drawable.ic_tripit_sync),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Free users: the button leads to the paywall; in crew mode nothing is sold.
                    upsellsHidden -> Body(stringResource(R.string.tripit_pro_note))
                    else -> {
                        ProBadge(stringResource(R.string.billing_badge_pro))
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton(
                            text = stringResource(R.string.tripit_connect),
                            onClick = onOpenPaywall,
                            icon = painterResource(R.drawable.ic_lock),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            is TripItRowState.Connected -> {
                Text(stringResource(R.string.tripit_connected), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Body(
                    row.lastSyncAt?.let { stringResource(R.string.tripit_last_sync, relativeTimeText(relativeTime(minOf(it, Clock.System.now()), Clock.System.now()))) }
                        ?: stringResource(R.string.tripit_never_synced),
                )
                if (row.needsReconnect) Body(stringResource(R.string.tripit_revoked_message))
                if (row.tripItTrouble) Body(stringResource(R.string.tripit_trouble_message))
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (row.needsReconnect) {
                        PrimaryButton(
                            text = stringResource(R.string.tripit_connect_again),
                            onClick = if (isPro || upsellsHidden) onConnect else onOpenPaywall,
                            loading = state.isConnecting,
                            enabled = !state.isBusy,
                        )
                    } else if (isPro) {
                        PrimaryButton(
                            text = stringResource(R.string.tripit_sync_now),
                            onClick = onSync,
                            loading = state.isSyncing,
                            enabled = !state.isBusy,
                            icon = painterResource(R.drawable.ic_tripit_sync),
                        )
                    }
                    // Disconnect works without Pro (contract section 7).
                    SecondaryButton(
                        text = stringResource(R.string.tripit_disconnect),
                        onClick = onDisconnect,
                        loading = state.isDisconnecting,
                        enabled = !state.isBusy,
                    )
                }
            }
            TripItRowState.Unknown -> {
                Body(stringResource(R.string.tripit_status_unknown))
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
            TripItRowState.Loading, TripItRowState.Hidden -> Unit
        }
        val notice = state.syncNotice
        val message = when {
            noBrowser -> stringResource(R.string.tripit_no_browser)
            notice != null -> listOfNotNull(
                notice.added.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.tripit_msg_added, it, it) },
                notice.notFound.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.tripit_msg_not_found, it, it) },
            ).joinToString(" · ")
            else -> state.message?.let { tripItMessageText(it) }
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(12.dp))
        HairlineDivider()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun tripItMessageText(message: TripItMessage): String = stringResource(
    when (message) {
        TripItMessage.CONNECTED -> R.string.tripit_msg_connected
        TripItMessage.CONNECT_DENIED -> R.string.tripit_msg_denied
        TripItMessage.CONNECT_EXPIRED -> R.string.tripit_msg_expired
        TripItMessage.CONNECT_FAILED -> R.string.tripit_msg_connect_failed
        TripItMessage.TRIPIT_DOWN -> R.string.tripit_msg_tripit_down
        TripItMessage.SYNCED_NOTHING_NEW -> R.string.tripit_msg_nothing_new
        TripItMessage.OFFLINE -> R.string.tripit_msg_offline
        TripItMessage.FAILED -> R.string.tripit_msg_failed
        TripItMessage.DISCONNECTED -> R.string.tripit_msg_disconnected
        TripItMessage.REVOKED -> R.string.tripit_msg_revoked
        TripItMessage.NOT_CONNECTED -> R.string.tripit_msg_not_connected
    },
)
