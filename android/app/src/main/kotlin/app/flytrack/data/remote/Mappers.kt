package app.flytrack.data.remote

import app.flytrack.core.model.Airport
import app.flytrack.core.model.AlertType
import app.flytrack.core.model.Cabin
import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.InboundAircraft
import app.flytrack.core.model.InboundPrediction
import app.flytrack.core.model.LivePosition
import app.flytrack.core.model.Plan
import app.flytrack.core.model.TrackSource
import app.flytrack.core.model.TrackedFlight
import app.flytrack.core.model.TrackedFlightDetails
import app.flytrack.core.model.UserProfile
import app.flytrack.data.local.AirportEntity
import app.flytrack.data.local.FlightEntity
import app.flytrack.data.local.FlightWithAirports
import app.flytrack.data.local.ProfileEntity
import app.flytrack.data.local.TrackedFlightEntity
import app.flytrack.data.local.TrackedFlightWithFlight
import app.flytrack.data.remote.dto.AirportDto
import app.flytrack.data.remote.dto.CompactFlightDto
import app.flytrack.data.remote.dto.FlightViewDto
import app.flytrack.data.remote.dto.LivePositionDto
import app.flytrack.data.remote.dto.PredictionDto
import app.flytrack.data.remote.dto.ProfileDto
import app.flytrack.data.remote.dto.UserFlightDto
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Parses the timestamps the backend sends: PostgREST's "2026-11-25T13:20:00+00:00" (with or
 * without fractional seconds) and the functions' "2026-11-25T13:20:00.000Z". Garbage becomes null.
 */
fun parseInstantOrNull(text: String?): Instant? =
    text?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun parseDateOrNull(text: String?): LocalDate? =
    text?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

// ---- flight_view -> Room ------------------------------------------------------------------------

fun FlightViewDto.toEntity(): FlightEntity = FlightEntity(
    id = id,
    carrierIata = carrierIata.trim(),
    flightNumber = flightNumber.trim(),
    flightDate = flightDate.trim(),
    depIata = depIata.trim(),
    arrIata = arrIata.trim(),
    airlineName = airlineName,
    status = status,
    schedDep = parseInstantOrNull(schedDep),
    estDep = parseInstantOrNull(estDep),
    actDep = parseInstantOrNull(actDep),
    depTerminal = depTerminal,
    depGate = depGate,
    depCheckin = depCheckin,
    schedArr = parseInstantOrNull(schedArr),
    estArr = parseInstantOrNull(estArr),
    actArr = parseInstantOrNull(actArr),
    arrTerminal = arrTerminal,
    arrGate = arrGate,
    baggageBelt = baggageBelt,
    aircraftModel = aircraftModel,
    aircraftReg = aircraftReg,
    delayReason = delayReason,
    liveLat = liveLat,
    liveLon = liveLon,
    liveAltFt = liveAltFt,
    liveHeading = liveHeading,
    liveSpeedKts = liveSpeedKts,
    liveUpdatedAt = parseInstantOrNull(liveUpdatedAt),
    lastPolledAt = parseInstantOrNull(lastPolledAt),
    updatedAt = parseInstantOrNull(updatedAt),
    inboundFlightId = inboundFlightId,
    prediction = prediction?.toString(),
    hasProInsight = hasProInsight == true,
)

private fun airportOrNull(iata: String, name: String?, city: String?, tz: String?, lat: Double?, lon: Double?): AirportEntity? =
    if (name == null || tz == null || lat == null || lon == null) null
    else AirportEntity(iata = iata.trim(), name = name, city = city, tz = tz, lat = lat, lon = lon)

/** The departure airport embedded in the view row; null when the backend does not know the airport. */
fun FlightViewDto.depAirportEntity(): AirportEntity? = airportOrNull(depIata, depName, depCity, depTz, depLat, depLon)

fun FlightViewDto.arrAirportEntity(): AirportEntity? = airportOrNull(arrIata, arrName, arrCity, arrTz, arrLat, arrLon)

fun FlightViewDto.airportEntities(): List<AirportEntity> = listOfNotNull(depAirportEntity(), arrAirportEntity())

/** Straight to the domain model, for results that are shown before Room is read (search). */
fun FlightViewDto.toDomain(): Flight = FlightWithAirports(toEntity(), depAirportEntity(), arrAirportEntity()).toDomain()

// ---- FCM sync payload -> Room -------------------------------------------------------------------

/**
 * Applies a compact push payload on top of the cached row. Fields the payload does not carry
 * (airline name, check-in desk, delay reason, Pro fields) are kept from [existing].
 * Returns null when the payload is older than what is cached, or is not usable at all.
 */
fun CompactFlightDto.mergeInto(existing: FlightEntity?): FlightEntity? {
    val updated = parseInstantOrNull(updatedAt)
    val cachedAt = existing?.updatedAt
    if (cachedAt != null && updated != null && updated < cachedAt) return null
    if (parseDateOrNull(flightDate) == null) return null
    return FlightEntity(
        id = id,
        carrierIata = carrierIata.trim(),
        flightNumber = flightNumber.trim(),
        flightDate = flightDate.trim(),
        depIata = depIata.trim(),
        arrIata = arrIata.trim(),
        airlineName = existing?.airlineName,
        status = status,
        schedDep = parseInstantOrNull(schedDep),
        estDep = parseInstantOrNull(estDep),
        actDep = parseInstantOrNull(actDep),
        depTerminal = depTerminal,
        depGate = depGate,
        depCheckin = existing?.depCheckin,
        schedArr = parseInstantOrNull(schedArr),
        estArr = parseInstantOrNull(estArr),
        actArr = parseInstantOrNull(actArr),
        arrTerminal = arrTerminal,
        arrGate = arrGate,
        baggageBelt = baggageBelt,
        aircraftModel = aircraftModel,
        aircraftReg = aircraftReg,
        delayReason = existing?.delayReason,
        liveLat = liveLat,
        liveLon = liveLon,
        liveAltFt = liveAltFt,
        liveHeading = existing?.liveHeading.takeIf { liveLat != null },
        liveSpeedKts = existing?.liveSpeedKts.takeIf { liveLat != null },
        liveUpdatedAt = parseInstantOrNull(liveUpdatedAt),
        lastPolledAt = existing?.lastPolledAt,
        updatedAt = updated ?: cachedAt,
        inboundFlightId = existing?.inboundFlightId,
        prediction = existing?.prediction,
        hasProInsight = existing?.hasProInsight ?: false,
    )
}

// ---- Room -> domain -----------------------------------------------------------------------------

fun AirportEntity.toDomain(): Airport = Airport(iata = iata, name = name, city = city, tz = tz, lat = lat, lon = lon)

fun AirportDto.toEntity(): AirportEntity =
    AirportEntity(iata = iata.trim(), name = name, city = city, tz = tz, lat = lat, lon = lon)

private fun livePosition(lat: Double?, lon: Double?, altFt: Int?, heading: Double?, speed: Double?, at: Instant?): LivePosition? =
    if (lat == null || lon == null) null else LivePosition(lat, lon, altFt, heading, speed, at)

private fun LivePositionDto.toDomain(): LivePosition =
    LivePosition(lat, lon, altFt, headingDeg, speedKts, parseInstantOrNull(at))

/** Decodes the stored prediction JSON; an unreadable prediction is treated as "no prediction". */
internal fun parsePrediction(json: String?): InboundPrediction? {
    if (json.isNullOrBlank()) return null
    val dto = runCatching { FlyTrackJson.decodeFromString(PredictionDto.serializer(), json) }.getOrNull() ?: return null
    return InboundPrediction(
        computedAt = parseInstantOrNull(dto.computedAt),
        predictedDep = parseInstantOrNull(dto.predictedDep),
        lateMin = dto.lateMin,
        reason = dto.reason,
        inbound = dto.inbound?.let {
            InboundAircraft(
                flight = it.flight,
                fromIata = it.from,
                status = FlightStatus.fromWire(it.status),
                schedArr = parseInstantOrNull(it.schedArr),
                estArr = parseInstantOrNull(it.estArr),
                actArr = parseInstantOrNull(it.actArr),
                live = it.live?.toDomain(),
            )
        },
    )
}

fun FlightWithAirports.toDomain(): Flight = with(flight) {
    Flight(
        id = id,
        carrierIata = carrierIata,
        flightNumber = flightNumber,
        // The server guarantees an ISO date; the epoch is only a non-crashing stand-in for corrupt data.
        flightDate = parseDateOrNull(flightDate) ?: LocalDate.fromEpochDays(0),
        depIata = depIata,
        arrIata = arrIata,
        airlineName = airlineName,
        status = FlightStatus.fromWire(status),
        schedDep = schedDep,
        estDep = estDep,
        actDep = actDep,
        depTerminal = depTerminal,
        depGate = depGate,
        depCheckin = depCheckin,
        schedArr = schedArr,
        estArr = estArr,
        actArr = actArr,
        arrTerminal = arrTerminal,
        arrGate = arrGate,
        baggageBelt = baggageBelt,
        aircraftModel = aircraftModel,
        aircraftReg = aircraftReg,
        delayReason = delayReason,
        live = livePosition(liveLat, liveLon, liveAltFt, liveHeading, liveSpeedKts, liveUpdatedAt),
        lastPolledAt = lastPolledAt,
        updatedAt = updatedAt,
        depAirport = depAirport?.toDomain(),
        arrAirport = arrAirport?.toDomain(),
        inboundFlightId = inboundFlightId,
        prediction = parsePrediction(prediction),
        hasProInsight = hasProInsight,
    )
}

// ---- user_flights -------------------------------------------------------------------------------

fun UserFlightDto.toEntity(): TrackedFlightEntity = TrackedFlightEntity(
    id = id,
    userId = userId,
    flightId = flightId,
    pnr = pnr,
    seat = seat,
    cabin = cabin,
    source = source,
    notifyPrefs = notifyPrefs,
    note = note,
    archivedAt = parseInstantOrNull(archivedAt),
    createdAt = parseInstantOrNull(createdAt),
)

fun TrackedFlightEntity.toDomain(): TrackedFlight = TrackedFlight(
    id = id,
    userId = userId,
    flightId = flightId,
    pnr = pnr,
    seat = seat,
    cabin = Cabin.fromWire(cabin),
    source = TrackSource.fromWire(source),
    notifyPrefs = notifyPrefs.mapNotNull { (key, value) -> AlertType.fromWire(key)?.let { it to value } }.toMap(),
    note = note,
    archivedAt = archivedAt,
    createdAt = createdAt,
)

fun TrackedFlightWithFlight.toDomain(): TrackedFlightDetails =
    TrackedFlightDetails(tracking = tracking.toDomain(), flight = flight.toDomain())

// ---- profiles -----------------------------------------------------------------------------------

fun ProfileDto.toEntity(): ProfileEntity =
    ProfileEntity(
        id = id,
        displayName = displayName,
        plan = plan,
        proUntil = parseInstantOrNull(proUntil),
        trialFlightId = trialFlightId,
    )

fun ProfileEntity.toDomain(): UserProfile =
    UserProfile(
        id = id,
        displayName = displayName,
        plan = Plan.fromWire(plan),
        proUntil = proUntil,
        trialFlightId = trialFlightId,
    )
