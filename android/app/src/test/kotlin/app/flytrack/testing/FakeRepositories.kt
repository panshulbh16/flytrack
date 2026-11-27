package app.flytrack.testing

import app.flytrack.core.model.Airport
import app.flytrack.core.model.AlertType
import app.flytrack.core.model.Flight
import app.flytrack.core.model.RouteSearchQuery
import app.flytrack.core.model.TrackExtras
import app.flytrack.core.model.TrackedFlightDetails
import app.flytrack.core.model.UserProfile
import app.flytrack.core.result.ActionResult
import app.flytrack.core.result.AuthResult
import app.flytrack.core.result.SearchResult
import app.flytrack.core.result.TrackResult
import app.flytrack.data.repo.AirportRepository
import app.flytrack.data.repo.Appearance
import app.flytrack.data.repo.AuthRepository
import app.flytrack.data.repo.DeviceRegistration
import app.flytrack.data.repo.DeviceRepository
import app.flytrack.data.repo.FlightRepository
import app.flytrack.data.repo.ProfileRepository
import app.flytrack.data.repo.SessionState
import app.flytrack.data.repo.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate

// In-memory fakes of every repository interface, for ViewModel tests.
// Each fake behaves sensibly by default (tracking a flight makes it appear in the list), exposes
// its state as mutable properties, records calls, and lets a test force a result via `next…`/`…Result`.

/** Signed in as an anonymous user by default. Set [session] or use [signedOut] / [notConfigured]. */
class FakeAuthRepository(
    initial: SessionState = SessionState.SignedIn(TestData.USER_ID, isAnonymous = true, email = null),
) : AuthRepository {
    private val _session = MutableStateFlow(initial)
    override val session: StateFlow<SessionState> = _session

    override val currentUserId: String? get() = (_session.value as? SessionState.SignedIn)?.userId

    /** Returned by every command while non-null; otherwise the command succeeds. */
    var nextResult: AuthResult? = null
    var ensureSignedInCalls = 0
    val linkedIdTokens = mutableListOf<String>()
    var signOutCalls = 0

    fun setSession(state: SessionState) {
        _session.value = state
    }

    override suspend fun ensureSignedIn(): AuthResult {
        ensureSignedInCalls++
        nextResult?.let { return it }
        return when (_session.value) {
            SessionState.NotConfigured -> AuthResult.NotConfigured
            is SessionState.SignedIn -> AuthResult.Success
            else -> {
                _session.value = SessionState.SignedIn(TestData.USER_ID, isAnonymous = true, email = null)
                AuthResult.Success
            }
        }
    }

    override suspend fun linkGoogle(idToken: String, nonce: String?): AuthResult {
        linkedIdTokens += idToken
        nextResult?.let { return it }
        val current = _session.value as? SessionState.SignedIn ?: return AuthResult.Failed("not signed in")
        _session.value = current.copy(isAnonymous = false, email = "user@example.com")
        return AuthResult.Success
    }

    override suspend fun signInWithGoogle(idToken: String, nonce: String?): AuthResult {
        linkedIdTokens += idToken
        nextResult?.let { return it }
        _session.value = SessionState.SignedIn("google-user", isAnonymous = false, email = "user@example.com")
        return AuthResult.Success
    }

    override suspend fun signOut(): AuthResult {
        signOutCalls++
        nextResult?.let { return it }
        _session.value = SessionState.SignedIn("anon-after-sign-out", isAnonymous = true, email = null)
        return AuthResult.Success
    }

    companion object {
        fun signedOut() = FakeAuthRepository(SessionState.SignedOut)
        fun notConfigured() = FakeAuthRepository(SessionState.NotConfigured)
    }
}

class FakeFlightRepository : FlightRepository {
    /** Everything the user tracks, archived rows included. */
    val tracked = MutableStateFlow<List<TrackedFlightDetails>>(emptyList())

    /** Cached flights that are not (yet) tracked: search results, inbound legs. */
    val cachedFlights = MutableStateFlow<Map<String, Flight>>(emptyMap())

    /** Result of [search]; a [SearchResult.Found] also fills [cachedFlights]. */
    var searchResult: SearchResult = SearchResult.NotFound

    /** Forces the result of [track]; null = default behaviour (track the cached flight). */
    var trackResult: TrackResult? = null

    /** Forces the result of untrack/archive/updateNotifyPrefs/updateTrackingDetails; null = default behaviour. */
    var actionResult: ActionResult? = null
    var refreshResult: ActionResult = ActionResult.Success
    var syncResult: ActionResult = ActionResult.Success

    val searches = mutableListOf<Pair<String, LocalDate>>()
    val trackCalls = mutableListOf<Pair<String, TrackExtras>>()
    val untrackCalls = mutableListOf<String>()
    val syncCalls = mutableListOf<Pair<String, String?>>()
    var refreshCalls = 0

    fun setTracked(vararg flights: TrackedFlightDetails) {
        tracked.value = flights.toList()
    }

    override fun observeTrackedFlights(): Flow<List<TrackedFlightDetails>> =
        tracked.map { list -> list.filterNot { it.tracking.isArchived } }.distinctUntilChanged()

    override fun observeTrackedFlight(flightId: String): Flow<TrackedFlightDetails?> =
        tracked.map { list -> list.firstOrNull { it.flight.id == flightId } }.distinctUntilChanged()

    override fun observeFlight(flightId: String): Flow<Flight?> =
        combine(tracked, cachedFlights) { list, cache ->
            list.firstOrNull { it.flight.id == flightId }?.flight ?: cache[flightId]
        }.distinctUntilChanged()

    override suspend fun search(flightText: String, date: LocalDate): SearchResult {
        searches += flightText to date
        val result = searchResult
        if (result is SearchResult.Found) cachedFlights.update { cache -> cache + result.legs.associateBy { it.id } }
        return result
    }

    /** Result of [searchPast] and [searchRoute]; null = the same as [searchResult]. */
    var pastSearchResult: SearchResult? = null
    var routeSearchResult: SearchResult? = null
    val pastSearches = mutableListOf<Pair<String, LocalDate>>()
    val routeSearches = mutableListOf<RouteSearchQuery>()

    override suspend fun searchPast(flightText: String, date: LocalDate): SearchResult {
        pastSearches += flightText to date
        return cacheFound(pastSearchResult ?: searchResult)
    }

    override suspend fun searchRoute(query: RouteSearchQuery): SearchResult {
        routeSearches += query
        return cacheFound(routeSearchResult ?: searchResult)
    }

    private fun cacheFound(result: SearchResult): SearchResult {
        if (result is SearchResult.Found) cachedFlights.update { cache -> cache + result.legs.associateBy { it.id } }
        return result
    }

    override suspend fun track(flightId: String, extras: TrackExtras): TrackResult {
        trackCalls += flightId to extras
        trackResult?.let { return it }
        tracked.value.firstOrNull { it.flight.id == flightId && (!it.tracking.isArchived || extras.archived) }
            ?.let { return TrackResult.AlreadyTracked(it.tracking) }
        val flight = cachedFlights.value[flightId] ?: tracked.value.firstOrNull { it.flight.id == flightId }?.flight
            ?: return TrackResult.FlightNotFound
        val tracking = TestData.tracking(
            flightId = flightId, pnr = extras.pnr, seat = extras.seat, cabin = extras.cabin,
            source = extras.source, note = extras.note,
        ).let { if (extras.archived) it.copy(archivedAt = TestData.NOW) else it }
        tracked.update { list -> list.filterNot { it.flight.id == flightId } + TrackedFlightDetails(tracking, flight) }
        return TrackResult.Tracked(tracking)
    }

    override suspend fun untrack(flightId: String): ActionResult {
        untrackCalls += flightId
        return mutate(flightId) { null }
    }

    override suspend fun archive(flightId: String, archived: Boolean): ActionResult = mutate(flightId) {
        it.copy(tracking = it.tracking.copy(archivedAt = if (archived) TestData.NOW else null))
    }

    override suspend fun updateNotifyPrefs(flightId: String, prefs: Map<AlertType, Boolean>): ActionResult =
        mutate(flightId) { it.copy(tracking = it.tracking.copy(notifyPrefs = it.tracking.notifyPrefs + prefs)) }

    override suspend fun updateTrackingDetails(flightId: String, extras: TrackExtras): ActionResult = mutate(flightId) {
        it.copy(tracking = it.tracking.copy(pnr = extras.pnr, seat = extras.seat, cabin = extras.cabin, note = extras.note))
    }

    override suspend fun refresh(): ActionResult {
        refreshCalls++
        return refreshResult
    }

    override suspend fun applyServerSync(flightId: String, compactFlightJson: String?): ActionResult {
        syncCalls += flightId to compactFlightJson
        return syncResult
    }

    /** Replaces (or removes, when [change] returns null) the tracked entry of [flightId]. */
    private fun mutate(flightId: String, change: (TrackedFlightDetails) -> TrackedFlightDetails?): ActionResult {
        actionResult?.let { return it }
        val current = tracked.value.firstOrNull { it.flight.id == flightId } ?: return ActionResult.NotFound
        tracked.update { list -> list.mapNotNull { if (it === current) change(it) else it } }
        return ActionResult.Success
    }
}

class FakeDeviceRepository : DeviceRepository {
    var result: ActionResult = ActionResult.Success

    data class Registration(
        val fcmToken: String,
        val appVersion: String?,
        val osVersion: String?,
        val locale: String?,
        val timezone: String?,
    )

    val registrations = mutableListOf<Registration>()
    val unregisteredTokens = mutableListOf<String>()

    override suspend fun registerDevice(
        fcmToken: String,
        appVersion: String?,
        osVersion: String?,
        locale: String?,
        timezone: String?,
    ): ActionResult {
        registrations += Registration(fcmToken, appVersion, osVersion, locale, timezone)
        return result
    }

    override suspend fun unregisterDevice(fcmToken: String): ActionResult {
        unregisteredTokens += fcmToken
        return result
    }

    /** The last value passed to [setNotificationsEnabled], null if it was never called. */
    var notificationsEnabled: Boolean? = null

    override suspend fun setNotificationsEnabled(fcmToken: String, enabled: Boolean): ActionResult {
        if (result == ActionResult.Success) notificationsEnabled = enabled
        return result
    }
}

/** Knows [TestData]'s airports by default. */
class FakeAirportRepository(
    vararg airports: Airport = arrayOf(TestData.DEL, TestData.BOM, TestData.BLR, TestData.DXB),
) : AirportRepository {
    val airports: MutableMap<String, Airport> = airports.associateBy { it.iata }.toMutableMap()
    var ensureResult: ActionResult = ActionResult.Success
    val lookups = mutableListOf<String>()

    override suspend fun getAirport(iata: String): Airport? {
        lookups += iata
        return airports[iata.trim().uppercase()]
    }

    override suspend fun ensureCached(iatas: Collection<String>): ActionResult = ensureResult
}

class FakeProfileRepository(initial: UserProfile? = TestData.profile()) : ProfileRepository {
    val profile = MutableStateFlow(initial)
    var refreshResult: ActionResult = ActionResult.Success
    var refreshCalls = 0

    override fun observeProfile(): Flow<UserProfile?> = profile

    override suspend fun refresh(): ActionResult {
        refreshCalls++
        return refreshResult
    }
}

class FakeSettingsStore(
    cloudTicketParsingConsent: Boolean = false,
    notificationPermissionAsked: Boolean = false,
    lastDeviceRegistration: DeviceRegistration? = null,
) : SettingsStore {
    private val consent = MutableStateFlow(cloudTicketParsingConsent)
    private val asked = MutableStateFlow(notificationPermissionAsked)
    private val registration = MutableStateFlow(lastDeviceRegistration)

    override val cloudTicketParsingConsent: Flow<Boolean> = consent
    override val notificationPermissionAsked: Flow<Boolean> = asked
    override val lastDeviceRegistration: Flow<DeviceRegistration?> = registration

    override suspend fun setCloudTicketParsingConsent(granted: Boolean) {
        consent.value = granted
    }

    override suspend fun setNotificationPermissionAsked(asked: Boolean) {
        this.asked.value = asked
    }

    override suspend fun setLastDeviceRegistration(registration: DeviceRegistration?) {
        this.registration.value = registration
    }

    private val appearanceFlow = MutableStateFlow(Appearance.SYSTEM)
    override val appearance: Flow<Appearance> = appearanceFlow

    override suspend fun setAppearance(appearance: Appearance) {
        appearanceFlow.value = appearance
    }

    private val gmailEmail = MutableStateFlow<String?>(null)
    private val gmailAuto = MutableStateFlow(true)
    private val gmailLastCheck = MutableStateFlow<Long?>(null)
    private val gmailReconnect = MutableStateFlow(false)
    private val gmailLastAppOpen = MutableStateFlow<Long?>(null)

    override val gmailAccountEmail: Flow<String?> = gmailEmail
    override val gmailAutoCheck: Flow<Boolean> = gmailAuto
    override val gmailLastCheckEpochSeconds: Flow<Long?> = gmailLastCheck
    override val gmailNeedsReconnect: Flow<Boolean> = gmailReconnect
    override val gmailLastAppOpenCheckEpochSeconds: Flow<Long?> = gmailLastAppOpen

    override suspend fun setGmailAccountEmail(email: String?) {
        gmailEmail.value = email
    }

    override suspend fun setGmailAutoCheck(enabled: Boolean) {
        gmailAuto.value = enabled
    }

    override suspend fun setGmailLastCheckEpochSeconds(value: Long?) {
        gmailLastCheck.value = value
    }

    override suspend fun setGmailNeedsReconnect(value: Boolean) {
        gmailReconnect.value = value
    }

    override suspend fun setGmailLastAppOpenCheckEpochSeconds(value: Long?) {
        gmailLastAppOpen.value = value
    }
}
