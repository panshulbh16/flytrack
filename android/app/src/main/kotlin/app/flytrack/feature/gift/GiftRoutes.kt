package app.flytrack.feature.gift

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.feature.billing.ui.LocalPaywallLauncher
import app.flytrack.feature.billing.ui.LocalUpsellsHidden
import app.flytrack.feature.billing.ui.formatProDate
import app.flytrack.feature.radar.P4NavRow
import app.flytrack.ui.components.BackendNotConfiguredState
import app.flytrack.ui.components.EmptyState
import app.flytrack.ui.components.ErrorState
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.LoadingState
import app.flytrack.ui.components.OfflineState
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.nav.LocalAppNavigator
import app.flytrack.ui.theme.FlyTrackTheme

// ---- Redeem a gift code ----------------------------------------------------------------------------------

/** Entry point of "Redeem a gift code" (route `RedeemGift`). */
@Composable
fun RedeemGiftRoute(prefillCode: String?, onBack: () -> Unit, viewModel: RedeemGiftViewModel = hiltViewModel()) {
    LaunchedEffect(prefillCode) { viewModel.prefill(prefillCode) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RedeemGiftScreen(state = state, onInput = viewModel::onInput, onRedeem = viewModel::redeem, onBack = onBack)
}

@Composable
fun RedeemGiftScreen(state: RedeemGiftUiState, onInput: (String) -> Unit, onRedeem: () -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { FlyTrackTopBar(title = stringResource(R.string.gift_redeem_title), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            val outcome = state.outcome
            if (outcome is RedeemOutcome.Redeemed) {
                RedeemedCard(outcome)
                Spacer(Modifier.height(20.dp))
                PrimaryButton(text = stringResource(R.string.action_done), onClick = onBack, modifier = Modifier.fillMaxWidth())
                return@Column
            }
            Text(stringResource(R.string.gift_redeem_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.input,
                onValueChange = onInput,
                label = { Text(stringResource(R.string.gift_redeem_label)) },
                placeholder = { Text(stringResource(R.string.gift_redeem_hint)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (state.canRedeem) onRedeem() }),
                isError = outcome != null,
                modifier = Modifier.fillMaxWidth(),
            )
            outcome?.let {
                Text(
                    text = redeemErrorText(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = stringResource(R.string.gift_redeem_action),
                onClick = onRedeem,
                enabled = state.canRedeem,
                loading = state.isRedeeming,
                icon = painterResource(R.drawable.ic_gift),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RedeemedCard(outcome: RedeemOutcome.Redeemed) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.gift_redeem_done_title), style = FlyTrackTheme.type.statusWord)
            if (outcome.days > 0) {
                Text(pluralStringResource(R.plurals.gift_redeem_done_days, outcome.days, outcome.days), style = MaterialTheme.typography.bodyLarge)
            }
            outcome.proUntil?.let {
                Text(stringResource(R.string.gift_redeem_done_until, formatProDate(it)), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun redeemErrorText(outcome: RedeemOutcome): String = stringResource(
    when (outcome) {
        RedeemOutcome.InvalidCode -> R.string.gift_redeem_invalid
        RedeemOutcome.AlreadyRedeemed -> R.string.gift_redeem_already
        RedeemOutcome.Voided -> R.string.gift_redeem_voided
        RedeemOutcome.OwnGift -> R.string.gift_redeem_own
        RedeemOutcome.RateLimited -> R.string.gift_redeem_rate_limited
        RedeemOutcome.LifetimePro -> R.string.gift_redeem_lifetime
        RedeemOutcome.Offline -> R.string.gift_redeem_offline
        RedeemOutcome.NotConfigured, is RedeemOutcome.Failed, is RedeemOutcome.Redeemed -> R.string.gift_redeem_failed
    },
)

// ---- My gifts -----------------------------------------------------------------------------------------------

/** Entry point of "My gifts" (route `MyGifts`). */
@Composable
fun MyGiftsRoute(onBack: () -> Unit, viewModel: MyGiftsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // A code bought a moment ago may only now have arrived from RevenueCat's webhook.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    MyGiftsScreen(state = state, onRetry = viewModel::refresh, onBack = onBack)
}

@Composable
fun MyGiftsScreen(state: MyGiftsContent, onRetry: () -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { FlyTrackTopBar(title = stringResource(R.string.gift_my_title), onBack = onBack) }) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (state) {
            MyGiftsContent.Loading -> LoadingState(modifier)
            MyGiftsContent.Offline -> OfflineState(modifier.padding(24.dp), onRetry = onRetry)
            MyGiftsContent.NotConfigured -> BackendNotConfiguredState(modifier.padding(24.dp))
            MyGiftsContent.Failed -> ErrorState(title = stringResource(R.string.gift_redeem_failed), onRetry = onRetry, modifier = modifier.padding(24.dp))
            is MyGiftsContent.Loaded -> if (state.gifts.isEmpty()) {
                MyGiftsEmpty(modifier.padding(24.dp))
            } else {
                LazyColumn(modifier.padding(horizontal = 20.dp)) {
                    items(state.gifts, key = { it.code }) { gift ->
                        GiftRow(gift)
                        HairlineDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun MyGiftsEmpty(modifier: Modifier) {
    val paywall = LocalPaywallLauncher.current
    val hidden = LocalUpsellsHidden.current
    EmptyState(
        title = stringResource(R.string.gift_my_empty_title),
        message = stringResource(R.string.gift_my_empty_message),
        icon = painterResource(R.drawable.ic_gift),
        actionLabel = if (hidden) null else stringResource(R.string.gift_settings_give),
        onAction = if (hidden) {
            null
        } else {
            {
                GiftTabRequest.request()
                paywall.openPaywall()
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun GiftRow(gift: Gift) {
    Column(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = gift.code,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when (gift.status) {
                    GiftStatus.UNUSED -> stringResource(R.string.gift_status_unused)
                    GiftStatus.REDEEMED -> gift.redeemedAt?.let { stringResource(R.string.gift_status_redeemed, formatProDate(it)) }
                        ?: stringResource(R.string.gift_status_redeemed_plain)
                    GiftStatus.VOIDED -> stringResource(R.string.gift_status_voided)
                },
                style = MaterialTheme.typography.labelLarge,
                color = when (gift.status) {
                    GiftStatus.UNUSED -> FlyTrackTheme.accentText
                    GiftStatus.REDEEMED -> MaterialTheme.colorScheme.onSurfaceVariant
                    GiftStatus.VOIDED -> MaterialTheme.colorScheme.error
                },
            )
        }
        val kind = if (gift.product == GiftProduct.YEAR) {
            stringResource(R.string.gift_kind_year)
        } else {
            pluralStringResource(R.plurals.gift_kind_days, gift.days, gift.days)
        }
        Text(
            text = listOfNotNull(kind, gift.createdAt?.let { stringResource(R.string.gift_bought_on, formatProDate(it)) }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (gift.status == GiftStatus.UNUSED) {
            Spacer(Modifier.height(8.dp))
            GiftCodeCard(code = gift.code, onStartOver = null)
        }
    }
}

// ---- Settings rows -------------------------------------------------------------------------------------------

/** Settings: "Redeem a gift code", "My gifts" and (outside crew mode) "Gift Pro". */
@Composable
fun GiftSettingsRows(modifier: Modifier = Modifier) {
    val navigator = LocalAppNavigator.current
    val paywall = LocalPaywallLauncher.current
    Column(modifier) {
        P4NavRow(
            iconRes = R.drawable.ic_gift,
            title = stringResource(R.string.gift_settings_redeem),
            message = stringResource(R.string.gift_settings_redeem_message),
            onClick = { navigator.openRedeemGift() },
        )
        P4NavRow(
            iconRes = R.drawable.ic_ticket,
            title = stringResource(R.string.gift_settings_my_gifts),
            message = stringResource(R.string.gift_settings_my_gifts_message),
            onClick = navigator::openMyGifts,
        )
        if (!LocalUpsellsHidden.current) {
            P4NavRow(
                iconRes = R.drawable.ic_gift,
                title = stringResource(R.string.gift_settings_give),
                message = stringResource(R.string.gift_settings_give_message),
                onClick = {
                    GiftTabRequest.request()
                    paywall.openPaywall()
                },
            )
        }
    }
}
