package app.flytrack.feature.billing.ui

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.feature.billing.ActivityPurchaseHost
import app.flytrack.feature.billing.BillingContract
import app.flytrack.feature.billing.PlanKind
import app.flytrack.feature.billing.ProSource
import app.flytrack.feature.billing.ProState
import app.flytrack.feature.gift.GiftShopPane
import app.flytrack.feature.gift.GiftTabRequest
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.flytrack.ui.components.ErrorState
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.LoadingState
import app.flytrack.ui.components.OfflineState
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.theme.FlyTrackTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant

/** Entry point of the paywall (route `Paywall`). */
@Composable
fun PaywallRoute(onBack: () -> Unit, viewModel: PaywallViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    PaywallScreen(
        state = state,
        onBack = onBack,
        onSelect = viewModel::select,
        onPurchase = { activity?.let { viewModel.purchase(ActivityPurchaseHost(it)) } },
        onRestore = viewModel::restore,
        onRetry = viewModel::retry,
        onMessageShown = viewModel::messageShown,
        // P4 Gift Pro: a second tab that sells gift codes (feature/gift). Hidden in crew mode.
        giftTab = if (LocalUpsellsHidden.current) null else { { GiftShopPane() } },
        startOnGiftTab = remember { GiftTabRequest.consume() },
    )
}

@Composable
fun PaywallScreen(
    state: PaywallUiState,
    onBack: () -> Unit,
    onSelect: (PlanKind) -> Unit,
    onPurchase: () -> Unit,
    onRestore: () -> Unit,
    onRetry: () -> Unit,
    onMessageShown: () -> Unit,
    /** P4: content of the "Gift Pro" tab; null = no tabs. Stateful, so previews leave it out. */
    giftTab: (@Composable () -> Unit)? = null,
    startOnGiftTab: Boolean = false,
) {
    var giftSelected by rememberSaveable { mutableStateOf(startOnGiftTab && giftTab != null) }
    val snackbars = remember { SnackbarHostState() }
    val messageText = state.message?.let { paywallMessageTextFor(it) }
    LaunchedEffect(state.message) {
        val text = messageText ?: return@LaunchedEffect
        snackbars.showSnackbar(text)
        onMessageShown()
    }

    Scaffold(
        topBar = { FlyTrackTopBar(title = "", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            if (giftTab != null) {
                PrimaryTabRow(selectedTabIndex = if (giftSelected) 1 else 0, containerColor = MaterialTheme.colorScheme.background) {
                    Tab(selected = !giftSelected, onClick = { giftSelected = false }, text = { Text(stringResource(R.string.gift_tab_pro)) })
                    Tab(selected = giftSelected, onClick = { giftSelected = true }, text = { Text(stringResource(R.string.gift_tab_gift)) })
                }
                Spacer(Modifier.height(16.dp))
                if (giftSelected) {
                    giftTab()
                    LegalLinks()
                    Spacer(Modifier.height(24.dp))
                    return@Column
                }
            }
            Text(
                text = stringResource(R.string.billing_paywall_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.billing_paywall_subtitle),
                style = MaterialTheme.typography.headlineSmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                color = FlyTrackTheme.accentText,
            )
            Spacer(Modifier.height(20.dp))
            Benefits()
            Spacer(Modifier.height(20.dp))
            HairlineDivider()
            Spacer(Modifier.height(16.dp))
            if (state.pro.isPro) {
                ProActiveCard(state.pro)
                Spacer(Modifier.height(16.dp))
            }
            // An active subscription: selling another plan would bill the user twice.
            if (!state.pro.subscriptionActive) PlanArea(state, onSelect, onPurchase, onRetry)
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onRestore,
                enabled = !state.isBusy && state.content != PaywallContent.ComingSoon,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(R.string.billing_action_restore))
            }
            Text(
                text = stringResource(R.string.billing_legal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            LegalLinks()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Benefits() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Benefit(R.drawable.ic_plane, stringResource(R.string.billing_benefit_inbound_title), stringResource(R.string.billing_benefit_inbound_body))
        Benefit(R.drawable.ic_bell, stringResource(R.string.billing_benefit_late_title), stringResource(R.string.billing_benefit_late_body))
        Benefit(R.drawable.ic_clock, stringResource(R.string.billing_benefit_live_title), stringResource(R.string.billing_benefit_live_body))
        // P3 A1: tracking is unlimited for everyone, so the number of flights is no longer a Pro benefit.
    }
}

@Composable
private fun Benefit(icon: Int, title: String, body: String) {
    Row {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlanArea(state: PaywallUiState, onSelect: (PlanKind) -> Unit, onPurchase: () -> Unit, onRetry: () -> Unit) {
    when (val content = state.content) {
        PaywallContent.Loading -> LoadingState(Modifier.fillMaxWidth().height(200.dp))
        PaywallContent.ComingSoon -> ComingSoonCard()
        PaywallContent.Offline -> OfflineState(Modifier.fillMaxWidth(), onRetry = onRetry)
        PaywallContent.Failed -> ErrorState(
            title = stringResource(R.string.billing_load_failed_title),
            message = stringResource(R.string.billing_load_failed_message),
            retryLabel = stringResource(R.string.action_retry),
            onRetry = onRetry,
            modifier = Modifier.fillMaxWidth(),
        )
        is PaywallContent.Plans -> {
            SectionCaption(stringResource(R.string.billing_section_plans))
            Spacer(Modifier.height(10.dp))
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content.plans.forEach { plan ->
                    PlanCard(plan, selected = plan.kind == state.selected, enabled = !state.isBusy, onClick = { onSelect(plan.kind) })
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = stringResource(R.string.billing_action_continue),
                onClick = onPurchase,
                enabled = state.selected != null && !state.isBusy,
                loading = state.isPurchasing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PlanCard(plan: PlanRow, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val title = when (plan.kind) {
        PlanKind.ANNUAL -> stringResource(R.string.billing_plan_annual)
        PlanKind.MONTHLY -> stringResource(R.string.billing_plan_monthly)
        PlanKind.TRIP_PASS -> stringResource(R.string.billing_plan_trip)
    }
    val detail = when (plan.kind) {
        PlanKind.ANNUAL -> plan.perMonth
            ?.let { stringResource(R.string.billing_plan_annual_price_monthly, plan.price, it) }
            ?: stringResource(R.string.billing_plan_annual_price, plan.price)
        PlanKind.MONTHLY -> stringResource(R.string.billing_plan_monthly_price, plan.price)
        PlanKind.TRIP_PASS -> {
            val days = BillingContract.TRIP_PASS_DURATION.inWholeDays.toInt()
            pluralStringResource(R.plurals.billing_plan_trip_price, days, plan.price, days)
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.defaultMinSize(minHeight = 64.dp).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The whole card is the selectable target; the radio button only draws the state.
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    plan.savePercent?.let {
                        Spacer(Modifier.width(8.dp))
                        ProBadge(text = stringResource(R.string.billing_plan_save, it))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ComingSoonCard() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.billing_coming_soon_title),
                style = FlyTrackTheme.type.statusWordSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.billing_coming_soon_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProActiveCard(pro: ProState) {
    val uriHandler = LocalUriHandler.current
    val manageUrl = stringResource(R.string.billing_manage_url)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.billing_pro_title), style = FlyTrackTheme.type.statusWordSmall)
            Spacer(Modifier.height(4.dp))
            Text(proUntilText(pro), style = MaterialTheme.typography.bodyMedium)
            if (!pro.confirmedByServer) {
                Text(stringResource(R.string.billing_pro_confirming), style = MaterialTheme.typography.bodySmall)
            }
            if (pro.subscriptionActive || pro.source == ProSource.SUBSCRIPTION || pro.source == ProSource.SERVER) {
                TextButton(onClick = { uriHandler.openUri(manageUrl) }) {
                    Text(stringResource(R.string.billing_action_manage))
                }
            }
        }
    }
}

@Composable
private fun LegalLinks() {
    val uriHandler = LocalUriHandler.current
    val terms = stringResource(R.string.billing_terms_url)
    val privacy = stringResource(R.string.billing_privacy_url)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = { uriHandler.openUri(terms) }) { Text(stringResource(R.string.billing_terms)) }
        TextButton(onClick = { uriHandler.openUri(privacy) }) { Text(stringResource(R.string.billing_privacy)) }
    }
}

/** "Pro until 3 Dec 2026" / "Trip pass until …" / "Pro, with no end date", in the phone's zone. */
@Composable
internal fun proUntilText(pro: ProState): String {
    if (pro.isViaFamily) return familyProText(pro.familyOwnerName)
    val until = pro.proUntil ?: return stringResource(R.string.billing_pro_no_end)
    val date = formatProDate(until)
    return if (pro.source == ProSource.TRIP_PASS) {
        stringResource(R.string.billing_trip_until, date)
    } else {
        stringResource(R.string.billing_pro_until, date)
    }
}

internal fun formatProDate(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("d MMM yyyy", locale).withZone(zone).format(java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()))

@Composable
internal fun paywallMessageTextFor(message: PaywallMessage): String = stringResource(
    when (message) {
        PaywallMessage.PURCHASED -> R.string.billing_msg_purchased
        PaywallMessage.PENDING -> R.string.billing_msg_pending
        PaywallMessage.ALREADY_OWNED -> R.string.billing_msg_already_owned
        PaywallMessage.FAILED -> R.string.billing_msg_failed
        PaywallMessage.OFFLINE -> R.string.billing_msg_offline
        PaywallMessage.NOT_AVAILABLE -> R.string.billing_msg_not_available
        PaywallMessage.RESTORED -> R.string.billing_msg_restored
        PaywallMessage.NOTHING_RESTORED -> R.string.billing_msg_nothing_restored
        PaywallMessage.RESTORE_FAILED -> R.string.billing_msg_restore_failed
    },
)

// Previews show a placeholder instead of prices: real prices only ever come from Google Play.
@Preview(name = "Paywall", showBackground = true, heightDp = 1200)
@Composable
private fun PaywallPreview() {
    FlyTrackTheme {
        PaywallScreen(
            state = PaywallUiState(
                content = PaywallContent.Plans(
                    listOf(
                        PlanRow(PlanKind.ANNUAL, PREVIEW_PRICE, perMonth = PREVIEW_PRICE, savePercent = 10),
                        PlanRow(PlanKind.MONTHLY, PREVIEW_PRICE),
                        PlanRow(PlanKind.TRIP_PASS, PREVIEW_PRICE),
                    ),
                ),
                selected = PlanKind.ANNUAL,
            ),
            onBack = {}, onSelect = {}, onPurchase = {}, onRestore = {}, onRetry = {}, onMessageShown = {},
        )
    }
}

@Preview(name = "Paywall, coming soon, dark", showBackground = true, heightDp = 1000)
@Composable
private fun PaywallComingSoonPreview() {
    FlyTrackTheme(darkTheme = true) {
        PaywallScreen(
            state = PaywallUiState(content = PaywallContent.ComingSoon),
            onBack = {}, onSelect = {}, onPurchase = {}, onRestore = {}, onRetry = {}, onMessageShown = {},
        )
    }
}

private const val PREVIEW_PRICE = "₹ ···"
