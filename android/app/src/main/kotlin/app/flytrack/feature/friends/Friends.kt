package app.flytrack.feature.friends

import app.flytrack.core.model.Airport
import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.LivePosition
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

// ---- invite codes (P2 wave 2, section 1) -----------------------------------------------------------

/** The 31 characters of a friend code: no 0/O, 1/I/L look-alikes. */
const val INVITE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
const val INVITE_CODE_LENGTH = 8

/**
 * A typed or pasted code in its canonical form ("ABCDEFGH"), or null when it cannot be a code. Spaces and
 * dashes are ignored and letters upper-cased, like the server's `accept_friend_invite`.
 */
fun normalizeInviteCode(input: String): String? {
    val code = input.uppercase().filterNot { it.isWhitespace() || it == '-' }
    return code.takeIf { it.length == INVITE_CODE_LENGTH && it.all { c -> c in INVITE_ALPHABET } }
}

/**
 * Finds a friend code in pasted text, such as the whole invite message ("... code ABCD-EFGH ..."). A bare
 * code wins; then the first "ABCD-EFGH" group (the form the invite message uses); then the first 8-character
 * word made only of the alphabet that also contains a digit, so ordinary words like "TRAVELER" are not
 * taken for a code. Matching is case-insensitive.
 */
fun extractInviteCode(text: String): String? {
    normalizeInviteCode(text)?.let { return it }
    val upper = text.uppercase()
    DASHED_CODE.find(upper)?.let { return it.value.replace("-", "") }
    return PLAIN_CODE.findAll(upper).map { it.value }.firstOrNull { candidate -> candidate.any { it.isDigit() } }
}

private val DASHED_CODE = Regex("(?<![A-Z0-9-])[$INVITE_ALPHABET]{4}-[$INVITE_ALPHABET]{4}(?![A-Z0-9-])")
private val PLAIN_CODE = Regex("(?<![A-Z0-9-])[$INVITE_ALPHABET]{8}(?![A-Z0-9-])")

/** "ABCD-EFGH": easier to read aloud and to type. The server ignores the dash. */
fun formatInviteCode(code: String): String =
    if (code.length == INVITE_CODE_LENGTH) "${code.take(4)}-${code.drop(4)}" else code

// ---- models ----------------------------------------------------------------------------------------

/** One row of `my_friends()`. [displayName] null = the friend set no name ("Your friend"). */
data class Friend(
    val id: String,
    val displayName: String?,
    /** I get alerts for this friend's flights. */
    val alertsEnabled: Boolean,
    val since: Instant?,
)

/** One row of `friends_flights()`: a flight a friend tracks and shares. */
data class FriendFlight(
    val friendId: String,
    val friendName: String?,
    val flight: Flight,
)

/** `accept_friend_invite` succeeded. */
data class AcceptedFriend(val friendId: String, val displayName: String?)

// ---- wire format -------------------------------------------------------------------------------------

private val FriendsJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
private data class FriendDto(
    @SerialName("friend_id") val friendId: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("alerts_enabled") val alertsEnabled: Boolean = true,
    val since: String? = null,
)

@Serializable
private data class AcceptDto(
    @SerialName("friend_id") val friendId: String,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
private data class FriendFlightDto(
    @SerialName("friend_id") val friendId: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("flight_id") val flightId: String? = null,
    val flight: PublicFlightDto? = null,
)

/** `flight_public_json`: the shared-flight shape (migration 20261126000017). */
@Serializable
private data class PublicFlightDto(
    val carrier: String? = null,
    val number: String? = null,
    val date: String? = null,
    val airline: String? = null,
    val status: String? = null,
    @SerialName("sched_dep") val schedDep: String? = null,
    @SerialName("est_dep") val estDep: String? = null,
    @SerialName("act_dep") val actDep: String? = null,
    @SerialName("sched_arr") val schedArr: String? = null,
    @SerialName("est_arr") val estArr: String? = null,
    @SerialName("act_arr") val actArr: String? = null,
    val dep: EndpointDto? = null,
    val arr: EndpointDto? = null,
    val aircraft: AircraftDto? = null,
    val live: LiveDto? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
private data class EndpointDto(
    val iata: String? = null,
    val name: String? = null,
    val city: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val tz: String? = null,
    val terminal: String? = null,
    val gate: String? = null,
    val belt: String? = null,
)

@Serializable
private data class AircraftDto(val model: String? = null)

@Serializable
private data class LiveDto(
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("alt_ft") val altFt: Int? = null,
    @SerialName("speed_kts") val speedKts: Double? = null,
    val heading: Double? = null,
    val at: String? = null,
)

/** `my_friends()`: newest friend first, as the server orders them. Throws SerializationException on a wrong shape. */
fun parseFriends(json: JsonElement): List<Friend> {
    if (json is JsonNull) return emptyList()
    return FriendsJson.decodeFromJsonElement(ListSerializer(FriendDto.serializer()), json).map {
        Friend(it.friendId, cleanName(it.displayName), it.alertsEnabled, it.since.toInstantOrNull())
    }
}

/** `friends_flights()`. Rows without a usable flight (no id, carrier, number or date) are skipped. */
fun parseFriendsFlights(json: JsonElement): List<FriendFlight> {
    if (json is JsonNull) return emptyList()
    return FriendsJson.decodeFromJsonElement(ListSerializer(FriendFlightDto.serializer()), json).mapNotNull { row ->
        val flight = row.flight?.toFlight(row.flightId ?: return@mapNotNull null) ?: return@mapNotNull null
        FriendFlight(row.friendId, cleanName(row.displayName), flight)
    }
}

/** `accept_friend_invite`. */
fun parseAcceptedFriend(json: JsonElement): AcceptedFriend {
    val dto = FriendsJson.decodeFromJsonElement(AcceptDto.serializer(), json)
    return AcceptedFriend(dto.friendId, cleanName(dto.displayName))
}

/** A `text` RPC answer (`create_friend_invite`, `my_forwarding_address`, `my_calendar_url`); null when empty. */
fun textAnswer(json: JsonElement): String? = (json as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }

private fun cleanName(name: String?): String? = name?.trim()?.ifEmpty { null }

private fun String?.toInstantOrNull(): Instant? = this?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun EndpointDto.toAirport(): Airport? {
    val code = iata ?: return null
    val zone = tz ?: return null
    return Airport(code, name ?: code, city, zone, lat ?: return null, lon ?: return null)
}

private fun PublicFlightDto.toFlight(id: String): Flight? {
    val carrierIata = carrier?.takeIf { it.isNotBlank() } ?: return null
    val flightNumber = number?.takeIf { it.isNotBlank() } ?: return null
    val flightDate = date?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return null
    val depIata = dep?.iata ?: return null
    val arrIata = arr?.iata ?: return null
    return Flight(
        id = id, carrierIata = carrierIata, flightNumber = flightNumber, flightDate = flightDate,
        depIata = depIata, arrIata = arrIata, airlineName = airline, status = FlightStatus.fromWire(status),
        schedDep = schedDep.toInstantOrNull(), estDep = estDep.toInstantOrNull(), actDep = actDep.toInstantOrNull(),
        depTerminal = dep.terminal, depGate = dep.gate, depCheckin = null,
        schedArr = schedArr.toInstantOrNull(), estArr = estArr.toInstantOrNull(), actArr = actArr.toInstantOrNull(),
        arrTerminal = arr.terminal, arrGate = arr.gate, baggageBelt = arr.belt,
        aircraftModel = aircraft?.model, aircraftReg = null, delayReason = null,
        live = live?.let { l ->
            if (l.lat == null || l.lon == null) null else LivePosition(l.lat, l.lon, l.altFt, l.heading, l.speedKts, l.at.toInstantOrNull())
        },
        lastPolledAt = null, updatedAt = updatedAt.toInstantOrNull(),
        depAirport = dep.toAirport(), arrAirport = arr.toAirport(),
        inboundFlightId = null, prediction = null, hasProInsight = false,
    )
}
