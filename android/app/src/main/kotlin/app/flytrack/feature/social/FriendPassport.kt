package app.flytrack.feature.social

import app.flytrack.feature.friends.RemoteResult
import app.flytrack.feature.friends.Wave2Caller
import app.flytrack.feature.passport.CountedAirline
import app.flytrack.feature.passport.LongestFlight
import app.flytrack.feature.passport.Passport
import app.flytrack.feature.passport.PassportScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

// P3 B10: compare Passports with a friend (`friend_passport`, migration 28). Pure models and parsing first,
// then the repository.

/** The numbers both sides of the comparison have. */
data class PassportSide(
    val flights: Int,
    val distanceKm: Int,
    val airports: Int,
    val countries: Int,
    /** Top three, most flown first. */
    val airlines: List<CountedAirline>,
    val longest: LongestFlight?,
)

/** `friend_passport(p_friend, p_year)`: a friend's reduced Passport, under the caller's year rules. */
data class FriendPassport(
    val friendId: String,
    val displayName: String?,
    val scope: PassportScope,
    val year: Int,
    /** The caller is free and the answer covers only the last 12 months (same rule as their own Passport). */
    val proLimited: Boolean,
    val side: PassportSide,
    val countryCodes: List<String>,
)

/** The caller's own Passport reduced to the compared numbers. */
fun Passport.toSide(): PassportSide = PassportSide(
    flights = flights,
    distanceKm = distanceKm,
    airports = airports.size,
    countries = countries.size,
    airlines = airlines.take(TOP_AIRLINES),
    longest = longest,
)

const val TOP_AIRLINES = 3

/** One compared number. */
enum class CompareKind { FLIGHTS, DISTANCE_KM, AIRPORTS, COUNTRIES, LONGEST_KM }

/** Who has more of one number; null on a tie. */
enum class CompareLeader { ME, FRIEND }

data class CompareRow(val kind: CompareKind, val mine: Int, val friend: Int) {
    val leader: CompareLeader? get() = when {
        mine > friend -> CompareLeader.ME
        friend > mine -> CompareLeader.FRIEND
        else -> null
    }

    /** 0..1 bar lengths, relative to the larger value (both 0 when nobody has any). */
    val mineFraction: Float get() = fraction(mine)
    val friendFraction: Float get() = fraction(friend)

    private fun fraction(value: Int): Float {
        val max = maxOf(mine, friend)
        return if (max <= 0) 0f else value.toFloat() / max
    }
}

/** The side-by-side rows of the compare screen, in display order. */
fun comparePassports(mine: PassportSide, friend: PassportSide): List<CompareRow> = listOf(
    CompareRow(CompareKind.FLIGHTS, mine.flights, friend.flights),
    CompareRow(CompareKind.DISTANCE_KM, mine.distanceKm, friend.distanceKm),
    CompareRow(CompareKind.AIRPORTS, mine.airports, friend.airports),
    CompareRow(CompareKind.COUNTRIES, mine.countries, friend.countries),
    CompareRow(CompareKind.LONGEST_KM, mine.longest?.distanceKm ?: 0, friend.longest?.distanceKm ?: 0),
)

/** Countries both have flown to (ISO codes, sorted). */
fun sharedCountries(mine: List<String>, friend: List<String>): List<String> = mine.toSet().intersect(friend.toSet()).sorted()

// ---- wire format -------------------------------------------------------------------------------------

private val FriendPassportJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
private data class FriendPassportDto(
    @SerialName("friend_id") val friendId: String,
    @SerialName("display_name") val displayName: String? = null,
    val scope: String? = null,
    val year: Int? = null,
    @SerialName("pro_limited") val proLimited: Boolean = false,
    val flights: Int = 0,
    @SerialName("distance_km") val distanceKm: Int = 0,
    val airports: Int = 0,
    val countries: Int = 0,
    @SerialName("country_codes") val countryCodes: List<String>? = null,
    val airlines: List<AirlineDto>? = null,
    val longest: LongestDto? = null,
)

@Serializable private data class AirlineDto(val carrier: String? = null, val name: String? = null, val count: Int = 0)

@Serializable
private data class LongestDto(
    val carrier: String? = null,
    val number: String? = null,
    val dep: String? = null,
    val arr: String? = null,
    @SerialName("distance_km") val distanceKm: Int? = null,
)

/** Reads a `friend_passport` answer. Throws SerializationException on a wrong shape or a null answer. */
fun parseFriendPassport(json: JsonElement, fallbackYear: Int): FriendPassport {
    if (json is JsonNull) throw kotlinx.serialization.SerializationException("friend_passport returned null")
    val dto = FriendPassportJson.decodeFromJsonElement(FriendPassportDto.serializer(), json)
    val longest = dto.longest?.let { l ->
        if (l.carrier == null || l.number == null || l.dep == null || l.arr == null || l.distanceKm == null) {
            null
        } else {
            LongestFlight(null, l.carrier, l.number, l.dep, l.arr, l.distanceKm)
        }
    }
    return FriendPassport(
        friendId = dto.friendId,
        displayName = dto.displayName?.trim()?.ifEmpty { null },
        scope = PassportScope.fromWire(dto.scope),
        year = dto.year ?: fallbackYear,
        proLimited = dto.proLimited,
        side = PassportSide(
            flights = dto.flights.coerceAtLeast(0),
            distanceKm = dto.distanceKm.coerceAtLeast(0),
            airports = dto.airports.coerceAtLeast(0),
            countries = dto.countries.coerceAtLeast(0),
            airlines = dto.airlines.orEmpty().mapNotNull { a ->
                a.carrier?.takeIf { it.isNotBlank() }?.let { CountedAirline(it, a.name?.takeIf { n -> n.isNotBlank() }, a.count) }
            }.take(TOP_AIRLINES),
            longest = longest,
        ),
        countryCodes = dto.countryCodes.orEmpty().filter { it.isNotBlank() },
    )
}

/** Outcome of `friend_passport`. */
sealed interface FriendPassportResult {
    data class Loaded(val passport: FriendPassport) : FriendPassportResult

    /** Not an accepted friend, or they turned Passport sharing off (`not_shared`, 42501): the same on purpose. */
    data object NotShared : FriendPassportResult
    data object Offline : FriendPassportResult
    data object NotConfigured : FriendPassportResult

    /** [message] is technical English: log it, do not show it. */
    data class Failed(val message: String?) : FriendPassportResult
}

interface FriendPassportRepository {
    /** [year] null = the caller's overview rules (all time for Pro, last 12 months free); a year = that year. */
    suspend fun friendPassport(friendId: String, year: Int?, fallbackYear: Int): FriendPassportResult
}

class SupabaseFriendPassportRepository @Inject constructor(
    private val caller: Wave2Caller,
) : FriendPassportRepository {

    override suspend fun friendPassport(friendId: String, year: Int?, fallbackYear: Int): FriendPassportResult {
        val result = caller.call { api, _ ->
            parseFriendPassport(
                api.rpc(
                    RPC_FRIEND_PASSPORT,
                    buildJsonObject {
                        put("p_friend", friendId)
                        if (year != null) put("p_year", year)
                    },
                ),
                year ?: fallbackYear,
            )
        }
        return when (result) {
            is RemoteResult.Ok -> FriendPassportResult.Loaded(result.value)
            RemoteResult.Offline -> FriendPassportResult.Offline
            RemoteResult.NotConfigured -> FriendPassportResult.NotConfigured
            is RemoteResult.Failed -> FriendPassportResult.Failed(result.message)
            is RemoteResult.Refused ->
                if ("not_shared" in result.message.orEmpty() || result.code == "42501") {
                    FriendPassportResult.NotShared
                } else {
                    FriendPassportResult.Failed(result.message ?: result.code)
                }
        }
    }

    internal companion object {
        const val RPC_FRIEND_PASSPORT = "friend_passport"
    }
}
