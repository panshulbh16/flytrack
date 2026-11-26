package app.flytrack.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.TravelApi
import app.flytrack.data.repo.AuthRepository
import app.flytrack.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AlertPrefsResult {
    data class Loaded(val prefs: AlertPrefs) : AlertPrefsResult
    data object Offline : AlertPrefsResult
    data object NotConfigured : AlertPrefsResult

    /** [message] is technical English: log it, do not show it. */
    data class Failed(val message: String?) : AlertPrefsResult
}

/** The phone's language and time zone (injectable so tests can pin them). */
interface DeviceLocale {
    /** ISO 639 language code, e.g. "hi", "en". */
    val language: String

    /** IANA zone id, e.g. "Asia/Kolkata". */
    val timeZone: String
}

class SystemDeviceLocale @Inject constructor() : DeviceLocale {
    override val language: String get() = Locale.getDefault().language
    override val timeZone: String get() = TimeZone.getDefault().id
}

/** Quiet hours and alert language, stored in the user's `profiles` row (P2 contract, sections 3 and 4). */
interface AlertPrefsRepository {
    /** The last known settings; null until the first successful [refresh]. */
    val prefs: StateFlow<AlertPrefs?>

    suspend fun refresh(): AlertPrefsResult

    /** Shows [change] at once, writes it, and goes back to the server's row when the write fails. Never throws. */
    suspend fun update(change: AlertPrefsChange): AlertPrefsResult

    /**
     * Once per user and phone: a Hindi phone whose account still has the server default (`en`) gets `hi`.
     * Safe to call on every sign-in; never throws.
     */
    suspend fun applyDefaultLanguageOnce(userId: String)
}

@Singleton
class SupabaseAlertPrefsRepository @Inject constructor(
    private val api: TravelApi,
    private val auth: AuthRepository,
    private val dataStore: DataStore<Preferences>,
    private val device: DeviceLocale,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AlertPrefsRepository {

    private val state = MutableStateFlow<AlertPrefs?>(null)
    override val prefs: StateFlow<AlertPrefs?> = state

    /** Writes go one at a time so a fast double tap cannot reorder them. */
    private val writes = Mutex()

    override suspend fun refresh(): AlertPrefsResult = withContext(io) {
        when (val signed = signedIn()) {
            is Signed.User -> load(signed.id)
            is Signed.Not -> signed.result
        }
    }

    override suspend fun update(change: AlertPrefsChange): AlertPrefsResult = withContext(io) {
        writes.withLock {
            val before = state.value
            before?.let { state.value = it.apply(change, device.timeZone) }
            val userId = when (val signed = signedIn()) {
                is Signed.User -> signed.id
                is Signed.Not -> {
                    state.value = before
                    return@withLock signed.result
                }
            }
            try {
                val row = api.updateAlertPrefs(userId, alertPrefsPatch(change, device.timeZone))
                if (row == null) {
                    state.value = before
                    AlertPrefsResult.Failed("profile row missing")
                } else {
                    row.toAlertPrefs().also { state.value = it }.let { AlertPrefsResult.Loaded(it) }
                }
            } catch (e: BackendException) {
                state.value = before
                e.toResult()
            }
        }
    }

    override suspend fun applyDefaultLanguageOnce(userId: String) {
        withContext(io) {
            val key = booleanPreferencesKey("$DEFAULT_APPLIED_PREFIX$userId")
            val done = try {
                dataStore.data.first()[key] == true
            } catch (_: IOException) {
                return@withContext
            }
            if (done) return@withContext
            val wanted = defaultAlertLanguage(device.language)
            try {
                val row = api.alertPrefs(userId) ?: return@withContext
                val current = AlertLanguage.fromWire(row.alertLanguage)
                // Only replace the untouched server default; a choice made on another phone wins.
                if (wanted != AlertLanguage.EN && current == AlertLanguage.EN) {
                    api.updateAlertPrefs(userId, alertPrefsPatch(AlertPrefsChange.Language(wanted), device.timeZone))
                        ?.let { state.value = it.toAlertPrefs() }
                }
                dataStore.edit { it[key] = true }
            } catch (_: BackendException) {
                // Not marked: the next sign-in tries again.
            } catch (_: IOException) {
                // DataStore write failed: harmless, the check runs again next time.
            }
        }
    }

    private suspend fun load(userId: String): AlertPrefsResult = try {
        val row = api.alertPrefs(userId)
        if (row == null) {
            AlertPrefsResult.Failed("profile row missing")
        } else {
            row.toAlertPrefs().also { state.value = it }.let { AlertPrefsResult.Loaded(it) }
        }
    } catch (e: BackendException) {
        e.toResult()
    }

    private sealed interface Signed {
        data class User(val id: String) : Signed
        data class Not(val result: AlertPrefsResult) : Signed
    }

    /** The signed-in user id, or the result to return instead. */
    private suspend fun signedIn(): Signed = when (val signedIn = auth.ensureSignedIn()) {
        AuthResult.Success, AuthResult.GoogleAccountInUse ->
            auth.currentUserId?.let { Signed.User(it) } ?: Signed.Not(AlertPrefsResult.Failed("no user id after sign-in"))
        AuthResult.NotConfigured -> Signed.Not(AlertPrefsResult.NotConfigured)
        AuthResult.Offline -> Signed.Not(AlertPrefsResult.Offline)
        is AuthResult.Failed -> Signed.Not(AlertPrefsResult.Failed(signedIn.message))
    }

    private fun BackendException.toResult(): AlertPrefsResult = when (this) {
        is BackendException.NotConfigured -> AlertPrefsResult.NotConfigured
        is BackendException.Network -> AlertPrefsResult.Offline
        is BackendException.Malformed -> AlertPrefsResult.Failed(message)
        is BackendException.Http -> AlertPrefsResult.Failed(message)
    }

    private companion object {
        const val DEFAULT_APPLIED_PREFIX = "alert_language_default_applied_"
    }
}
