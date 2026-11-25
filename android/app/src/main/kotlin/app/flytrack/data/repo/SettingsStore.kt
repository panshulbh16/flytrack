package app.flytrack.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

/** Small on-device preferences (DataStore). Nothing here is synced to the backend. */
interface SettingsStore {
    /**
     * The user allowed sending extracted ticket TEXT to the `parse-ticket` Edge Function when the
     * on-device parser fails. Off by default; never send anything without it.
     */
    val cloudTicketParsingConsent: Flow<Boolean>
    suspend fun setCloudTicketParsingConsent(granted: Boolean)

    /** The POST_NOTIFICATIONS rationale/request was already shown once, so it is not shown at every start. */
    val notificationPermissionAsked: Flow<Boolean>
    suspend fun setNotificationPermissionAsked(asked: Boolean)

    /** The FCM token and user id last accepted by `register_device`; null before the first registration. */
    val lastDeviceRegistration: Flow<DeviceRegistration?>
    suspend fun setLastDeviceRegistration(registration: DeviceRegistration?)

    /** Light, dark, or follow the phone (the default). */
    val appearance: Flow<Appearance>
    suspend fun setAppearance(appearance: Appearance)

    /** The Gmail address the user connected for ticket import; null when Gmail is not connected. */
    val gmailAccountEmail: Flow<String?>
    suspend fun setGmailAccountEmail(email: String?)

    /** Check Gmail for new bookings in the background. On by default once Gmail is connected. */
    val gmailAutoCheck: Flow<Boolean>
    suspend fun setGmailAutoCheck(enabled: Boolean)

    /** Start time (epoch seconds) of the last scan that completed; null until one has. */
    val gmailLastCheckEpochSeconds: Flow<Long?>
    suspend fun setGmailLastCheckEpochSeconds(value: Long?)

    /** Gmail access was lost (revoked, scope missing); background checks stop until reconnected. */
    val gmailNeedsReconnect: Flow<Boolean>
    suspend fun setGmailNeedsReconnect(value: Boolean)

    /** When the app-open check last ran (epoch seconds), for its once-an-hour throttle. */
    val gmailLastAppOpenCheckEpochSeconds: Flow<Long?>
    suspend fun setGmailLastAppOpenCheckEpochSeconds(value: Long?)
}

data class DeviceRegistration(val fcmToken: String, val userId: String)

enum class Appearance { SYSTEM, LIGHT, DARK }

class DataStoreSettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {

    // A corrupt or unreadable file must not crash the app: fall back to defaults.
    private val preferences: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    override val cloudTicketParsingConsent: Flow<Boolean> =
        preferences.map { it[CLOUD_TICKET_CONSENT] ?: false }.distinctUntilChanged()

    override suspend fun setCloudTicketParsingConsent(granted: Boolean) {
        dataStore.edit { it[CLOUD_TICKET_CONSENT] = granted }
    }

    override val notificationPermissionAsked: Flow<Boolean> =
        preferences.map { it[NOTIFICATION_PERMISSION_ASKED] ?: false }.distinctUntilChanged()

    override suspend fun setNotificationPermissionAsked(asked: Boolean) {
        dataStore.edit { it[NOTIFICATION_PERMISSION_ASKED] = asked }
    }

    override val lastDeviceRegistration: Flow<DeviceRegistration?> = preferences.map { prefs ->
        val token = prefs[FCM_TOKEN]
        val userId = prefs[FCM_USER_ID]
        if (token != null && userId != null) DeviceRegistration(token, userId) else null
    }.distinctUntilChanged()

    override suspend fun setLastDeviceRegistration(registration: DeviceRegistration?) {
        dataStore.edit { prefs ->
            if (registration == null) {
                prefs.remove(FCM_TOKEN)
                prefs.remove(FCM_USER_ID)
            } else {
                prefs[FCM_TOKEN] = registration.fcmToken
                prefs[FCM_USER_ID] = registration.userId
            }
        }
    }

    // An unknown stored name (from a future version) falls back to following the phone.
    override val appearance: Flow<Appearance> = preferences.map { prefs ->
        Appearance.entries.firstOrNull { it.name == prefs[APPEARANCE] } ?: Appearance.SYSTEM
    }.distinctUntilChanged()

    override suspend fun setAppearance(appearance: Appearance) {
        dataStore.edit { it[APPEARANCE] = appearance.name }
    }

    override val gmailAccountEmail: Flow<String?> =
        preferences.map { it[GMAIL_ACCOUNT_EMAIL] }.distinctUntilChanged()

    override suspend fun setGmailAccountEmail(email: String?) = put(GMAIL_ACCOUNT_EMAIL, email)

    override val gmailAutoCheck: Flow<Boolean> =
        preferences.map { it[GMAIL_AUTO_CHECK] ?: true }.distinctUntilChanged()

    override suspend fun setGmailAutoCheck(enabled: Boolean) = put(GMAIL_AUTO_CHECK, enabled)

    override val gmailLastCheckEpochSeconds: Flow<Long?> =
        preferences.map { it[GMAIL_LAST_CHECK] }.distinctUntilChanged()

    override suspend fun setGmailLastCheckEpochSeconds(value: Long?) = put(GMAIL_LAST_CHECK, value)

    override val gmailNeedsReconnect: Flow<Boolean> =
        preferences.map { it[GMAIL_NEEDS_RECONNECT] ?: false }.distinctUntilChanged()

    override suspend fun setGmailNeedsReconnect(value: Boolean) = put(GMAIL_NEEDS_RECONNECT, value)

    override val gmailLastAppOpenCheckEpochSeconds: Flow<Long?> =
        preferences.map { it[GMAIL_LAST_APP_OPEN_CHECK] }.distinctUntilChanged()

    override suspend fun setGmailLastAppOpenCheckEpochSeconds(value: Long?) = put(GMAIL_LAST_APP_OPEN_CHECK, value)

    /** Writes [value], or removes the key when it is null. */
    private suspend fun <T : Any> put(key: Preferences.Key<T>, value: T?) {
        dataStore.edit { prefs ->
            if (value == null) {
                prefs.remove(key)
            } else {
                prefs[key] = value
            }
        }
    }

    private companion object {
        val APPEARANCE = stringPreferencesKey("appearance")
        val GMAIL_ACCOUNT_EMAIL = stringPreferencesKey("gmail_account_email")
        val GMAIL_AUTO_CHECK = booleanPreferencesKey("gmail_auto_check")
        val GMAIL_LAST_CHECK = longPreferencesKey("gmail_last_check_epoch_seconds")
        val GMAIL_NEEDS_RECONNECT = booleanPreferencesKey("gmail_needs_reconnect")
        val GMAIL_LAST_APP_OPEN_CHECK = longPreferencesKey("gmail_last_app_open_check_epoch_seconds")
        val CLOUD_TICKET_CONSENT = booleanPreferencesKey("cloud_ticket_parsing_consent")
        val NOTIFICATION_PERMISSION_ASKED = booleanPreferencesKey("notification_permission_asked")
        val FCM_TOKEN = stringPreferencesKey("last_registered_fcm_token")
        val FCM_USER_ID = stringPreferencesKey("last_registered_fcm_user_id")
    }
}
