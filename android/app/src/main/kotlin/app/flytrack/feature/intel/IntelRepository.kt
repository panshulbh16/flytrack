package app.flytrack.feature.intel

import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendException
import app.flytrack.data.repo.AuthRepository
import app.flytrack.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Outcome of one intelligence call. */
sealed interface IntelResult<out T> {
    data class Ok<T>(val value: T) : IntelResult<T>

    /** `pro_required`: the caller is not Pro for this flight or airport. Show the teaser (or nothing). */
    data object Locked : IntelResult<Nothing>

    /** No connection: the request may never have reached the server. */
    data object Offline : IntelResult<Nothing>

    /** The build has no backend keys (the app shell already shows a banner). */
    data object NotConfigured : IntelResult<Nothing>

    /** Any other error. [message] is technical English: log it, do not show it. */
    data class Failed(val message: String?) : IntelResult<Nothing>
}

/** The value, or null for every error. */
fun <T> IntelResult<T>.valueOrNull(): T? = (this as? IntelResult.Ok<T>)?.value

interface IntelRepository {
    /** `flight_timeline`: free keys and times for everyone, Pro detail. */
    suspend fun timeline(flightId: String): IntelResult<List<TimelineStep>>

    /** `delay_prediction` in [language] (en, hi or hinglish); Ok(null) outside the 72 h window. */
    suspend fun delayPrediction(flightId: String, language: String): IntelResult<DelayPrediction?>

    /** `gate_prediction`; Ok(null) below 4 samples. */
    suspend fun gatePrediction(flightId: String): IntelResult<GatePrediction?>

    /** `taxi_stats` for one airport, kept in memory for a while (the 30-day figures move slowly). */
    suspend fun taxiStats(iata: String): IntelResult<TaxiStats?>

    /** ICAO type and (Pro) age from `flight_view`, plus the (Pro) swap history. */
    suspend fun aircraft(flightId: String): IntelResult<AircraftInfo>

    /** `my_connections`. */
    suspend fun connections(): IntelResult<List<Connection>>

    /** `airport_trends`, kept in memory for a while. Ok(null) for an airport the backend does not know. */
    suspend fun airportTrends(iata: String, days: Int = DEFAULT_TREND_DAYS): IntelResult<AirportTrends?>
}

@Singleton
class SupabaseIntelRepository @Inject constructor(
    private val api: IntelApi,
    private val auth: AuthRepository,
    private val json: Json,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : IntelRepository {

    private data class Cached<T>(val value: T, val at: Instant)

    private val taxiCache = HashMap<String, Cached<TaxiStats?>>()
    private val trendsCache = HashMap<String, Cached<AirportTrends?>>()

    override suspend fun timeline(flightId: String): IntelResult<List<TimelineStep>> = call {
        parseTimeline(api.rpc(RPC_TIMELINE, buildJsonObject { put("p_flight_id", flightId) }), json)
    }

    override suspend fun delayPrediction(flightId: String, language: String): IntelResult<DelayPrediction?> = call {
        val args = buildJsonObject {
            put("p_flight_id", flightId)
            put("p_lang", language)
        }
        parseDelayPrediction(api.rpc(RPC_DELAY_PREDICTION, args), json)
    }

    override suspend fun gatePrediction(flightId: String): IntelResult<GatePrediction?> = call {
        parseGatePrediction(api.rpc(RPC_GATE_PREDICTION, buildJsonObject { put("p_flight_id", flightId) }), json)
    }

    override suspend fun taxiStats(iata: String): IntelResult<TaxiStats?> {
        val code = iata.trim().uppercase()
        if (!IATA.matches(code)) return IntelResult.Ok(null)
        return cached(taxiCache, code, TAXI_CACHE_FOR) {
            parseTaxiStats(api.rpc(RPC_TAXI_STATS, buildJsonObject { put("p_iata", code) }), code, json)
        }
    }

    override suspend fun aircraft(flightId: String): IntelResult<AircraftInfo> = call {
        parseAircraftInfo(api.aircraftColumns(flightId), api.aircraftSwapEvents(flightId), json)
    }

    override suspend fun connections(): IntelResult<List<Connection>> = call {
        parseConnections(api.rpc(RPC_MY_CONNECTIONS), json)
    }

    override suspend fun airportTrends(iata: String, days: Int): IntelResult<AirportTrends?> {
        val code = iata.trim().uppercase()
        if (!IATA.matches(code)) return IntelResult.Ok(null)
        val window = days.coerceIn(1, MAX_TREND_DAYS)
        return cached(trendsCache, "$code:$window", TRENDS_CACHE_FOR) {
            val args = buildJsonObject {
                put("p_iata", code)
                put("p_days", window)
            }
            parseAirportTrends(api.rpc(RPC_AIRPORT_TRENDS, args), code, json)
        }
    }

    private suspend fun <T> cached(
        cache: HashMap<String, Cached<T>>,
        key: String,
        ttl: Duration,
        block: suspend () -> T,
    ): IntelResult<T> {
        val now = clock.now()
        synchronized(cache) { cache[key] }?.takeIf { now - it.at < ttl }?.let { return IntelResult.Ok(it.value) }
        val result = call(block)
        if (result is IntelResult.Ok) synchronized(cache) { cache[key] = Cached(result.value, now) }
        return result
    }

    /** Signs in when needed and turns every failure into an [IntelResult]. Never throws (cancellation aside). */
    private suspend fun <T> call(block: suspend () -> T): IntelResult<T> = withContext(io) {
        when (val signedIn = auth.ensureSignedIn()) {
            AuthResult.Success, AuthResult.GoogleAccountInUse -> Unit
            AuthResult.NotConfigured -> return@withContext IntelResult.NotConfigured
            AuthResult.Offline -> return@withContext IntelResult.Offline
            is AuthResult.Failed -> return@withContext IntelResult.Failed(signedIn.message)
        }
        try {
            IntelResult.Ok(block())
        } catch (e: BackendException) {
            e.toResult()
        } catch (e: SerializationException) {
            IntelResult.Failed(e.message)
        } catch (e: IllegalArgumentException) {
            // a JSON value of the wrong kind
            IntelResult.Failed(e.message)
        }
    }

    private companion object {
        const val RPC_TIMELINE = "flight_timeline"
        const val RPC_DELAY_PREDICTION = "delay_prediction"
        const val RPC_GATE_PREDICTION = "gate_prediction"
        const val RPC_TAXI_STATS = "taxi_stats"
        const val RPC_MY_CONNECTIONS = "my_connections"
        const val RPC_AIRPORT_TRENDS = "airport_trends"
        const val MAX_TREND_DAYS = 90
        val IATA = Regex("^[A-Z0-9]{3}$")
        val TAXI_CACHE_FOR = 30.minutes
        val TRENDS_CACHE_FOR = 15.minutes
    }
}

/** `pro_required` (42501) is the one refusal the screens show differently: as the Pro teaser. */
internal fun BackendException.toResult(): IntelResult<Nothing> = when (this) {
    is BackendException.NotConfigured -> IntelResult.NotConfigured
    is BackendException.Network -> IntelResult.Offline
    is BackendException.Malformed -> IntelResult.Failed(message)
    is BackendException.Http -> if (isProRequired()) IntelResult.Locked else IntelResult.Failed(message)
}

internal fun BackendException.Http.isProRequired(): Boolean =
    message?.contains(PRO_REQUIRED) == true || hint?.contains(PRO_REQUIRED) == true

private const val PRO_REQUIRED = "pro_required"
