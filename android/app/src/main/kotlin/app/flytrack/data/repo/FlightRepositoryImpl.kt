package app.flytrack.data.repo

import app.flytrack.core.flightnumber.parseFlightNumber
import app.flytrack.core.model.AlertType
import app.flytrack.core.model.Flight
import app.flytrack.core.model.RouteSearchQuery
import app.flytrack.core.model.TrackExtras
import app.flytrack.core.model.TrackedFlightDetails
import app.flytrack.core.model.bestDeparture
import app.flytrack.core.model.normalizePnr
import app.flytrack.core.result.ActionResult
import app.flytrack.core.result.AuthResult
import app.flytrack.core.result.BadInputReason
import app.flytrack.core.result.SearchResult
import app.flytrack.core.result.TrackField
import app.flytrack.core.result.TrackResult
import app.flytrack.core.time.PastSearchWindow
import app.flytrack.core.time.RouteSearchWindow
import app.flytrack.core.time.SearchWindow
import app.flytrack.data.local.AirportDao
import app.flytrack.data.local.FlightDao
import app.flytrack.data.local.TrackedFlightDao
import app.flytrack.data.local.TrackedFlightEntity
import app.flytrack.data.local.TransactionRunner
import app.flytrack.data.remote.BackendApi
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.airportEntities
import app.flytrack.data.remote.dto.CompactFlightDto
import app.flytrack.data.remote.dto.FlightViewDto
import app.flytrack.data.remote.dto.SearchFlightRequest
import app.flytrack.data.remote.dto.SearchFlightResponse
import app.flytrack.data.remote.dto.SearchRouteRequest
import app.flytrack.data.remote.dto.UserFlightDto
import app.flytrack.data.remote.dto.UserFlightInsertDto
import app.flytrack.data.remote.isForeignKeyViolation
import app.flytrack.data.remote.isTrackingLimit
import app.flytrack.data.remote.isUniqueViolation
import app.flytrack.data.remote.mergeInto
import app.flytrack.data.remote.toDomain
import app.flytrack.data.remote.toEntity
import app.flytrack.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class FlightRepositoryImpl @Inject constructor(
    private val api: BackendApi,
    private val auth: AuthRepository,
    private val flightDao: FlightDao,
    private val trackedDao: TrackedFlightDao,
    private val airportDao: AirportDao,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val json: Json,
    @IoDispatcher private val io: CoroutineDispatcher,
) : FlightRepository {

    // Two overlapping refreshes would interleave "clear + insert" of user_flights.
    private val refreshMutex = Mutex()

    override fun observeTrackedFlights(): Flow<List<TrackedFlightDetails>> =
        trackedDao.observeActive().map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged().flowOn(io)

    override fun observeTrackedFlight(flightId: String): Flow<TrackedFlightDetails?> =
        trackedDao.observeByFlightId(flightId).map { it?.toDomain() }.distinctUntilChanged().flowOn(io)

    override fun observeFlight(flightId: String): Flow<Flight?> =
        flightDao.observe(flightId).map { it?.toDomain() }.distinctUntilChanged().flowOn(io)

    // ---- search ---------------------------------------------------------------------------------

    override suspend fun search(flightText: String, date: LocalDate): SearchResult = withContext(io) {
        val number = parseFlightNumber(flightText)
            ?: return@withContext SearchResult.BadInput(BadInputReason.FLIGHT_NUMBER)
        if (!SearchWindow.contains(date, clock.now())) return@withContext SearchResult.BadInput(BadInputReason.DATE)
        try {
            requireSession()
            val rows = api.searchFlight(number.display, date.toString())
            if (rows.isEmpty()) {
                SearchResult.NotFound
            } else {
                cacheFlights(rows)
                SearchResult.Found(rows.map { it.toDomain() }.sortedWith(compareBy<Flight, kotlin.time.Instant?>(nullsLast()) { it.bestDeparture }))
            }
        } catch (e: BackendException) {
            e.toSearchResult()
        }
    }

    override suspend fun searchPast(flightText: String, date: LocalDate): SearchResult = withContext(io) {
        val number = parseFlightNumber(flightText)
            ?: return@withContext SearchResult.BadInput(BadInputReason.FLIGHT_NUMBER)
        if (!PastSearchWindow.contains(date, clock.now())) return@withContext SearchResult.BadInput(BadInputReason.DATE)
        functionSearch(SEARCH_FLIGHT_FUNCTION, json.encodeToString(SearchFlightRequest.serializer(), SearchFlightRequest(number.display, date.toString(), MODE_PAST)))
    }

    override suspend fun searchRoute(query: RouteSearchQuery): SearchResult = withContext(io) {
        val q = query.normalized()
        if (!q.isValid) return@withContext SearchResult.BadInput(BadInputReason.OTHER)
        if (!RouteSearchWindow.contains(q.date, clock.now())) return@withContext SearchResult.BadInput(BadInputReason.DATE)
        functionSearch(SEARCH_ROUTE_FUNCTION, json.encodeToString(SearchRouteRequest.serializer(), SearchRouteRequest(q.dep, q.arr, q.date.toString(), q.airline)))
    }

    /** The P3 searches go through the generic function door; the answer has search-flight's shape. */
    private suspend fun functionSearch(function: String, body: String): SearchResult = try {
        requireSession()
        val rows = try {
            json.decodeFromString(SearchFlightResponse.serializer(), api.invokeFunction(function, body)).flights
        } catch (e: kotlinx.serialization.SerializationException) {
            throw BackendException.Malformed(e)
        } catch (e: IllegalArgumentException) {
            throw BackendException.Malformed(e)
        }
        if (rows.isEmpty()) {
            SearchResult.NotFound
        } else {
            cacheFlights(rows)
            SearchResult.Found(rows.map { it.toDomain() }.sortedWith(compareBy<Flight, kotlin.time.Instant?>(nullsLast()) { it.bestDeparture }))
        }
    } catch (e: BackendException) {
        e.toSearchResult()
    }

    private fun BackendException.toSearchResult(): SearchResult = when (this) {
        is BackendException.NotConfigured -> SearchResult.NotConfigured
        is BackendException.Network -> SearchResult.Offline
        is BackendException.Malformed -> SearchResult.ProviderError
        is BackendException.Http -> when {
            code == "bad_input" -> SearchResult.BadInput(badInputReason(message))
            // Only the function's own code: a bare 404 means the function is not deployed, not "no such flight".
            code == "not_found" -> SearchResult.NotFound
            code == "rate_limited" || status == 429 -> SearchResult.RateLimited
            else -> SearchResult.ProviderError
        }
    }

    /** The server only sends English text for bad input; these are its two known messages. */
    private fun badInputReason(message: String?): BadInputReason = when {
        message == null -> BadInputReason.OTHER
        message.contains("Flight number", ignoreCase = true) -> BadInputReason.FLIGHT_NUMBER
        message.contains("Date", ignoreCase = true) -> BadInputReason.DATE
        else -> BadInputReason.OTHER
    }

    // ---- track / untrack / archive --------------------------------------------------------------

    override suspend fun track(flightId: String, extras: TrackExtras): TrackResult = withContext(io) {
        val valid = when (val checked = validate(extras)) {
            is Checked.Invalid -> return@withContext TrackResult.InvalidInput(checked.field)
            is Checked.Valid -> checked
        }
        try {
            requireSession()
            val row = try {
                api.insertUserFlight(
                    UserFlightInsertDto(
                        flightId = flightId,
                        pnr = valid.pnr,
                        seat = valid.seat,
                        cabin = extras.cabin?.wire,
                        source = extras.source.wire,
                        note = valid.note,
                        archivedAt = if (extras.archived) clock.now().toString() else null,
                    ),
                )
            } catch (e: BackendException.Http) {
                if (!e.isUniqueViolation) throw e
                return@withContext trackExisting(flightId, valid, extras)
            }
            storeTracking(row)
            TrackResult.Tracked(row.toEntity().toDomain())
        } catch (e: BackendException) {
            e.toTrackResult()
        }
    }

    /** The user already has a row for this flight: un-archive it, or report that it is tracked. */
    private suspend fun trackExisting(flightId: String, valid: Checked.Valid, extras: TrackExtras): TrackResult {
        val existing = api.userFlightByFlightId(flightId)
            ?: return TrackResult.Failed("user_flights row exists but is not readable")
        // A past flight for Passport never changes an existing row: an archived one is already in Passport,
        // an active one stays tracked.
        if (existing.archivedAt == null || extras.archived) {
            storeTracking(existing)
            return TrackResult.AlreadyTracked(existing.toEntity().toDomain())
        }
        val patch = buildJsonObject {
            put("archived_at", JsonNull)
            valid.pnr?.let { put("pnr", it) }
            valid.seat?.let { put("seat", it) }
            extras.cabin?.let { put("cabin", it.wire) }
            valid.note?.let { put("note", it) }
        }
        val revived = api.updateUserFlight(existing.id, patch) ?: return TrackResult.Failed("user_flights row vanished")
        storeTracking(revived)
        return TrackResult.Tracked(revived.toEntity().toDomain())
    }

    private fun BackendException.toTrackResult(): TrackResult = when (this) {
        is BackendException.NotConfigured -> TrackResult.NotConfigured
        is BackendException.Network -> TrackResult.Offline
        is BackendException.Malformed -> TrackResult.Failed(message)
        is BackendException.Http -> when {
            isTrackingLimit -> TrackResult.TrackingLimitReached(hint)
            isForeignKeyViolation -> TrackResult.FlightNotFound
            else -> TrackResult.Failed(message)
        }
    }

    override suspend fun untrack(flightId: String): ActionResult = command {
        val id = trackingId(flightId) ?: return@command ActionResult.NotFound
        api.deleteUserFlight(id)
        trackedDao.delete(id)
        ActionResult.Success
    }

    override suspend fun archive(flightId: String, archived: Boolean): ActionResult = command {
        val id = trackingId(flightId) ?: return@command ActionResult.NotFound
        val patch = buildJsonObject {
            put("archived_at", if (archived) JsonPrimitive(clock.now().toString()) else JsonNull)
        }
        patchTracking(id, patch)
    }

    override suspend fun updateNotifyPrefs(flightId: String, prefs: Map<AlertType, Boolean>): ActionResult = command {
        val current = tracking(flightId) ?: return@command ActionResult.NotFound
        val merged = current.notifyPrefs + prefs.mapKeys { it.key.wire }
        val patch = buildJsonObject {
            putJsonObject("notify_prefs") { merged.forEach { (type, enabled) -> put(type, enabled) } }
        }
        patchTracking(current.id, patch)
    }

    override suspend fun updateTrackingDetails(flightId: String, extras: TrackExtras): ActionResult = command {
        val valid = when (val checked = validate(extras)) {
            is Checked.Invalid -> return@command ActionResult.InvalidInput(checked.field)
            is Checked.Valid -> checked
        }
        val id = trackingId(flightId) ?: return@command ActionResult.NotFound
        val patch = buildJsonObject {
            put("pnr", valid.pnr)
            put("seat", valid.seat)
            put("cabin", extras.cabin?.wire)
            put("note", valid.note)
        }
        patchTracking(id, patch)
    }

    private suspend fun patchTracking(id: String, patch: JsonObject): ActionResult {
        val row = api.updateUserFlight(id, patch)
        if (row == null) {
            trackedDao.delete(id)
            return ActionResult.NotFound
        }
        trackedDao.upsert(listOf(row.toEntity()))
        return ActionResult.Success
    }

    /** The cached tracking row, or the server's when this device has not synced it yet. */
    private suspend fun tracking(flightId: String): TrackedFlightEntity? =
        trackedDao.getByFlightId(flightId) ?: api.userFlightByFlightId(flightId)?.toEntity()

    private suspend fun trackingId(flightId: String): String? = tracking(flightId)?.id

    // ---- sync -----------------------------------------------------------------------------------

    override suspend fun refresh(): ActionResult = command {
        refreshMutex.withLock {
            val rows = api.userFlights()
            val flightIds = rows.map { it.flightId }.distinct()
            val views = if (flightIds.isEmpty()) emptyList() else api.flightViews(flightIds)
            val all = views + inboundFlights(views)
            transactions.inTransaction {
                airportDao.upsert(all.flatMap { it.airportEntities() }.distinctBy { it.iata })
                flightDao.upsert(all.map { it.toEntity() })
                trackedDao.clear()
                trackedDao.upsert(rows.map { it.toEntity() })
                flightDao.pruneUnreferenced(olderThan = clock.now() - CACHE_RETENTION)
            }
            ActionResult.Success
        }
    }

    override suspend fun applyServerSync(flightId: String, compactFlightJson: String?): ActionResult = withContext(io) {
        val merged = compactFlightJson != null && mergeCompact(flightId, compactFlightJson)
        val fetched = command {
            val views = api.flightViews(listOf(flightId))
            if (views.isEmpty()) return@command ActionResult.NotFound
            cacheFlights(views + inboundFlights(views))
            ActionResult.Success
        }
        if (merged) ActionResult.Success else fetched
    }

    private suspend fun mergeCompact(flightId: String, compactFlightJson: String): Boolean {
        val dto = runCatching { json.decodeFromString(CompactFlightDto.serializer(), compactFlightJson) }.getOrNull()
        if (dto == null || dto.id != flightId) return false
        return transactions.inTransaction {
            val merged = dto.mergeInto(flightDao.get(flightId)) ?: return@inTransaction false
            flightDao.upsert(listOf(merged))
            true
        }
    }

    /**
     * Pro users get `inbound_flight_id`; that flight's row feeds "Where's my plane". It is optional
     * data, so a failure here must not fail the sync it belongs to.
     */
    private suspend fun inboundFlights(views: List<FlightViewDto>): List<FlightViewDto> {
        val known = views.mapTo(HashSet()) { it.id }
        val inboundIds = views.mapNotNull { it.inboundFlightId }.filterNot { it in known }.distinct()
        if (inboundIds.isEmpty()) return emptyList()
        return try {
            api.flightViews(inboundIds)
        } catch (_: BackendException) {
            emptyList()
        }
    }

    private suspend fun cacheFlights(views: List<FlightViewDto>) {
        transactions.inTransaction {
            airportDao.upsert(views.flatMap { it.airportEntities() }.distinctBy { it.iata })
            flightDao.upsert(views.map { it.toEntity() })
        }
    }

    /** Stores a tracking row; its flight is re-fetched so the list shows it even without a prior search. */
    private suspend fun storeTracking(row: UserFlightDto) {
        val views = try {
            api.flightViews(listOf(row.flightId))
        } catch (_: BackendException) {
            emptyList() // the tracking itself succeeded; the flight row arrives with the next refresh
        }
        transactions.inTransaction {
            airportDao.upsert(views.flatMap { it.airportEntities() }.distinctBy { it.iata })
            flightDao.upsert(views.map { it.toEntity() })
            trackedDao.upsert(listOf(row.toEntity()))
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private suspend fun requireSession() {
        when (val result = auth.ensureSignedIn()) {
            AuthResult.Success, AuthResult.GoogleAccountInUse -> Unit
            AuthResult.NotConfigured -> throw BackendException.NotConfigured()
            AuthResult.Offline -> throw BackendException.Network(IOException("offline while signing in"))
            is AuthResult.Failed -> throw BackendException.Http(401, "sign_in_failed", result.message)
        }
    }

    /** Runs a signed-in backend command on the IO dispatcher and maps failures to [ActionResult]. */
    private suspend fun command(block: suspend () -> ActionResult): ActionResult = withContext(io) {
        try {
            requireSession()
            block()
        } catch (e: BackendException) {
            when (e) {
                is BackendException.NotConfigured -> ActionResult.NotConfigured
                is BackendException.Network -> ActionResult.Offline
                is BackendException.Malformed -> ActionResult.Failed(e.message)
                is BackendException.Http ->
                    if (e.isTrackingLimit) ActionResult.TrackingLimitReached else ActionResult.Failed(e.message)
            }
        }
    }

    private sealed interface Checked {
        data class Valid(val pnr: String?, val seat: String?, val note: String?) : Checked
        data class Invalid(val field: TrackField) : Checked
    }

    /** Applies the `user_flights` check constraints up front, so the user gets a field-level error. */
    private fun validate(extras: TrackExtras): Checked {
        val pnrText = extras.pnr?.takeIf { it.isNotBlank() }
        val pnr = pnrText?.let { normalizePnr(it) ?: return Checked.Invalid(TrackField.PNR) }
        val seat = extras.seat?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (seat != null && seat.length > TrackExtras.MAX_SEAT_LENGTH) return Checked.Invalid(TrackField.SEAT)
        val note = extras.note?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && note.length > TrackExtras.MAX_NOTE_LENGTH) return Checked.Invalid(TrackField.NOTE)
        return Checked.Valid(pnr, seat, note)
    }

    private companion object {
        /** Untracked flights (old search results) are dropped from the cache after this long. */
        val CACHE_RETENTION = 7.days

        const val SEARCH_FLIGHT_FUNCTION = "search-flight"
        const val SEARCH_ROUTE_FUNCTION = "search-route"
        const val MODE_PAST = "past"
    }
}
