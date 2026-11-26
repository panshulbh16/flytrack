package app.flytrack.feature.friends

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.feature.share.ShareTarget
import app.flytrack.feature.share.shareFlightText
import app.flytrack.ui.components.BackendNotConfiguredState
import app.flytrack.ui.components.ErrorState
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.IconAction
import app.flytrack.ui.components.LoadingState
import app.flytrack.ui.components.OfflineState
import app.flytrack.ui.nav.LocalAppNavigator
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.SecondaryButton
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.theme.FlyTrackTheme
import kotlin.time.Instant

/** Friends: invite with a code (WhatsApp first), list with per-friend alert switches and remove. */
@Composable
fun FriendsRoute(onEnterCode: () -> Unit, onBack: () -> Unit) {
    val viewModel = hiltViewModel<FriendsViewModel>()
    val navigator = LocalAppNavigator.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var resumedOnce by remember { mutableStateOf(false) }
    // Coming back from the accept screen: the new friend appears.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (resumedOnce) viewModel.refresh() else resumedOnce = true
    }
    InviteShareEffect(state.inviteShare, viewModel::inviteHandled)
    FriendsScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onInvite = viewModel::invite,
        onEnterCode = onEnterCode,
        onAlerts = viewModel::setAlerts,
        onRemove = viewModel::remove,
        onMessageShown = viewModel::messageShown,
        onComparePassport = { friend -> navigator.openFriendPassport(friend.id, friend.displayName) },
    )
}

/** Hands a ready invite to WhatsApp, the share sheet, or the clipboard once. */
@Composable
private fun InviteShareEffect(request: InviteShareRequest?, onHandled: (InviteShareRequest) -> Unit) {
    val context = LocalContext.current
    val hinglish = stringResource(R.string.friends_invite_text_hinglish)
    val english = stringResource(R.string.friends_invite_text_english)
    val subject = stringResource(R.string.friends_invite_subject)
    val chooserTitle = stringResource(R.string.friends_invite_chooser)
    val copyLabel = stringResource(R.string.friends_code_label)
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        val code = formatInviteCode(request.code)
        val target = request.target
        if (target == null) {
            copyText(context, copyLabel, code, R.string.friends_code_copied)
        } else {
            val text = inviteMessage(hinglish, english, code)
            if (!shareFlightText(context, text, subject, chooserTitle, target)) {
                Toast.makeText(context, R.string.share_message_no_app, Toast.LENGTH_LONG).show()
            }
        }
        onHandled(request)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    state: FriendsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onInvite: (ShareTarget?) -> Unit,
    onEnterCode: () -> Unit,
    onAlerts: (friendId: String, enabled: Boolean) -> Unit,
    onRemove: (friendId: String) -> Unit,
    onMessageShown: (FriendsMessage) -> Unit,
    onComparePassport: (Friend) -> Unit = {},
) {
    val snackbars = remember { SnackbarHostState() }
    val messageText = when (state.message) {
        null -> null
        FriendsMessage.OFFLINE -> stringResource(R.string.friends_message_offline)
        FriendsMessage.FAILED -> stringResource(R.string.friends_message_failed)
        FriendsMessage.REMOVED -> stringResource(R.string.friends_message_removed)
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        messageText?.let { snackbars.showSnackbar(it) }
        onMessageShown(message)
    }
    var confirmRemove by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { FlyTrackTopBar(title = stringResource(R.string.friends_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        when (state.load) {
            FriendsLoad.LOADING -> LoadingState(Modifier.fillMaxSize().padding(padding))
            FriendsLoad.NOT_CONFIGURED -> BackendNotConfiguredState(Modifier.fillMaxSize().padding(padding))
            FriendsLoad.OFFLINE -> OfflineState(Modifier.fillMaxSize().padding(padding), onRetry = onRefresh)
            FriendsLoad.FAILED -> ErrorState(
                title = stringResource(R.string.friends_load_failed),
                modifier = Modifier.fillMaxSize().padding(padding),
                retryLabel = stringResource(R.string.action_retry),
                onRetry = onRefresh,
            )
            FriendsLoad.LOADED -> PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    item(key = "invite") {
                        InviteCard(state, onInvite, onEnterCode)
                    }
                    item(key = "header") {
                        Spacer(Modifier.height(24.dp))
                        SectionCaption(stringResource(R.string.friends_section_list))
                        Spacer(Modifier.height(4.dp))
                        if (state.friends.isEmpty()) {
                            Text(
                                stringResource(R.string.friends_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                    items(state.friends, key = { it.id }) { friend ->
                        FriendRow(
                            friend = friend,
                            busy = friend.id in state.busy,
                            onAlerts = { onAlerts(friend.id, it) },
                            onRemove = { confirmRemove = friend.id },
                            onCompare = { onComparePassport(friend) },
                        )
                        HairlineDivider()
                    }
                    item(key = "footer") {
                        Text(
                            stringResource(R.string.friends_privacy),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }

    val removing = confirmRemove?.let { id -> state.friends.firstOrNull { it.id == id } }
    if (removing != null) {
        val name = friendName(removing.displayName)
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.friends_remove_title, name)) },
            text = { Text(stringResource(R.string.friends_remove_message, name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    onRemove(removing.id)
                }) { Text(stringResource(R.string.friends_action_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InviteCard(state: FriendsUiState, onInvite: (ShareTarget?) -> Unit, onEnterCode: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.friends_invite_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.friends_invite_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.inviteCode?.let { code ->
                Spacer(Modifier.height(12.dp))
                SectionCaption(stringResource(R.string.friends_code_label))
                Text(
                    formatInviteCode(code),
                    style = FlyTrackTheme.type.numeralSmall,
                    modifier = Modifier.semantics { contentDescription = code.toCharArray().joinToString(" ") },
                )
                Text(
                    stringResource(R.string.friends_code_expiry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = stringResource(R.string.friends_action_invite_whatsapp),
                onClick = { onInvite(ShareTarget.WHATSAPP_FIRST) },
                loading = state.isCreatingInvite,
                enabled = !state.isCreatingInvite,
                icon = painterResource(R.drawable.ic_share),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.friends_action_invite_more),
                    onClick = { onInvite(ShareTarget.SHARE_SHEET) },
                    enabled = !state.isCreatingInvite,
                )
                SecondaryButton(
                    text = stringResource(R.string.friends_action_copy_code),
                    onClick = { onInvite(null) },
                    enabled = !state.isCreatingInvite,
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onEnterCode) { Text(stringResource(R.string.friends_action_enter_code)) }
        }
    }
}

@Composable
private fun FriendRow(friend: Friend, busy: Boolean, onAlerts: (Boolean) -> Unit, onRemove: () -> Unit, onCompare: () -> Unit = {}) {
    val name = friendName(friend.displayName)
    val switchDescription = stringResource(R.string.friends_alerts_switch, name)
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (friend.alertsEnabled) stringResource(R.string.friends_alerts_on) else stringResource(R.string.friends_alerts_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            friend.since?.let { since ->
                Text(
                    stringResource(R.string.friends_since, sinceText(since)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // P3 B10: "You vs Asha".
            TextButton(onClick = onCompare, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.social_compare_action), style = MaterialTheme.typography.labelLarge)
            }
        }
        Switch(
            checked = friend.alertsEnabled,
            onCheckedChange = onAlerts,
            enabled = !busy,
            modifier = Modifier.semantics { contentDescription = switchDescription },
        )
        IconAction(
            icon = painterResource(R.drawable.ic_delete),
            contentDescription = stringResource(R.string.friends_remove_title, name),
            onClick = onRemove,
            enabled = !busy,
        )
    }
}

private fun sinceText(since: Instant): String = FlightTimeFormat.fullDate(FlightTimeFormat.localDate(since, null))

/** The friend's name, or "Your friend" when they set none. */
@Composable
fun friendName(displayName: String?): String = displayName ?: stringResource(R.string.friends_name_fallback)

/** The invite text: Hinglish line, then English line. Templates take `%1$s` = the code ("ABCD-EFGH"). */
fun inviteMessage(hinglishTemplate: String, englishTemplate: String, code: String): String =
    listOf(hinglishTemplate, englishTemplate)
        .map { String.format(java.util.Locale.ROOT, it, code).trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun FriendsPreview() {
    FlyTrackTheme {
        FriendsScreen(
            state = FriendsUiState(
                load = FriendsLoad.LOADED,
                inviteCode = "ABCD2345",
                friends = listOf(
                    Friend("1", "Asha", true, Instant.parse("2026-11-20T10:00:00Z")),
                    Friend("2", null, false, null),
                ),
            ),
            onBack = {}, onRefresh = {}, onInvite = {}, onEnterCode = {}, onAlerts = { _, _ -> }, onRemove = {}, onMessageShown = {},
        )
    }
}
