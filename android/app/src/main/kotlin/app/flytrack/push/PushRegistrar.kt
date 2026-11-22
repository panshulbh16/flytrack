package app.flytrack.push

import app.flytrack.core.result.ActionResult
import app.flytrack.data.repo.DeviceRegistration
import app.flytrack.data.repo.DeviceRepository
import app.flytrack.data.repo.SettingsStore
import kotlinx.coroutines.flow.first

/** Phone facts sent with `register_device`. Plain data so the registrar stays free of android.*. */
data class DeviceFacts(
    val appVersion: String?,
    val osVersion: String?,
    val locale: String?,
    val timezone: String?,
)

/**
 * Decides when to call `register_device` / `unregister_device`.
 *
 * The token is registered when it is new or when it belongs to a different user. It is removed
 * before sign-out, while the current user's token is still valid.
 */
class PushRegistrar(
    private val devices: DeviceRepository,
    private val settings: SettingsStore,
    private val facts: DeviceFacts,
    private val currentToken: suspend () -> String?,
) {
    /** True when the stored registration is already this token on this user. */
    fun alreadyRegistered(last: DeviceRegistration?, token: String, userId: String): Boolean =
        last?.fcmToken == token && last.userId == userId

    /** Registers [userId]'s token unless it is already registered. Does nothing without a token. */
    suspend fun syncForUser(userId: String): ActionResult? {
        val token = currentToken() ?: return null
        val last = settings.lastDeviceRegistration.first()
        if (alreadyRegistered(last, token, userId)) return ActionResult.Success
        return devices.registerDevice(token, facts.appVersion, facts.osVersion, facts.locale, facts.timezone)
    }

    /**
     * Drops this phone's token from the signed-in user. Call this before [app.flytrack.data.repo.AuthRepository.signOut],
     * not after: the RPC needs the user's session.
     */
    suspend fun beforeSignOut(): ActionResult? {
        val token = settings.lastDeviceRegistration.first()?.fcmToken ?: currentToken() ?: return null
        return devices.unregisterDevice(token)
    }
}

/** What a finished sync write should make WorkManager do. */
enum class SyncWorkOutcome { SUCCESS, RETRY, FAILURE }

/**
 * Offline and transient failures are retried a few times. A missing flight or an unconfigured
 * build is not worth waking the phone again.
 */
/**
 * @param attempt 1 for the first run. Offline and other transient results are retried until
 * [maxAttempts]; a missing flight or an unconfigured build is not retried.
 */
fun syncWorkOutcome(result: ActionResult, attempt: Int, maxAttempts: Int = 3): SyncWorkOutcome = when (result) {
    ActionResult.Success, ActionResult.NotFound, ActionResult.NotConfigured -> SyncWorkOutcome.SUCCESS
    ActionResult.Offline, ActionResult.TrackingLimitReached, is ActionResult.Failed, is ActionResult.InvalidInput ->
        if (attempt >= maxAttempts) SyncWorkOutcome.FAILURE else SyncWorkOutcome.RETRY
}
