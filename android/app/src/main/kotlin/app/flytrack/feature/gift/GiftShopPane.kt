package app.flytrack.feature.gift

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.feature.billing.ActivityPurchaseHost
import app.flytrack.feature.friends.copyText
import app.flytrack.feature.share.ShareTarget
import app.flytrack.feature.share.shareFlightText
import app.flytrack.ui.components.ErrorState
import app.flytrack.ui.components.LoadingState
import app.flytrack.ui.components.OfflineState
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.SecondaryButton
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.nav.LocalAppNavigator
import app.flytrack.ui.theme.FlyTrackTheme

/** The "Gift Pro" tab of the paywall: pick a gift, pay, then share the code. */
@Composable
fun GiftShopPane(modifier: Modifier = Modifier, viewModel: GiftShopViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    val navigator = LocalAppNavigator.current
    GiftShopContentView(
        state = state,
        onSelect = viewModel::select,
        onBuy = { activity?.let { viewModel.purchase(ActivityPurchaseHost(it)) } },
        onRetry = viewModel::retry,
        onStartOver = viewModel::startOver,
        onMyGifts = navigator::openMyGifts,
        modifier = modifier,
    )
}

@Composable
fun GiftShopContentView(
    state: GiftShopUiState,
    onSelect: (GiftProduct) -> Unit,
    onBuy: () -> Unit,
    onRetry: () -> Unit,
    onStartOver: () -> Unit,
    onMyGifts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(stringResource(R.string.gift_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.gift_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        when (val purchase = state.purchase) {
            is GiftPurchaseState.CodeReady -> GiftCodeCard(purchase.gift.code, onStartOver)
            GiftPurchaseState.CodeLater -> InfoCard(
                title = stringResource(R.string.gift_code_later_title),
                message = stringResource(R.string.gift_code_later_message),
                action = stringResource(R.string.gift_action_my_gifts) to onMyGifts,
            )
            GiftPurchaseState.WaitingForCode -> LoadingState(Modifier.fillMaxWidth().height(160.dp), label = stringResource(R.string.gift_waiting))
            GiftPurchaseState.Idle, GiftPurchaseState.Purchasing -> GiftOptions(state, onSelect, onBuy, onRetry)
        }
        state.message?.let { message ->
            Text(
                text = stringResource(
                    when (message) {
                        GiftShopMessage.PENDING -> R.string.gift_msg_pending
                        GiftShopMessage.OFFLINE -> R.string.gift_msg_offline
                        GiftShopMessage.NOT_AVAILABLE -> R.string.gift_msg_not_available
                        GiftShopMessage.FAILED -> R.string.gift_msg_failed
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = FlyTrackTheme.accentText,
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        TextButton(onClick = onMyGifts, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.gift_action_my_gifts))
        }
    }
}

@Composable
private fun GiftOptions(state: GiftShopUiState, onSelect: (GiftProduct) -> Unit, onBuy: () -> Unit, onRetry: () -> Unit) {
    when (val content = state.content) {
        GiftShopContent.Loading -> LoadingState(Modifier.fillMaxWidth().height(160.dp))
        GiftShopContent.ComingSoon -> InfoCard(
            title = stringResource(R.string.gift_coming_soon_title),
            message = stringResource(R.string.gift_coming_soon_message),
        )
        GiftShopContent.Offline -> OfflineState(Modifier.fillMaxWidth(), onRetry = onRetry)
        GiftShopContent.Failed -> ErrorState(
            title = stringResource(R.string.gift_load_failed_title),
            message = stringResource(R.string.gift_load_failed_message),
            onRetry = onRetry,
            modifier = Modifier.fillMaxWidth(),
        )
        is GiftShopContent.Options -> {
            SectionCaption(stringResource(R.string.gift_section_options))
            Spacer(Modifier.height(10.dp))
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content.options.forEach { option ->
                    GiftOptionCard(option, selected = option.product == state.selected, enabled = !state.isBusy, onClick = { onSelect(option.product) })
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = stringResource(R.string.gift_action_buy),
                onClick = onBuy,
                enabled = state.selected != null && !state.isBusy,
                loading = state.purchase == GiftPurchaseState.Purchasing,
                icon = painterResource(R.drawable.ic_gift),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.gift_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun GiftOptionCard(option: GiftOption, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val title = when (option.product) {
        GiftProduct.YEAR -> stringResource(R.string.gift_option_year)
        GiftProduct.TRIP -> stringResource(R.string.gift_option_trip)
    }
    val detail = when (option.product) {
        GiftProduct.YEAR -> stringResource(R.string.gift_option_price_year, option.price.formatted)
        GiftProduct.TRIP -> stringResource(R.string.gift_option_price_trip, option.price.formatted)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(Modifier.defaultMinSize(minHeight = 64.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The code, large and easy to read aloud, with WhatsApp / Share / Copy. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GiftCodeCard(code: String, onStartOver: (() -> Unit)?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val description = stringResource(R.string.gift_cd_code, code.toList().joinToString(" "))
    val hinglish = stringResource(R.string.gift_share_hinglish, code)
    val english = stringResource(R.string.gift_share_english, code)
    val subject = stringResource(R.string.gift_share_subject)
    val chooser = stringResource(R.string.gift_share_chooser)
    val text = "$hinglish\n$english"
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.gift_ready_title), style = FlyTrackTheme.type.statusWordSmall)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.gift_ready_message), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            Text(
                text = code,
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
                modifier = Modifier.semantics { contentDescription = description },
            )
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    text = stringResource(R.string.gift_action_whatsapp),
                    onClick = { shareGift(context, text, subject, chooser, ShareTarget.WHATSAPP_FIRST) },
                    icon = painterResource(R.drawable.ic_share),
                )
                SecondaryButton(
                    text = stringResource(R.string.gift_action_share),
                    onClick = { shareGift(context, text, subject, chooser, ShareTarget.SHARE_SHEET) },
                )
                SecondaryButton(
                    text = stringResource(R.string.gift_action_copy),
                    onClick = { copyText(context, subject, code, R.string.gift_copied) },
                )
            }
            if (onStartOver != null) {
                TextButton(onClick = onStartOver) { Text(stringResource(R.string.gift_action_buy_another)) }
            }
        }
    }
}

private fun shareGift(context: Context, text: String, subject: String, chooser: String, target: ShareTarget) {
    if (!shareFlightText(context, text, subject, chooser, target)) {
        android.widget.Toast.makeText(context, R.string.gift_share_no_app, android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun InfoCard(title: String, message: String, action: Pair<String, () -> Unit>? = null) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = FlyTrackTheme.type.statusWordSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
        }
    }
}

/**
 * Asks the next paywall to open on its "Gift Pro" tab (Settings → Gift Pro). Read once by the paywall.
 */
object GiftTabRequest {
    @Volatile private var pending = false

    fun request() {
        pending = true
    }

    /** True once after [request]. */
    fun consume(): Boolean = pending.also { pending = false }
}
