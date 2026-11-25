package app.flytrack.feature.settings

import android.app.Activity
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.BuildConfig
import app.flytrack.R
import app.flytrack.core.push.PushContract
import app.flytrack.data.repo.Appearance
import app.flytrack.feature.flights.presentation.AlertGroup
import app.flytrack.feature.flights.presentation.AlertToggleUiModel
import app.flytrack.push.ChannelHealth
import app.flytrack.push.NotificationHealth
import app.flytrack.push.PermissionHealth
import app.flytrack.push.PushAvailability
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.SecondaryButton
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.nav.LocalAppNavigator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/** Entry point of the settings screen (account, notifications, privacy opt-ins). */
@Composable
fun SettingsRoute(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requester = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, SettingsEntryPoint::class.java).googleSignIn()
    }
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val navigator = LocalAppNavigator.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshHealth() }

    SettingsScreen(
        state = state,
        onBack = onBack,
        onLinkGoogle = {
            if (viewModel.beginGoogleLink() && activity != null) {
                scope.launch { viewModel.onGoogleAttempt(requester.request(activity)) }
            }
        },
        onSignInWithGoogle = viewModel::signInWithPendingGoogle,
        onSignOut = viewModel::signOut,
        onAlertGroup = viewModel::setAlertGroup,
        onCloudConsent = viewModel::setCloudConsent,
        onOpenNotifications = viewModel::openNotificationSettings,
        onOpenCriticalChannel = { viewModel.openChannelSettings(PushContract.CHANNEL_CRITICAL) },
        onOpenUpdatesChannel = { viewModel.openChannelSettings(PushContract.CHANNEL_UPDATES) },
        onOpenBattery = viewModel::openBatterySettings,
        onMessageShown = viewModel::messageShown,
        appearance = appearance,
        onAppearance = viewModel::setAppearance,
        onGmailAutoCheck = viewModel::setGmailAutoCheck,
        onReconnectGmail = navigator::openGmailImport,
        onDisconnectGmail = viewModel::disconnectGmail,
    )
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SettingsEntryPoint {
    fun googleSignIn(): GoogleIdTokenRequester
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onLinkGoogle: () -> Unit,
    onSignInWithGoogle: () -> Unit,
    onSignOut: () -> Unit,
    onAlertGroup: (AlertGroup, Boolean) -> Unit,
    onCloudConsent: (Boolean) -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenCriticalChannel: () -> Unit,
    onOpenUpdatesChannel: () -> Unit,
    onOpenBattery: () -> Unit,
    onMessageShown: () -> Unit,
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    onGmailAutoCheck: (Boolean) -> Unit,
    onReconnectGmail: () -> Unit,
    onDisconnectGmail: () -> Unit,
) {
    val snackbars = remember { SnackbarHostState() }
    val message = state.message?.let { settingsMessage(it) }
    LaunchedEffect(state.message) {
        val text = message ?: return@LaunchedEffect
        snackbars.showSnackbar(text)
        onMessageShown()
    }

    Scaffold(
        topBar = { FlyTrackTopBar(title = stringResource(R.string.settings_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            SectionCaption(stringResource(R.string.settings_section_account))
            Spacer(Modifier.height(8.dp))
            AccountSection(state, onLinkGoogle, onSignInWithGoogle, onSignOut)
            Spacer(Modifier.height(20.dp))
            HairlineDivider()
            Spacer(Modifier.height(16.dp))
            SectionCaption(stringResource(R.string.settings_section_appearance))
            Spacer(Modifier.height(8.dp))
            AppearancePicker(appearance, onAppearance)
            Spacer(Modifier.height(20.dp))
            HairlineDivider()
            Spacer(Modifier.height(16.dp))
            SectionCaption(stringResource(R.string.settings_section_alerts))
            Text(
                text = stringResource(R.string.settings_alerts_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            state.alerts.forEach { toggle ->
                AlertSwitch(toggle, onAlertGroup)
            }
            Spacer(Modifier.height(12.dp))
            HairlineDivider()
            Spacer(Modifier.height(16.dp))
            SectionCaption(stringResource(R.string.settings_section_tickets))
            LabeledSwitch(
                title = stringResource(R.string.settings_cloud_title),
                message = stringResource(R.string.settings_cloud_message),
                checked = state.cloudConsent,
                onChange = onCloudConsent,
            )
            Spacer(Modifier.height(12.dp))
            HairlineDivider()
            Spacer(Modifier.height(16.dp))
            val gmail = state.gmail
            // Hidden while the release switch is off, and until Gmail is connected.
            if (BuildConfig.GMAIL_IMPORT_ENABLED && gmail != null) {
                SectionCaption(stringResource(R.string.settings_section_gmail))
                GmailSection(gmail, onGmailAutoCheck, onReconnectGmail, onDisconnectGmail)
                Spacer(Modifier.height(12.dp))
                HairlineDivider()
                Spacer(Modifier.height(16.dp))
            }
            SectionCaption(stringResource(R.string.settings_section_notifications))
            NotificationSection(state, onOpenNotifications, onOpenCriticalChannel, onOpenUpdatesChannel, onOpenBattery)
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.settings_version, state.versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AppearancePicker(selected: Appearance, onSelect: (Appearance) -> Unit) {
    val options = listOf(
        Appearance.SYSTEM to R.string.settings_appearance_system,
        Appearance.LIGHT to R.string.settings_appearance_light,
        Appearance.DARK to R.string.settings_appearance_dark,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(stringResource(label)) }
        }
    }
}

@Composable
private fun AccountSection(
    state: SettingsUiState,
    onLinkGoogle: () -> Unit,
    onSignInWithGoogle: () -> Unit,
    onSignOut: () -> Unit,
) {
    Text(
        text = accountText(state.account),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(vertical = 8.dp),
    )
    val anonymous = state.account is AccountUi.Anonymous || state.account is AccountUi.SignedOut
    if (anonymous) {
        PrimaryButton(
            text = stringResource(R.string.settings_action_link_google),
            onClick = onLinkGoogle,
            enabled = state.googleConfigured && !state.linking,
            loading = state.linking,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!state.googleConfigured) {
            Text(
                text = stringResource(R.string.settings_google_not_configured),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    if (state.message == SettingsMessage.GoogleInUse && state.pendingGoogleToken != null) {
        Text(
            text = stringResource(R.string.settings_google_in_use),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 12.dp),
        )
        Spacer(Modifier.height(8.dp))
        SecondaryButton(
            text = stringResource(R.string.settings_action_sign_in_google),
            onClick = onSignInWithGoogle,
            enabled = !state.linking,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (state.account is AccountUi.Google || state.account is AccountUi.Anonymous) {
        Spacer(Modifier.height(8.dp))
        SecondaryButton(
            text = stringResource(R.string.settings_action_sign_out),
            onClick = onSignOut,
            enabled = !state.linking,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AlertSwitch(toggle: AlertToggleUiModel, onAlertGroup: (AlertGroup, Boolean) -> Unit) {
    val title = alertTitle(toggle.group)
    val description = stringResource(R.string.settings_alert_switch, title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (toggle.locked) {
                Text(
                    stringResource(R.string.settings_alert_inbound_locked),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Switch(
            checked = toggle.enabled && !toggle.locked,
            onCheckedChange = { onAlertGroup(toggle.group, it) },
            enabled = !toggle.locked,
        )
    }
}

@Composable
private fun LabeledSwitch(title: String, message: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

@Composable
private fun GmailSection(
    gmail: GmailSettingsUi,
    onAutoCheck: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Text(
        text = stringResource(R.string.settings_gmail_connected, gmail.email),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(vertical = 8.dp),
    )
    if (gmail.needsReconnect) {
        Text(
            text = stringResource(R.string.settings_gmail_needs_reconnect),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        PrimaryButton(
            text = stringResource(R.string.settings_gmail_reconnect),
            onClick = onReconnect,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    LabeledSwitch(
        title = stringResource(R.string.settings_gmail_auto_check),
        message = stringResource(R.string.settings_gmail_auto_check_message),
        checked = gmail.autoCheck,
        onChange = onAutoCheck,
    )
    Spacer(Modifier.height(8.dp))
    SecondaryButton(
        text = stringResource(R.string.settings_gmail_disconnect),
        onClick = onDisconnect,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NotificationSection(
    state: SettingsUiState,
    onOpenNotifications: () -> Unit,
    onOpenCriticalChannel: () -> Unit,
    onOpenUpdatesChannel: () -> Unit,
    onOpenBattery: () -> Unit,
) {
    if (state.push == PushAvailability.NotConfigured) {
        Text(
            text = stringResource(R.string.settings_push_not_configured),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
    val health = state.health ?: return
    HealthRow(
        title = stringResource(R.string.settings_health_permission),
        value = permissionText(health),
        onClick = onOpenNotifications,
    )
    HealthRow(
        title = stringResource(R.string.settings_health_channel_critical),
        value = channelText(health.criticalChannel),
        onClick = onOpenCriticalChannel,
    )
    HealthRow(
        title = stringResource(R.string.settings_health_channel_updates),
        value = channelText(health.updatesChannel),
        onClick = onOpenUpdatesChannel,
    )
    HealthRow(
        title = stringResource(R.string.settings_health_battery),
        value = stringResource(
            if (health.batteryUnrestricted) R.string.settings_health_battery_ok else R.string.settings_health_battery_restricted,
        ),
        onClick = onOpenBattery,
    )
}

@Composable
private fun HealthRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onClick, onClickLabel = stringResource(R.string.settings_health_open))
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun accountText(account: AccountUi): String = when (account) {
    AccountUi.Loading -> stringResource(R.string.settings_account_loading)
    AccountUi.NotConfigured -> stringResource(R.string.settings_account_not_configured)
    AccountUi.SignedOut -> stringResource(R.string.settings_account_signed_out)
    is AccountUi.Anonymous -> stringResource(R.string.settings_account_anonymous)
    is AccountUi.Google -> account.email?.let { stringResource(R.string.settings_account_google_email, it) }
        ?: stringResource(R.string.settings_account_google)
}

@Composable
private fun alertTitle(group: AlertGroup): String = stringResource(
    when (group) {
        AlertGroup.DELAYS -> R.string.settings_alert_delays
        AlertGroup.GATE -> R.string.settings_alert_gate
        AlertGroup.BOARDING -> R.string.settings_alert_boarding
        AlertGroup.TAKEOFF_AND_LANDING -> R.string.settings_alert_movement
        AlertGroup.BAGGAGE -> R.string.settings_alert_baggage
        AlertGroup.DISRUPTION -> R.string.settings_alert_disruption
        AlertGroup.INBOUND_AIRCRAFT -> R.string.settings_alert_inbound
    },
)

@Composable
private fun permissionText(health: NotificationHealth): String = stringResource(
    when (health.permission) {
        PermissionHealth.GRANTED, PermissionHealth.NOT_REQUIRED -> R.string.settings_health_on
        PermissionHealth.DENIED, PermissionHealth.NOT_REQUIRED_BUT_BLOCKED -> R.string.settings_health_off
    },
)

@Composable
private fun channelText(health: ChannelHealth): String = stringResource(
    when (health) {
        ChannelHealth.OK -> R.string.settings_health_on
        ChannelHealth.BLOCKED -> R.string.settings_health_off
        ChannelHealth.MISSING -> R.string.settings_health_missing
    },
)

@Composable
private fun settingsMessage(message: SettingsMessage): String? = when (message) {
    SettingsMessage.NoGoogleCredential -> stringResource(R.string.settings_google_no_credential)
    SettingsMessage.GoogleNotConfigured -> stringResource(R.string.settings_google_not_configured)
    SettingsMessage.GoogleInUse -> null
    SettingsMessage.Linked -> stringResource(R.string.settings_google_linked)
    SettingsMessage.Offline -> stringResource(R.string.settings_offline)
    SettingsMessage.Failed -> stringResource(R.string.settings_failed)
    SettingsMessage.SignedOut -> stringResource(R.string.settings_signed_out)
}
