package app.flytrack.feature.family

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.feature.billing.ProSource
import app.flytrack.feature.billing.ProState
import app.flytrack.feature.billing.ui.LocalPaywallLauncher
import app.flytrack.feature.billing.ui.LocalProState
import app.flytrack.feature.billing.ui.LocalUpsellsHidden
import app.flytrack.feature.billing.ui.familyProText
import app.flytrack.feature.friends.copyText
import app.flytrack.feature.friends.formatInviteCode
import app.flytrack.feature.share.ShareTarget
import app.flytrack.feature.share.shareFlightText
import app.flytrack.ui.components.BackendNotConfiguredState
import app.flytrack.ui.components.ErrorState
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.IconAction
import app.flytrack.ui.components.LoadingState
import app.flytrack.ui.components.OfflineState
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.SecondaryButton
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.theme.FlyTrackTheme

/** Entry point of Family sharing (P3 B13). [prefillCode] comes from an invite link or pasted text. */
@Composable
fun FamilyRoute(prefillCode: String?, onBack: () -> Unit) {
    val viewModel = hiltViewModel<FamilyViewModel, FamilyViewModel.Factory>(key = "family") { it.create(prefillCode) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FamilyInviteShareEffect(state.inviteShare, viewModel::inviteHandled)
    FamilyScreen(
        state = state,
        pro = LocalProState.current,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onShareInvite = { viewModel.shareInvite(copyOnly = false) },
        onCopyInvite = { viewModel.shareInvite(copyOnly = true) },
        onCodeInputChange = viewModel::onCodeInputChange,
        onJoin = viewModel::join,
        onLeave = viewModel::leave,
        onRemove = viewModel::remove,
        onMessageShown = viewModel::messageShown,
    )
}

@Composable
private fun FamilyInviteShareEffect(share: FamilyInviteShare?, onHandled: (FamilyInviteShare) -> Unit) {
    val context = LocalContext.current
    val template = stringResource(R.string.family_invite_text)
    val subject = stringResource(R.string.family_invite_subject)
    val chooser = stringResource(R.string.family_invite_chooser)
    val label = stringResource(R.string.family_code_label)
    LaunchedEffect(share) {
        if (share == null) return@LaunchedEffect
        val code = formatInviteCode(share.code)
        if (share.copyOnly) {
            copyText(context, label, code, R.string.family_code_copied)
        } else if (!shareFlightText(context, template.replace(CODE_PLACEHOLDER, code), subject, chooser, ShareTarget.WHATSAPP_FIRST)) {
            Toast.makeText(context, R.string.share_message_no_app, Toast.LENGTH_LONG).show()
        }
        onHandled(share)
    }
}

/** The invite text carries the code in place of this token (the template is a translatable string). */
private const val CODE_PLACEHOLDER = "{code}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyScreen(
    state: FamilyUiState,
    pro: ProState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onShareInvite: () -> Unit,
    onCopyInvite: () -> Unit,
    onCodeInputChange: (String) -> Unit,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onRemove: (userId: String) -> Unit,
    onMessageShown: (FamilyMessage) -> Unit,
) {
    val snackbars = remember { SnackbarHostState() }
    val messageText = state.message?.let { familyMessageText(it) }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        messageText?.let { snackbars.showSnackbar(it) }
        onMessageShown(message)
    }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { FlyTrackTopBar(title = stringResource(R.string.family_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (state.load) {
            FamilyLoad.LOADING -> LoadingState(modifier)
            FamilyLoad.NOT_CONFIGURED -> BackendNotConfiguredState(modifier)
            FamilyLoad.OFFLINE -> OfflineState(modifier, onRetry = onRefresh)
            FamilyLoad.FAILED -> ErrorState(
                title = stringResource(R.string.family_load_failed),
                modifier = modifier,
                retryLabel = stringResource(R.string.action_retry),
                onRetry = onRefresh,
            )
            FamilyLoad.LOADED -> PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh, modifier = modifier) {
                val family = state.family
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                    if (family == null) {
                        item(key = "start") { StartCard(state, pro, onShareInvite) }
                        item(key = "join") { JoinCard(state, onCodeInputChange, onJoin) }
                    } else {
                        item(key = "summary") { SummaryCard(family, state, onShareInvite, onCopyInvite) }
                        item(key = "members-header") {
                            Spacer(Modifier.height(24.dp))
                            SectionCaption(stringResource(R.string.family_section_members))
                            Spacer(Modifier.height(4.dp))
                            MemberRow(
                                name = ownerLabel(family),
                                detail = stringResource(R.string.family_role_owner),
                                onRemove = null,
                                busy = false,
                            )
                            HairlineDivider()
                            if (family.members.isEmpty()) {
                                Text(
                                    stringResource(R.string.family_members_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            }
                        }
                        items(family.members, key = { it.userId }) { member ->
                            MemberRow(
                                name = member.displayName ?: stringResource(R.string.family_member_fallback),
                                detail = member.joinedAt?.let {
                                    stringResource(R.string.family_member_since, FlightTimeFormat.fullDate(FlightTimeFormat.localDate(it, null)))
                                },
                                onRemove = if (family.isOwner) ({ confirmRemove = member.userId }) else null,
                                busy = state.removingUserId == member.userId,
                            )
                            HairlineDivider()
                        }
                        item(key = "leave") {
                            Spacer(Modifier.height(20.dp))
                            SecondaryButton(
                                text = stringResource(if (family.isOwner) R.string.family_action_end else R.string.family_action_leave),
                                onClick = { confirmLeave = true },
                                enabled = !state.isBusy,
                                loading = state.running == FamilyAction.LEAVE,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    item(key = "footer") {
                        Text(
                            stringResource(R.string.family_privacy),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmLeave) {
        val owner = state.family?.isOwner == true
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(if (owner) R.string.family_end_title else R.string.family_leave_title)) },
            text = { Text(stringResource(if (owner) R.string.family_end_message else R.string.family_leave_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onLeave()
                }) { Text(stringResource(if (owner) R.string.family_action_end else R.string.family_action_leave)) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    val removing = confirmRemove?.let { id -> state.family?.members?.firstOrNull { it.userId == id } }
    if (removing != null) {
        val name = removing.displayName ?: stringResource(R.string.family_member_fallback)
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.family_remove_title, name)) },
            text = { Text(stringResource(R.string.family_remove_message, name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    onRemove(removing.userId)
                }) { Text(stringResource(R.string.family_action_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Not in a family: start one (own Pro) or learn how. */
@Composable
private fun StartCard(state: FamilyUiState, pro: ProState, onShareInvite: () -> Unit) {
    val ownPro = pro.isPro && pro.source != ProSource.FAMILY
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.family_start_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                pluralStringResource(R.plurals.family_start_message, DEFAULT_MAX_MEMBERS, DEFAULT_MAX_MEMBERS),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (ownPro) {
                PrimaryButton(
                    text = stringResource(R.string.family_action_invite),
                    onClick = onShareInvite,
                    loading = state.running == FamilyAction.CREATE_INVITE,
                    enabled = !state.isBusy,
                    icon = painterResource(R.drawable.ic_share),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.family_start_yearly_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text(stringResource(R.string.family_start_needs_pro), style = MaterialTheme.typography.bodyMedium)
                // P3 B12: crew mode hides every upsell.
                if (!LocalUpsellsHidden.current) {
                    val paywall = LocalPaywallLauncher.current
                    TextButton(onClick = paywall::openPaywall) { Text(stringResource(R.string.billing_action_see_plans)) }
                }
            }
        }
    }
}

@Composable
private fun JoinCard(state: FamilyUiState, onCodeInputChange: (String) -> Unit, onJoin: () -> Unit) {
    val errorText = if (state.codeInvalid) stringResource(R.string.family_code_invalid) else null
    Column(Modifier.padding(top = 24.dp)) {
        SectionCaption(stringResource(R.string.family_join_title))
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.family_join_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.codeInput,
            onValueChange = onCodeInputChange,
            modifier = Modifier.fillMaxWidth().semantics { if (errorText != null) error(errorText) },
            label = { Text(stringResource(R.string.family_code_label)) },
            supportingText = { Text(errorText ?: stringResource(R.string.family_code_hint)) },
            isError = errorText != null,
            singleLine = true,
            textStyle = FlyTrackTheme.type.numeralSmall,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onJoin() }),
            shape = MaterialTheme.shapes.small,
        )
        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            text = stringResource(R.string.family_action_join),
            onClick = onJoin,
            enabled = !state.isBusy && state.codeInput.isNotBlank(),
            loading = state.running == FamilyAction.JOIN,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** In a family: whose Pro it is, and (owner) the invite to hand out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(family: FamilyInfo, state: FamilyUiState, onShareInvite: () -> Unit, onCopyInvite: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            when {
                family.isOwner -> Text(stringResource(R.string.family_owner_headline), style = MaterialTheme.typography.titleMedium)
                family.proViaFamily -> Text(familyProText(family.ownerName), style = FlyTrackTheme.type.statusWordSmall)
                family.ownerPro -> Text(stringResource(R.string.family_member_own_pro), style = MaterialTheme.typography.titleMedium)
                else -> Text(stringResource(R.string.family_member_inactive, ownerLabel(family)), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                pluralStringResource(R.plurals.family_seats, family.members.size, family.members.size, family.maxMembers),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (family.isOwner) {
                if (!family.ownerPro) {
                    Text(
                        stringResource(R.string.family_owner_inactive),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                family.invite?.let { invite ->
                    Spacer(Modifier.height(12.dp))
                    SectionCaption(stringResource(R.string.family_code_label))
                    Text(
                        formatInviteCode(invite.code),
                        style = FlyTrackTheme.type.numeralSmall,
                        modifier = Modifier.semantics { contentDescription = invite.code.toCharArray().joinToString(" ") },
                    )
                    invite.expiresAt?.let {
                        Text(
                            stringResource(R.string.family_code_expires, FlightTimeFormat.fullDate(FlightTimeFormat.localDate(it, null))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (family.canInvite || family.invite != null) {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(
                            text = stringResource(R.string.family_action_invite),
                            onClick = onShareInvite,
                            loading = state.running == FamilyAction.CREATE_INVITE,
                            enabled = !state.isBusy,
                            icon = painterResource(R.drawable.ic_share),
                        )
                        SecondaryButton(
                            text = stringResource(R.string.family_action_copy),
                            onClick = onCopyInvite,
                            enabled = !state.isBusy,
                        )
                    }
                } else if (family.seatsLeft == 0) {
                    Text(
                        stringResource(R.string.family_full),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberRow(name: String, detail: String?, onRemove: (() -> Unit)?, busy: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onRemove != null) {
            IconAction(
                icon = painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.family_remove_title, name),
                onClick = onRemove,
                enabled = !busy,
            )
        }
    }
}

@Composable
private fun ownerLabel(family: FamilyInfo): String =
    family.ownerName ?: stringResource(if (family.isOwner) R.string.family_owner_you else R.string.family_owner_fallback)

@Composable
private fun familyMessageText(message: FamilyMessage): String = when (message) {
    is FamilyMessage.Joined -> familyProText(message.ownerName)
    FamilyMessage.Left -> stringResource(R.string.family_message_left)
    FamilyMessage.Ended -> stringResource(R.string.family_message_ended)
    FamilyMessage.Removed -> stringResource(R.string.family_message_removed)
    FamilyMessage.Offline -> stringResource(R.string.family_message_offline)
    FamilyMessage.Failed -> stringResource(R.string.family_message_failed)
    is FamilyMessage.Refused -> stringResource(
        when (message.error) {
            FamilyError.REQUIRES_YEARLY -> R.string.family_error_requires_yearly
            FamilyError.ALREADY_IN_FAMILY -> R.string.family_error_already
            FamilyError.INVALID_CODE -> R.string.family_error_invalid_code
            FamilyError.OWN_CODE -> R.string.family_error_own_code
            FamilyError.FULL -> R.string.family_error_full
            FamilyError.INACTIVE -> R.string.family_error_inactive
            FamilyError.NOT_MEMBER -> R.string.family_error_not_member
            FamilyError.NOT_OWNER -> R.string.family_error_not_owner
        },
    )
}
