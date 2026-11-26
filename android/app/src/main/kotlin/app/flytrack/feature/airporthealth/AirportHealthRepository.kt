package app.flytrack.feature.airporthealth

import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.TravelApi
import app.flytrack.data.repo.AuthRepository
import app.flytrack.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

sealed interface AirportHealthResult {
    data class Loaded(val health: AirportHealth) : AirportHealthResult

    /** The backend does not know the airport, or answered with a level this app cannot show. */
    data object Unavailable : AirportHealthResult
    data object Offline : AirportHealthResult
    data object NotConfigured : AirportHealthResult

    /** [message] is technical English: log it, do not show it. */
    data class Failed(val message: String?) : AirportHealthResult
}

interface AirportHealthRepository {
    /** `get_airport_health` for [iata]. Answers younger than a few minutes come from memory unless [force]. Never throws. */
    suspend fun health(iata: String, force: Boolean = false): AirportHealthResult
}

@Singleton
class SupabaseAirportHealthRepository @Inject constructor(
    private val api: TravelApi,
    private val auth: AuthRepository,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AirportHealthRepository {

    private data class Cached(val result: AirportHealthResult, val at: Instant)

    private val cache = HashMap<String, Cached>()

    override suspend fun health(iata: String, force: Boolean): AirportHealthResult = withContext(io) {
        val code = iata.trim().uppercase()
        if (!IATA.matches(code)) return@withContext AirportHealthResult.Unavailable
        val now = clock.now()
        if (!force) {
            synchronized(cache) { cache[code] }?.takeIf { now - it.at < CACHE_FOR }?.let { return@withContext it.result }
        }
        when (val signedIn = auth.ensureSignedIn()) {
            AuthResult.Success, AuthResult.GoogleAccountInUse -> Unit
            AuthResult.NotConfigured -> return@withContext AirportHealthResult.NotConfigured
            AuthResult.Offline -> return@withContext AirportHealthResult.Offline
            is AuthResult.Failed -> return@withContext AirportHealthResult.Failed(signedIn.message)
        }
        val result = try {
            val json = api.airportHealth(code)
            json?.let { parseAirportHealth(it, code) }?.let { AirportHealthResult.Loaded(it) } ?: AirportHealthResult.Unavailable
        } catch (e: BackendException) {
            e.toResult()
        } catch (e: SerializationException) {
            AirportHealthResult.Failed(e.message)
        }
        if (result is AirportHealthResult.Loaded || result == AirportHealthResult.Unavailable) {
            synchronized(cache) { cache[code] = Cached(result, now) }
        }
        result
    }

    private fun BackendException.toResult(): AirportHealthResult = when (this) {
        is BackendException.NotConfigured -> AirportHealthResult.NotConfigured
        is BackendException.Network -> AirportHealthResult.Offline
        is BackendException.Malformed -> AirportHealthResult.Failed(message)
        // invalid_iata (22023): nothing to show for this code.
        is BackendException.Http -> if (code == PG_INVALID_PARAMETER) AirportHealthResult.Unavailable else AirportHealthResult.Failed(message)
    }

    private companion object {
        val IATA = Regex("^[A-Z0-9]{3}$")
        val CACHE_FOR = 5.minutes
        const val PG_INVALID_PARAMETER = "22023"
    }
}
