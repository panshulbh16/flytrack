package app.flytrack.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.core.model.AlertType
import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendConfig
import app.flytrack.data.repo.Appearance
import app.flytrack.data.repo.AuthRepository
import app.flytrack.data.repo.ProfileRepository
import app.flytrack.data.repo.SessionState
import app.flytrack.data.repo.SettingsStore
import app.flytrack.feature.flights.presentation.AlertGroup
import app.flytrack.feature.flights.presentation.AlertToggleUiModel
import app.flytrack.push.NotificationHealth
import app.flytrack.push.NotificationHealthSource
import app.flytrack.push.PushAvailability
import app.flytrack.push.PushInitializer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Clock

data class SettingsUiState(
    val account: AccountUi = AccountUi.Loading,
    val linking: Boolean = false,
    val googleConfigured: Boolean = false,
    val alerts: List<AlertToggleUiModel> = emptyList(),
    val cloudConsent: Boolean = false,
    val health: NotificationHealth? = null,
    val push: PushAvailability = PushAvailability.Checking,
    val versionName: String = "",
    val message: SettingsMessage? = null,
    /** Held so "this Google account already has a FlyTrack user" can offer a real sign-in. */
    val pendingGoogleToken: String? = null,
)

sealed interface AccountUi {
    data object Loading : AccountUi
    data object NotConfigured : AccountUi
    data object SignedOut : AccountUi
    data class Anonymous(val userId: String) : AccountUi
    data class Google(val email: String?) : AccountUi
}

sealed interface SettingsMessage {
    data object NoGoogleCredential : SettingsMessage
    data object GoogleNotConfigured : SettingsMessage
    data object GoogleInUse : SettingsMessage
    data object Linked : SettingsMessage
    data object Offline : SettingsMessage
    data object Failed : SettingsMessage
    data object SignedOut : SettingsMessage
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val profiles: ProfileRepository,
    private val settings: SettingsStore,
    private val defaultAlerts: DefaultAlertPreferences,
    private val healthSource: NotificationHealthSource,
    private val push: PushInitializer,
    private val config: BackendConfig,
    private val clock: Clock,
    version: AppVersion,
) : ViewModel() {

    private val health = MutableStateFlow(healthSource.read())
    private val linking = MutableStateFlow(false)
    private val message = MutableStateFlow<SettingsMessage?>(null)
    private val pendingToken = MutableStateFlow<String?>(null)

    private data class Stored(
        val session: SessionState,
        val profile: app.flytrack.core.model.UserProfile?,
        val consent: Boolean,
        val muted: Set<AlertType>,
    )

    private data class Transient(
        val health: NotificationHealth,
        val push: PushAvailability,
        val linking: Boolean,
        val message: SettingsMessage?,
        val token: String?,
    )

    private val stored = combine(
        auth.session,
        profiles.observeProfile(),
        settings.cloudTicketParsingConsent,
        defaultAlerts.muted,
    ) { session, profile, consent, muted -> Stored(session, profile, consent, muted) }

    private val transient = combine(health, push.availability, linking, message, pendingToken) { healthNow, pushNow, isLinking, msg, token ->
        Transient(healthNow, pushNow, isLinking, msg, token)
    }

    val uiState: StateFlow<SettingsUiState> = combine(stored, transient) { storedNow, transientNow ->
        val isPro = storedNow.profile?.isPro(clock.now()) == true
        SettingsUiState(
            account = storedNow.session.toAccount(),
            linking = transientNow.linking,
            googleConfigured = config.isGoogleSignInConfigured,
            alerts = alertToggles(storedNow.muted, isPro),
            cloudConsent = storedNow.consent,
            health = transientNow.health,
            push = transientNow.push,
            versionName = version.name,
            message = transientNow.message,
            pendingGoogleToken = transientNow.token,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState(versionName = version.name))

    init {
        viewModelScope.launch { profiles.refresh() }
    }

    fun refreshHealth() {
        health.value = healthSource.read()
    }

    /** False when Google sign-in is not configured: the caller must not open Credential Manager. */
    fun beginGoogleLink(): Boolean {
        if (!config.isGoogleSignInConfigured) {
            message.value = SettingsMessage.GoogleNotConfigured
            return false
        }
        if (linking.value) return false
        linking.value = true
        message.value = null
        return true
    }

    fun onGoogleAttempt(attempt: GoogleSignInAttempt) {
        when (attempt) {
            GoogleSignInAttempt.Cancelled -> linking.value = false
            GoogleSignInAttempt.NoCredential -> finishLink(SettingsMessage.NoGoogleCredential, token = null)
            GoogleSignInAttempt.NotConfigured -> finishLink(SettingsMessage.GoogleNotConfigured, token = null)
            is GoogleSignInAttempt.Failed -> finishLink(SettingsMessage.Failed, token = null)
            is GoogleSignInAttempt.Token -> viewModelScope.launch { link(attempt.idToken) }
        }
    }

    fun signInWithPendingGoogle() {
        val token = pendingToken.value ?: return
        viewModelScope.launch {
            linking.value = true
            // No push.beforeSignOut() here: if the switch fails, the phone must stay on this account. If it
            // succeeds, the push initializer sees the new user id and register_device moves the device row.
            finishLink(auth.signInWithGoogle(token).toMessage(SettingsMessage.Linked), token = null)
        }
    }

    fun signOut() {
        if (linking.value) return
        viewModelScope.launch {
            linking.value = true
            push.beforeSignOut()
            val result = auth.signOut()
            linking.value = false
            message.value = if (result.isSuccess) SettingsMessage.SignedOut else result.toMessage(SettingsMessage.SignedOut)
        }
    }

    fun setAlertGroup(group: AlertGroup, enabled: Boolean) {
        if (group.proOnly && uiState.value.alerts.firstOrNull { it.group == group }?.locked == true) return
        viewModelScope.launch { defaultAlerts.setMuted(group.types.toSet(), muted = !enabled) }
    }

    fun setCloudConsent(granted: Boolean) {
        viewModelScope.launch { settings.setCloudTicketParsingConsent(granted) }
    }

    val appearance: StateFlow<Appearance> =
        settings.appearance.stateIn(viewModelScope, SharingStarted.Eagerly, Appearance.SYSTEM)

    fun setAppearance(appearance: Appearance) {
        viewModelScope.launch { settings.setAppearance(appearance) }
    }

    fun messageShown() {
        message.value = null
    }

    fun openNotificationSettings() = healthSource.openNotificationSettings()

    fun openChannelSettings(channelId: String) = healthSource.openChannelSettings(channelId)

    fun openBatterySettings() = healthSource.openBatterySettings()

    private suspend fun link(idToken: String) {
        val result = auth.linkGoogle(idToken)
        val msg = result.toMessage(SettingsMessage.Linked)
        finishLink(msg, token = idToken.takeIf { result is AuthResult.GoogleAccountInUse })
    }

    private fun finishLink(msg: SettingsMessage?, token: String?) {
        linking.value = false
        pendingToken.value = token
        message.value = msg
    }
}

interface AppVersion {
    val name: String
}

class BuildAppVersion @Inject constructor() : AppVersion {
    override val name: String = app.flytrack.BuildConfig.VERSION_NAME
}

private fun SessionState.toAccount(): AccountUi = when (this) {
    SessionState.Loading -> AccountUi.Loading
    SessionState.NotConfigured -> AccountUi.NotConfigured
    SessionState.SignedOut -> AccountUi.SignedOut
    is SessionState.SignedIn -> if (isAnonymous) AccountUi.Anonymous(userId) else AccountUi.Google(email)
}

private fun AuthResult.toMessage(success: SettingsMessage): SettingsMessage = when (this) {
    AuthResult.Success -> success
    AuthResult.Offline -> SettingsMessage.Offline
    AuthResult.NotConfigured -> SettingsMessage.GoogleNotConfigured
    AuthResult.GoogleAccountInUse -> SettingsMessage.GoogleInUse
    is AuthResult.Failed -> SettingsMessage.Failed
}
