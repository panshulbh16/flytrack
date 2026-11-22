package app.flytrack.testing

import app.flytrack.data.local.AirportDao
import app.flytrack.data.local.AirportEntity
import app.flytrack.data.local.FlightDao
import app.flytrack.data.local.FlightEntity
import app.flytrack.data.local.FlightWithAirports
import app.flytrack.data.local.ProfileDao
import app.flytrack.data.local.ProfileEntity
import app.flytrack.data.local.TrackedFlightDao
import app.flytrack.data.local.TrackedFlightEntity
import app.flytrack.data.local.TrackedFlightWithFlight
import app.flytrack.data.local.TransactionRunner
import app.flytrack.data.remote.AuthGateway
import app.flytrack.data.remote.BackendApi
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.FlyTrackJson
import app.flytrack.data.remote.PgErrors
import app.flytrack.data.remote.RemoteAuthState
import app.flytrack.data.remote.RemoteSession
import app.flytrack.data.remote.dto.AirportDto
import app.flytrack.data.remote.dto.FlightViewDto
import app.flytrack.data.remote.dto.ProfileDto
import app.flytrack.data.remote.dto.RegisterDeviceParams
import app.flytrack.data.remote.dto.SearchFlightResponse
import app.flytrack.data.remote.dto.UserFlightDto
import app.flytrack.data.remote.dto.UserFlightInsertDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

// Data-layer fakes for the repository implementation tests: an in-memory "server" behind
// BackendApi/AuthGateway and in-memory Room DAOs. Feature tests normally want FakeRepositories.kt instead.

/** Behaves like the Supabase backend for one signed-in user, including the database's error cases. */
class FakeBackendApi(private val userId: String = TestData.USER_ID) : BackendApi {
    val serverFlights = mutableMapOf<String, FlightViewDto>()
    val serverUserFlights = mutableListOf<UserFlightDto>()
    val serverAirports = mutableMapOf<String, AirportDto>()
    var serverProfile: ProfileDto? = ProfileDto(id = userId)
    val devices = mutableListOf<RegisterDeviceParams>()

    /** Active (non-archived) tracked flights allowed, as enforced by the database trigger. */
    var trackingLimit = 10

    /** What `search-flight` answers; throw a [BackendException.Http] to simulate its error codes. */
    var searchHandler: (flight: String, date: String) -> List<FlightViewDto> = { _, _ -> emptyList() }

    /** Thrown by every call while non-null (e.g. `BackendException.Network(IOException())`). */
    var failure: BackendException? = null

    /** Thrown only by the named call ("flightViews", "userFlights", ...). */
    val failures = mutableMapOf<String, BackendException>()

    /** Names of all calls made, in order, e.g. "searchFlight(6E 2341, 2026-11-25)". */
    val calls = mutableListOf<String>()

    private var nextId = 1

    fun addFlights(vararg flights: FlightViewDto) = flights.forEach { serverFlights[it.id] = it }

    private fun enter(name: String, detail: String = "") {
        calls += if (detail.isEmpty()) name else "$name($detail)"
        failure?.let { throw it }
        failures[name]?.let { throw it }
    }

    /** Answers of [invokeFunction] by function name; the lambda gets the JSON request body. */
    val functionHandlers = mutableMapOf<String, (jsonBody: String) -> String>()

    override suspend fun invokeFunction(name: String, jsonBody: String): String {
        enter("invokeFunction", "$name, $jsonBody")
        val handler = functionHandlers[name] ?: throw BackendException.Http(404, null, "Requested function was not found")
        return handler(jsonBody)
    }

    override suspend fun searchFlight(flight: String, date: String): List<FlightViewDto> {
        enter("searchFlight", "$flight, $date")
        return searchHandler(flight, date).onEach { serverFlights[it.id] = it }
    }

    override suspend fun flightViews(ids: Collection<String>): List<FlightViewDto> {
        enter("flightViews", ids.joinToString(","))
        return ids.mapNotNull { serverFlights[it] }
    }

    override suspend fun userFlights(): List<UserFlightDto> {
        enter("userFlights")
        return serverUserFlights.toList()
    }

    override suspend fun userFlightByFlightId(flightId: String): UserFlightDto? {
        enter("userFlightByFlightId", flightId)
        return serverUserFlights.firstOrNull { it.flightId == flightId }
    }

    private fun checkLimit(excludingId: String?) {
        val active = serverUserFlights.count { it.archivedAt == null && it.id != excludingId }
        if (active >= trackingLimit) {
            throw BackendException.Http(400, PgErrors.RAISE_EXCEPTION, PgErrors.TRACKING_LIMIT_MESSAGE, "Archive an old flight or upgrade to Pro")
        }
    }

    override suspend fun insertUserFlight(body: UserFlightInsertDto): UserFlightDto {
        enter("insertUserFlight", body.flightId)
        if (body.flightId !in serverFlights) {
            throw BackendException.Http(409, PgErrors.FOREIGN_KEY_VIOLATION, "violates foreign key constraint")
        }
        if (serverUserFlights.any { it.flightId == body.flightId }) {
            throw BackendException.Http(409, PgErrors.UNIQUE_VIOLATION, "duplicate key value violates unique constraint")
        }
        checkLimit(excludingId = null)
        val row = UserFlightDto(
            id = "uf-${nextId++}", userId = userId, flightId = body.flightId, pnr = body.pnr, seat = body.seat,
            cabin = body.cabin, source = body.source, note = body.note, createdAt = TestData.NOW.toString(),
        )
        serverUserFlights += row
        return row
    }

    override suspend fun updateUserFlight(id: String, patch: JsonObject): UserFlightDto? {
        enter("updateUserFlight", "$id, $patch")
        val index = serverUserFlights.indexOfFirst { it.id == id }
        if (index < 0) return null
        var row = serverUserFlights[index]
        fun text(key: String, current: String?): String? =
            if (key !in patch) current else patch.getValue(key).takeUnless { it is JsonNull }?.jsonPrimitive?.content
        if ("archived_at" in patch && patch["archived_at"] is JsonNull && row.archivedAt != null) checkLimit(excludingId = id)
        row = row.copy(
            pnr = text("pnr", row.pnr),
            seat = text("seat", row.seat),
            cabin = text("cabin", row.cabin),
            note = text("note", row.note),
            archivedAt = text("archived_at", row.archivedAt),
            notifyPrefs = patch["notify_prefs"]?.jsonObject?.mapValues { it.value.jsonPrimitive.boolean } ?: row.notifyPrefs,
        )
        serverUserFlights[index] = row
        return row
    }

    override suspend fun deleteUserFlight(id: String) {
        enter("deleteUserFlight", id)
        serverUserFlights.removeAll { it.id == id }
    }

    override suspend fun airports(iatas: Collection<String>): List<AirportDto> {
        enter("airports", iatas.joinToString(","))
        return iatas.mapNotNull { serverAirports[it] }
    }

    override suspend fun profile(userId: String): ProfileDto? {
        enter("profile", userId)
        return serverProfile?.takeIf { it.id == userId }
    }

    override suspend fun registerDevice(params: RegisterDeviceParams): String {
        enter("registerDevice", params.token)
        devices += params
        return "device-${devices.size}"
    }

    override suspend fun unregisterDevice(token: String) {
        enter("unregisterDevice", token)
        devices.removeAll { it.token == token }
        deviceNotificationsEnabled.remove(token)
    }

    /** `devices.notifications_enabled` per registered token (absent = the default, true). */
    val deviceNotificationsEnabled = mutableMapOf<String, Boolean>()

    override suspend fun setDeviceNotificationsEnabled(token: String, enabled: Boolean): Boolean {
        enter("setDeviceNotificationsEnabled", "$token, $enabled")
        if (devices.none { it.token == token }) return false
        deviceNotificationsEnabled[token] = enabled
        return true
    }

    companion object {
        /** The `flight_view` rows of the fixtures: [0] = 6E 2341 DEL-BOM (Pro view), [1] = its inbound leg 6E 2032. */
        fun fixtureFlights(): List<FlightViewDto> {
            val response = FlyTrackJson.decodeFromString(SearchFlightResponse.serializer(), Fixtures.read("search_response.json"))
            return response.flights
        }

        fun fixtureUserFlights(): List<UserFlightDto> =
            FlyTrackJson.decodeFromString(ListSerializer(UserFlightDto.serializer()), Fixtures.read("user_flights.json"))
    }
}

/** Auth backend with no user until [signInAnonymously] is called (or [signedIn] is used). */
class FakeAuthGateway(override var isConfigured: Boolean = true) : AuthGateway {
    private val _state = MutableStateFlow<RemoteAuthState>(RemoteAuthState.SignedOut)
    override val state: Flow<RemoteAuthState> = _state

    /** Thrown by every network operation while non-null. */
    var failure: BackendException? = null
    var anonymousSignIns = 0
    var refreshes = 0
    var signOuts = 0

    /** The user a Google ID token resolves to in [signInWithGoogleIdToken]. */
    var googleUserId = "google-user"

    /** When true, linking fails like the server does for a Google account that already has a user. */
    var googleAccountInUse = false

    /** Simulates the stored session not being readable in time. */
    var stillInitializing = false

    fun signedIn(userId: String = TestData.USER_ID, anonymous: Boolean = true, refreshPending: Boolean = false): FakeAuthGateway =
        apply { _state.value = RemoteAuthState.SignedIn(RemoteSession(userId, anonymous, null), refreshPending) }

    private fun check() {
        if (!isConfigured) throw BackendException.NotConfigured()
        failure?.let { throw it }
    }

    override suspend fun awaitState(): RemoteAuthState {
        if (!isConfigured) throw BackendException.NotConfigured()
        if (stillInitializing) throw BackendException.Network(java.io.IOException("the stored session is still loading"))
        return _state.value
    }

    override fun currentSession(): RemoteSession? = (_state.value as? RemoteAuthState.SignedIn)?.session

    override suspend fun signInAnonymously() {
        check()
        anonymousSignIns++
        _state.value = RemoteAuthState.SignedIn(RemoteSession("anon-$anonymousSignIns", isAnonymous = true, email = null))
    }

    override suspend fun refreshSession() {
        check()
        refreshes++
        val current = checkNotNull(_state.value as? RemoteAuthState.SignedIn) { "nothing to refresh" }
        _state.value = current.copy(refreshPending = false)
    }

    override suspend fun linkGoogleIdToken(idToken: String, nonce: String?) {
        check()
        if (googleAccountInUse) throw BackendException.Http(422, AuthGateway.CODE_IDENTITY_ALREADY_EXISTS, "Identity is already linked")
        val current = checkNotNull(currentSession()) { "not signed in" }
        _state.value = RemoteAuthState.SignedIn(current.copy(isAnonymous = false, email = "user@example.com"))
    }

    override suspend fun signInWithGoogleIdToken(idToken: String, nonce: String?) {
        check()
        _state.value = RemoteAuthState.SignedIn(RemoteSession(googleUserId, isAnonymous = false, email = "user@example.com"))
    }

    override suspend fun signOut() {
        check()
        signOuts++
        _state.value = RemoteAuthState.SignedOut
    }
}

/** The Room tables as maps, shared by the in-memory DAOs below. */
class InMemoryDatabase {
    val flights = MutableStateFlow<Map<String, FlightEntity>>(emptyMap())
    val tracked = MutableStateFlow<Map<String, TrackedFlightEntity>>(emptyMap())
    val airports = MutableStateFlow<Map<String, AirportEntity>>(emptyMap())
    val profiles = MutableStateFlow<Map<String, ProfileEntity>>(emptyMap())

    val flightDao: FlightDao = InMemoryFlightDao(this)
    val trackedDao: TrackedFlightDao = InMemoryTrackedFlightDao(this)
    val airportDao: AirportDao = InMemoryAirportDao(this)
    val profileDao: ProfileDao = InMemoryProfileDao(this)

    /** No real atomicity is needed in single-threaded tests. */
    val transactions: TransactionRunner = object : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    internal fun join(flight: FlightEntity, airports: Map<String, AirportEntity>) =
        FlightWithAirports(flight, airports[flight.depIata], airports[flight.arrIata])
}

private class InMemoryFlightDao(private val db: InMemoryDatabase) : FlightDao {
    override suspend fun upsert(flights: List<FlightEntity>) = db.flights.update { it + flights.associateBy(FlightEntity::id) }

    override suspend fun get(id: String): FlightEntity? = db.flights.value[id]

    override fun observe(id: String): Flow<FlightWithAirports?> =
        combine(db.flights, db.airports) { flights, airports -> flights[id]?.let { db.join(it, airports) } }

    override suspend fun pruneUnreferenced(olderThan: Instant) {
        val trackedIds = db.tracked.value.values.map { it.flightId }.toSet()
        val inboundIds = db.flights.value.values.mapNotNull { it.inboundFlightId }.toSet()
        db.flights.update { flights ->
            flights.filterValues { f ->
                f.id in trackedIds || f.id in inboundIds || f.updatedAt.let { it != null && it >= olderThan }
            }
        }
    }

    override suspend fun clear() {
        db.flights.value = emptyMap()
    }
}

private class InMemoryTrackedFlightDao(private val db: InMemoryDatabase) : TrackedFlightDao {
    private fun joined(): Flow<List<TrackedFlightWithFlight>> =
        combine(db.tracked, db.flights, db.airports) { tracked, flights, airports ->
            tracked.values.mapNotNull { row ->
                flights[row.flightId]?.let { TrackedFlightWithFlight(row, db.join(it, airports)) }
            }
        }

    override fun observeActive(): Flow<List<TrackedFlightWithFlight>> = joined().map { rows ->
        rows.filter { it.tracking.archivedAt == null }.sortedWith(
            compareBy<TrackedFlightWithFlight, Instant?>(nullsLast()) { with(it.flight.flight) { actDep ?: estDep ?: schedDep } }
                .thenBy { it.flight.flight.carrierIata }
                .thenBy { it.flight.flight.flightNumber },
        )
    }

    override fun observeByFlightId(flightId: String): Flow<TrackedFlightWithFlight?> =
        joined().map { rows -> rows.firstOrNull { it.tracking.flightId == flightId } }

    override suspend fun getByFlightId(flightId: String): TrackedFlightEntity? =
        db.tracked.value.values.firstOrNull { it.flightId == flightId }

    override suspend fun upsert(rows: List<TrackedFlightEntity>) = db.tracked.update { it + rows.associateBy(TrackedFlightEntity::id) }

    override suspend fun delete(id: String) = db.tracked.update { it - id }

    override suspend fun clear() {
        db.tracked.value = emptyMap()
    }
}

private class InMemoryAirportDao(private val db: InMemoryDatabase) : AirportDao {
    override suspend fun upsert(airports: List<AirportEntity>) = db.airports.update { it + airports.associateBy(AirportEntity::iata) }

    override suspend fun get(iata: String): AirportEntity? = db.airports.value[iata]

    override suspend fun knownIatas(iatas: List<String>): List<String> = iatas.filter { it in db.airports.value }
}

private class InMemoryProfileDao(private val db: InMemoryDatabase) : ProfileDao {
    override suspend fun upsert(profile: ProfileEntity) = db.profiles.update { it + (profile.id to profile) }

    override fun observe(id: String): Flow<ProfileEntity?> = db.profiles.map { it[id] }

    override suspend fun clear() {
        db.profiles.value = emptyMap()
    }
}
