package app.flytrack.data.remote

import app.flytrack.core.model.AlertType
import app.flytrack.core.model.Cabin
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.Plan
import app.flytrack.core.model.TrackSource
import app.flytrack.core.model.departureDelayMinutes
import app.flytrack.data.local.FlightWithAirports
import app.flytrack.data.remote.dto.AirportDto
import app.flytrack.data.remote.dto.CompactFlightDto
import app.flytrack.data.remote.dto.FlightViewDto
import app.flytrack.data.remote.dto.FunctionErrorDto
import app.flytrack.data.remote.dto.ProfileDto
import app.flytrack.data.remote.dto.RegisterDeviceParams
import app.flytrack.data.remote.dto.SearchFlightRequest
import app.flytrack.data.remote.dto.SearchFlightResponse
import app.flytrack.data.remote.dto.UserFlightDto
import app.flytrack.data.remote.dto.UserFlightInsertDto
import app.flytrack.testing.Fixtures
import kotlinx.datetime.LocalDate
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * The fixtures were produced by the real schema (migration + seed on Postgres) and by the real
 * notify.ts, so these tests fail when a DTO drifts from the SQL column names or the push payload.
 */
@OptIn(ExperimentalSerializationApi::class)
class MappersTest {
    private val json = FlyTrackJson

    private fun flightView(name: String) = json.decodeFromString(FlightViewDto.serializer(), Fixtures.read(name))

    private fun columns(name: String) = Fixtures.read(name).trim().split(',').toSet()

    // ---- column contracts -----------------------------------------------------------------------

    @Test
    fun `FlightViewDto has exactly the columns of flight_view`() {
        assertEquals(columns("view_columns.txt"), FlightViewDto.serializer().descriptor.elementNames.toSet())
    }

    @Test
    fun `UserFlightDto has exactly the columns of user_flights`() {
        assertEquals(columns("user_flights_columns.txt"), UserFlightDto.serializer().descriptor.elementNames.toSet())
    }

    @Test
    fun `insert body only uses insertable user_flights columns and omits nulls`() {
        val names = UserFlightInsertDto.serializer().descriptor.elementNames.toSet()
        assertTrue(columns("user_flights_columns.txt").containsAll(names))
        assertFalse("user_id comes from the column default auth.uid()", "user_id" in names)

        val body = json.encodeToString(UserFlightInsertDto.serializer(), UserFlightInsertDto(flightId = "f1", pnr = "ABC123"))
        assertEquals("""{"flight_id":"f1","pnr":"ABC123","source":"manual"}""", body)
    }

    @Test
    fun `register_device parameters are named like the SQL function's`() {
        val body = json.encodeToString(
            RegisterDeviceParams.serializer(),
            RegisterDeviceParams("tok-0123456789abcdefghij", "0.1.0", "16", "en-IN", "Asia/Kolkata"),
        )
        assertEquals(
            """{"p_token":"tok-0123456789abcdefghij","p_app_version":"0.1.0","p_os_version":"16","p_locale":"en-IN","p_timezone":"Asia/Kolkata"}""",
            body,
        )
        // optional parameters are left to their SQL defaults
        assertEquals("""{"p_token":"t"}""", json.encodeToString(RegisterDeviceParams.serializer(), RegisterDeviceParams("t")))
        assertEquals("5dd51af7-51c8-4cc5-a714-0c969a49858b", json.decodeFromString(String.serializer(), Fixtures.read("register_device_result.json")))
    }

    @Test
    fun `search request body`() {
        assertEquals(
            """{"flight":"6E 2341","date":"2026-11-25"}""",
            json.encodeToString(SearchFlightRequest.serializer(), SearchFlightRequest("6E 2341", "2026-11-25")),
        )
    }

    // ---- flight_view ------------------------------------------------------------------------------

    @Test
    fun `flight_view row of a Pro user maps to the domain model`() {
        val flight = flightView("flight_view_pro.json").toDomain()

        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", flight.id)
        assertEquals("6E 2341", flight.designator)
        assertEquals(LocalDate.parse("2026-11-25"), flight.flightDate)
        assertEquals("DEL", flight.depIata)
        assertEquals("BOM", flight.arrIata)
        assertEquals("IndiGo", flight.airlineName)
        assertEquals(FlightStatus.SCHEDULED, flight.status)
        assertEquals(Instant.parse("2026-11-25T12:55:00Z"), flight.schedDep)
        assertEquals(Instant.parse("2026-11-25T13:20:00Z"), flight.estDep)
        assertNull(flight.actDep)
        assertEquals(25, flight.departureDelayMinutes)
        assertEquals("1", flight.depTerminal)
        assertEquals("29", flight.depGate)
        assertEquals("Row C", flight.depCheckin)
        assertEquals(Instant.parse("2026-11-25T15:10:00Z"), flight.schedArr)
        assertEquals(Instant.parse("2026-11-25T15:35:00Z"), flight.estArr)
        assertEquals("2", flight.arrTerminal)
        assertNull(flight.baggageBelt)
        assertEquals("Airbus A321neo", flight.aircraftModel)
        assertEquals("VT-ISR", flight.aircraftReg)
        assertEquals("Late arrival of the incoming aircraft", flight.delayReason)
        assertNull(flight.live)
        assertEquals(Instant.parse("2026-11-25T10:41:07.123456Z"), flight.lastPolledAt)
        assertNotNull(flight.updatedAt)

        val dep = flight.depAirport!!
        assertEquals("Indira Gandhi International Airport", dep.name)
        assertEquals("New Delhi", dep.city)
        assertEquals("Asia/Kolkata", dep.tz)
        assertEquals(28.5665, dep.lat, 1e-6)
        assertEquals(77.1031, dep.lon, 1e-6)
        assertEquals("Mumbai", flight.arrAirport!!.city)
        assertEquals("Asia/Kolkata", flight.arrTz)
    }

    @Test
    fun `Pro fields are mapped and the server-internal prediction keys are not needed`() {
        val flight = flightView("flight_view_pro.json").toDomain()

        assertTrue(flight.hasProInsight)
        assertEquals("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", flight.inboundFlightId)
        val prediction = flight.prediction!!
        assertEquals(45, prediction.lateMin)
        assertEquals(Instant.parse("2026-11-25T13:40:00Z"), prediction.predictedDep)
        assertEquals(Instant.parse("2026-11-25T10:41:07.200Z"), prediction.computedAt)
        assertEquals("Inbound aircraft (6E 2032 from BLR) is running 50 min late", prediction.reason)
        val inbound = prediction.inbound!!
        assertEquals("6E 2032", inbound.flight)
        assertEquals("BLR", inbound.fromIata)
        assertEquals(FlightStatus.EN_ROUTE, inbound.status)
        assertEquals(Instant.parse("2026-11-25T12:05:00Z"), inbound.estArr)
        assertNull(inbound.actArr)
        assertEquals(36000, inbound.live?.altFt)
        assertEquals(452.3, inbound.live?.speedKts!!, 1e-6)
    }

    @Test
    fun `free users get the teaser flag but no Pro data`() {
        val flight = flightView("flight_view_free.json").toDomain()
        assertTrue(flight.hasProInsight)
        assertNull(flight.prediction)
        assertNull(flight.inboundFlightId)
    }

    @Test
    fun `row with every optional column null and unknown airports still maps`() {
        val dto = flightView("flight_view_minimal.json")
        val flight = dto.toDomain()

        assertEquals("AI 101A", flight.designator)
        assertEquals(FlightStatus.UNKNOWN, flight.status)
        assertNull(flight.schedDep)
        assertNull(flight.depAirport)
        assertNull(flight.arrAirport)
        assertNull(flight.depTz)
        assertFalse("SQL NULL in has_pro_insight means no insight", flight.hasProInsight)
        assertTrue(dto.airportEntities().isEmpty())
    }

    @Test
    fun `live position of an airborne flight`() {
        val inboundLeg = json.decodeFromString(SearchFlightResponse.serializer(), Fixtures.read("search_response.json"))
            .flights.single { it.flightNumber == "2032" }.toDomain()
        val live = inboundLeg.live!!
        assertEquals(21.1458, live.lat, 1e-6)
        assertEquals(79.0882, live.lon, 1e-6)
        assertEquals(36000, live.altFt)
        assertEquals(12.5, live.headingDeg!!, 1e-6)
        assertEquals(Instant.parse("2026-11-25T10:40:00Z"), live.at)
        assertEquals(Instant.parse("2026-11-25T09:14:00Z"), inboundLeg.actDep)
    }

    @Test
    fun `entity round trip keeps every field and splits the airports off`() {
        val dto = flightView("flight_view_pro.json")
        val entity = dto.toEntity()
        val airports = dto.airportEntities()

        assertEquals(listOf("DEL", "BOM"), airports.map { it.iata })
        assertEquals("scheduled", entity.status)
        assertEquals("2026-11-25", entity.flightDate)
        val viaRoom = FlightWithAirports(entity, airports[0], airports[1]).toDomain()
        assertEquals(dto.toDomain(), viaRoom)
    }

    @Test
    fun `unknown status and unreadable prediction do not crash`() {
        val dto = flightView("flight_view_pro.json").copy(status = "teleporting")
        assertEquals(FlightStatus.UNKNOWN, dto.toDomain().status)
        assertNull(parsePrediction("{not json"))
        assertNull(parsePrediction(""))
        assertNull(parsePrediction(null))
    }

    @Test
    fun `timestamps in every format the backend emits`() {
        val expected = Instant.parse("2026-11-25T13:20:00Z")
        assertEquals(expected, parseInstantOrNull("2026-11-25T13:20:00+00:00")) // PostgREST
        assertEquals(expected, parseInstantOrNull("2026-11-25T13:20:00Z")) // provider snapshots
        assertEquals(expected, parseInstantOrNull("2026-11-25T13:20:00.000Z")) // Date.toISOString()
        assertEquals(expected, parseInstantOrNull("2026-11-25T18:50:00+05:30"))
        assertEquals(Instant.parse("2026-11-22T13:45:13.567170Z"), parseInstantOrNull("2026-11-22T13:45:13.56717+00:00"))
        assertNull(parseInstantOrNull(null))
        assertNull(parseInstantOrNull(""))
        assertNull(parseInstantOrNull("--:--"))
    }

    // ---- user_flights / airports / profiles ---------------------------------------------------------

    @Test
    fun `user_flights rows map to tracked flights`() {
        val rows = json.decodeFromString(ListSerializer(UserFlightDto.serializer()), Fixtures.read("user_flights.json"))
        val (active, archived) = rows.map { it.toEntity().toDomain() }

        assertEquals("dddddddd-dddd-4ddd-8ddd-dddddddddddd", active.id)
        assertEquals("11111111-1111-4111-8111-111111111111", active.userId)
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", active.flightId)
        assertEquals("ABC123", active.pnr)
        assertEquals("14C", active.seat)
        assertEquals(Cabin.ECONOMY, active.cabin)
        assertEquals(TrackSource.PDF, active.source)
        assertEquals("Window seat", active.note)
        assertFalse(active.isArchived)
        assertNotNull(active.createdAt)
        assertEquals(mapOf(AlertType.DEPARTED to false, AlertType.LANDED to true), active.notifyPrefs)

        assertTrue(archived.isArchived)
        assertEquals(Instant.parse("2026-11-20T09:00:00Z"), archived.archivedAt)
        assertEquals(TrackSource.MANUAL, archived.source)
        assertNull(archived.cabin)
        assertTrue(archived.notifyPrefs.isEmpty())
    }

    @Test
    fun `alert types this app does not know stay in the stored preferences`() {
        val row = json.decodeFromString(ListSerializer(UserFlightDto.serializer()), Fixtures.read("user_flights.json")).first()
        assertEquals(false, row.toEntity().notifyPrefs["FUTURE_TYPE"])
    }

    @Test
    fun `airports rows`() {
        val airports = json.decodeFromString(ListSerializer(AirportDto.serializer()), Fixtures.read("airports.json"))
        val bom = airports.first().toEntity().toDomain()
        assertEquals("BOM", bom.iata)
        assertEquals("Mumbai", bom.displayCity)
        assertEquals("Asia/Kolkata", bom.tz)
        assertEquals(19.0887, bom.lat, 1e-6)
        assertEquals("VIDP", airports[1].icao)
        assertEquals("IN", airports[1].country)
    }

    @Test
    fun `profile row`() {
        val profile = json.decodeFromString(ListSerializer(ProfileDto.serializer()), Fixtures.read("profile.json")).single()
            .toEntity().toDomain()
        assertEquals("Asha", profile.displayName)
        assertEquals(Plan.PRO, profile.plan)
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), profile.proUntil)
        assertTrue(profile.isPro(Instant.parse("2026-11-22T10:00:00Z")))
    }

    // ---- function errors ----------------------------------------------------------------------------

    @Test
    fun `function error bodies`() {
        fun error(name: String) = json.decodeFromString(FunctionErrorDto.serializer(), Fixtures.read(name))
        assertEquals("not_found", error("search_error_not_found.json").error)
        assertEquals("rate_limited", error("search_error_rate_limited.json").error)
        assertEquals("bad_input", error("search_error_bad_input.json").error)
        assertEquals("Date must be between yesterday and one year ahead", error("search_error_bad_input.json").message)
        assertEquals("provider_error", error("search_error_provider.json").error)
        assertNull(json.decodeFromString(FunctionErrorDto.serializer(), """{"error":"internal"}""").message)
    }

    // ---- FCM sync payload ---------------------------------------------------------------------------

    private fun compactPayload(): CompactFlightDto {
        val sync = json.parseToJsonElement(Fixtures.read("fcm_messages.json")).jsonObject.getValue("sync").jsonObject
        assertEquals("sync", sync.getValue("kind").jsonPrimitive.content)
        return json.decodeFromString(CompactFlightDto.serializer(), sync.getValue("flight").jsonPrimitive.content)
    }

    @Test
    fun `compact sync payload updates the cached flight and keeps what it does not carry`() {
        val cached = flightView("flight_view_pro.json").copy(updatedAt = "2026-11-25T10:41:07+00:00").toEntity()

        val merged = compactPayload().mergeInto(cached)!!

        assertEquals("boarding", merged.status)
        assertEquals("31", merged.depGate)
        assertEquals(Instant.parse("2026-11-25T13:35:00Z"), merged.estDep)
        assertEquals(Instant.parse("2026-11-25T15:50:00Z"), merged.estArr)
        assertEquals(Instant.parse("2026-11-25T12:41:10.500Z"), merged.updatedAt)
        // not part of the payload: kept from the cache
        assertEquals("IndiGo", merged.airlineName)
        assertEquals("Row C", merged.depCheckin)
        assertEquals("Late arrival of the incoming aircraft", merged.delayReason)
        assertEquals(cached.prediction, merged.prediction)
        assertEquals(cached.inboundFlightId, merged.inboundFlightId)
        assertTrue(merged.hasProInsight)
        assertEquals(cached.lastPolledAt, merged.lastPolledAt)
    }

    @Test
    fun `compact sync payload older than the cache is ignored`() {
        val cached = flightView("flight_view_pro.json").copy(updatedAt = "2026-11-25T12:45:00+00:00").toEntity()
        assertNull(compactPayload().mergeInto(cached))
    }

    @Test
    fun `compact sync payload creates the row when nothing is cached`() {
        val created = compactPayload().mergeInto(null)!!
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", created.id)
        assertEquals("6E", created.carrierIata)
        assertEquals("2341", created.flightNumber)
        assertEquals("2026-11-25", created.flightDate)
        assertEquals("DEL", created.depIata)
        assertEquals("BOM", created.arrIata)
        assertEquals("VT-ISR", created.aircraftReg)
        assertFalse(created.hasProInsight)
        assertNull(created.prediction)
        assertNull(compactPayload().copy(flightDate = "garbage").mergeInto(null))
    }

    @Test
    fun `compact sync payload carries the live position`() {
        val airborne = compactPayload().copy(liveLat = 22.5, liveLon = 75.1, liveAltFt = 35000, liveUpdatedAt = "2026-11-25T14:00:00Z")
        val cached = flightView("flight_view_pro.json").copy(updatedAt = null).toEntity().copy(liveHeading = 200.0, liveSpeedKts = 440.0)

        val merged = airborne.mergeInto(cached)!!
        assertEquals(22.5, merged.liveLat!!, 1e-6)
        assertEquals(35000, merged.liveAltFt)
        assertEquals(200.0, merged.liveHeading!!, 1e-6)

        val landed = compactPayload().mergeInto(merged)!!
        assertNull(landed.liveLat)
        assertNull("heading without a position is meaningless", landed.liveHeading)
    }
}
