package app.flytrack.data.repo

import app.flytrack.core.model.Airport
import app.flytrack.core.model.UserProfile
import app.flytrack.core.result.ActionResult
import app.flytrack.core.result.AuthResult
import app.flytrack.data.local.AirportDao
import app.flytrack.data.local.FlightDao
import app.flytrack.data.local.ProfileDao
import app.flytrack.data.local.TrackedFlightDao
import app.flytrack.data.local.TransactionRunner
import app.flytrack.data.remote.BackendApi
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.dto.RegisterDeviceParams
import app.flytrack.data.remote.toDomain
import app.flytrack.data.remote.toEntity
import app.flytrack.di.IoDispatcher
import app.flytrack.feature.gmailimport.GmailConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

// ---- shared error mapping -------------------------------------------------------------------------

internal fun BackendException.toActionResult(): ActionResult = when (this) {
    is BackendException.NotConfigured -> ActionResult.NotConfigured
    is BackendException.Network -> ActionResult.Offline
    is BackendException.Malformed -> ActionResult.Failed(message)
    is BackendException.Http -> ActionResult.Failed(message)
}

internal fun AuthResult.toActionResult(): ActionResult = when (this) {
    AuthResult.Success, AuthResult.GoogleAccountInUse -> ActionResult.Success
    AuthResult.NotConfigured -> ActionResult.NotConfigured
    AuthResult.Offline -> ActionResult.Offline
    is AuthResult.Failed -> ActionResult.Failed(message)
}

// ---- devices (FCM tokens) -------------------------------------------------------------------------

interface DeviceRepository {
    /**
     * Registers or refreshes this phone's FCM token for the signed-in user through the
     * `register_device(p_token, p_app_version, p_os_version, p_locale, p_timezone)` RPC, then
     * remembers it in [SettingsStore.lastDeviceRegistration]. Call it on every app start and from
     * `onNewToken`: the RPC is an upsert and also refreshes `last_seen_at`.
     *
     * @param locale BCP 47 tag, e.g. "en-IN"
     * @param timezone IANA id of the phone, e.g. "Asia/Kolkata"
     */
    suspend fun registerDevice(
        fcmToken: String,
        appVersion: String? = null,
        osVersion: String? = null,
        locale: String? = null,
        timezone: String? = null,
    ): ActionResult

    /** Removes the token from the backend (`unregister_device`), e.g. before signing out. */
    suspend fun unregisterDevice(fcmToken: String): ActionResult

    /**
     * Master switch for this phone: with `false` the backend sends it no alerts at all
     * (`devices.notifications_enabled`). The choice survives later [registerDevice] calls.
     * Returns [ActionResult.NotFound] when the token was never registered.
     */
    suspend fun setNotificationsEnabled(fcmToken: String, enabled: Boolean): ActionResult
}

class DeviceRepositoryImpl @Inject constructor(
    private val api: BackendApi,
    private val auth: AuthRepository,
    private val settings: SettingsStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DeviceRepository {

    override suspend fun registerDevice(
        fcmToken: String,
        appVersion: String?,
        osVersion: String?,
        locale: String?,
        timezone: String?,
    ): ActionResult = withContext(io) {
        val signedIn = auth.ensureSignedIn().toActionResult()
        if (signedIn != ActionResult.Success) return@withContext signedIn
        try {
            api.registerDevice(RegisterDeviceParams(fcmToken, appVersion, osVersion, locale, timezone))
            auth.currentUserId?.let { settings.setLastDeviceRegistration(DeviceRegistration(fcmToken, it)) }
            ActionResult.Success
        } catch (e: BackendException) {
            e.toActionResult()
        }
    }

    override suspend fun unregisterDevice(fcmToken: String): ActionResult = withContext(io) {
        try {
            api.unregisterDevice(fcmToken)
            settings.setLastDeviceRegistration(null)
            ActionResult.Success
        } catch (e: BackendException) {
            e.toActionResult()
        }
    }

    override suspend fun setNotificationsEnabled(fcmToken: String, enabled: Boolean): ActionResult = withContext(io) {
        val signedIn = auth.ensureSignedIn().toActionResult()
        if (signedIn != ActionResult.Success) return@withContext signedIn
        try {
            if (api.setDeviceNotificationsEnabled(fcmToken, enabled)) ActionResult.Success else ActionResult.NotFound
        } catch (e: BackendException) {
            e.toActionResult()
        }
    }
}

// ---- airports -------------------------------------------------------------------------------------

/**
 * Airports are NOT bundled with the app in this step. They reach Room in two ways:
 * 1. every `flight_view` row embeds its two airports, which are cached as a side effect of
 *    search/refresh (so tracked flights never need an extra request);
 * 2. on demand through this repository, from `public.airports` via PostgREST, then cached.
 */
interface AirportRepository {
    /** Cached airport, else fetched and cached. Null when unknown to the backend or unreachable. */
    suspend fun getAirport(iata: String): Airport?

    /** Makes sure the given airports are cached (one request for all missing ones). */
    suspend fun ensureCached(iatas: Collection<String>): ActionResult
}

class AirportRepositoryImpl @Inject constructor(
    private val api: BackendApi,
    private val auth: AuthRepository,
    private val airportDao: AirportDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AirportRepository {

    override suspend fun getAirport(iata: String): Airport? = withContext(io) {
        val code = iata.trim().uppercase()
        airportDao.get(code)?.toDomain() ?: run {
            ensureCached(listOf(code))
            airportDao.get(code)?.toDomain()
        }
    }

    override suspend fun ensureCached(iatas: Collection<String>): ActionResult = withContext(io) {
        val wanted = iatas.map { it.trim().uppercase() }.filter { it.length == 3 }.distinct()
        val missing = wanted - airportDao.knownIatas(wanted).toSet()
        if (missing.isEmpty()) return@withContext ActionResult.Success
        val signedIn = auth.ensureSignedIn().toActionResult()
        if (signedIn != ActionResult.Success) return@withContext signedIn
        try {
            airportDao.upsert(api.airports(missing).map { it.toEntity() })
            ActionResult.Success
        } catch (e: BackendException) {
            e.toActionResult()
        }
    }
}

// ---- profile / plan -------------------------------------------------------------------------------

interface ProfileRepository {
    /** The signed-in user's profile from Room; null until the first [refresh] or while signed out. */
    fun observeProfile(): Flow<UserProfile?>

    /** Fetches the `profiles` row (plan, pro_until) into Room. */
    suspend fun refresh(): ActionResult
}

class ProfileRepositoryImpl @Inject constructor(
    private val api: BackendApi,
    private val auth: AuthRepository,
    private val profileDao: ProfileDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ProfileRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeProfile(): Flow<UserProfile?> = auth.session
        .map { (it as? SessionState.SignedIn)?.userId }
        .distinctUntilChanged()
        .flatMapLatest { userId ->
            if (userId == null) flowOf(null) else profileDao.observe(userId).map { it?.toDomain() }
        }
        .distinctUntilChanged()

    override suspend fun refresh(): ActionResult = withContext(io) {
        val signedIn = auth.ensureSignedIn().toActionResult()
        if (signedIn != ActionResult.Success) return@withContext signedIn
        val userId = auth.currentUserId ?: return@withContext ActionResult.Failed("no user")
        try {
            val profile = api.profile(userId) ?: return@withContext ActionResult.NotFound
            profileDao.upsert(profile.toEntity())
            ActionResult.Success
        } catch (e: BackendException) {
            e.toActionResult()
        }
    }
}

// ---- local wipe -----------------------------------------------------------------------------------

class RoomLocalUserData @Inject constructor(
    private val flightDao: FlightDao,
    private val trackedDao: TrackedFlightDao,
    private val profileDao: ProfileDao,
    private val settings: SettingsStore,
    private val transactions: TransactionRunner,
    // Lazy: a build with Gmail import off never needs its HTTP client until someone signs out.
    private val gmail: dagger.Lazy<GmailConnection>,
) : LocalUserData {
    override suspend fun clear() {
        transactions.inTransaction {
            trackedDao.clear()
            profileDao.clear()
            // Flight rows carry per-user Pro fields (prediction), so they go too; airports are public data.
            flightDao.clear()
        }
        settings.setLastDeviceRegistration(null)
        // Gmail access was granted by the user who is leaving: revoke it, and drop suggestions, seen ids,
        // Gmail settings, the background check and its notifications.
        gmail.get().disconnect()
    }
}
