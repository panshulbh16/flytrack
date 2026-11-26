package app.flytrack.feature.checkin

import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.CheckinRuleDto
import app.flytrack.data.remote.ShareCheckinApi
import app.flytrack.data.repo.AuthRepository
import app.flytrack.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

interface CheckinRulesRepository {
    /** The web check-in rule of [carrierIata] ("6E"), or null when the airline has none. Never throws. */
    suspend fun ruleFor(carrierIata: String): CheckinRule?
}

/**
 * `checkin_rules` is a handful of rows that change rarely: it is fetched once and kept in memory
 * for [CACHE_FOR]. Until the first fetch succeeds (offline start, build without backend keys) the
 * seed of the P1 contract stands in, so the card still works on the way to the airport.
 */
@Singleton
class SupabaseCheckinRulesRepository @Inject constructor(
    private val api: ShareCheckinApi,
    private val auth: AuthRepository,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CheckinRulesRepository {

    private val mutex = Mutex()
    private var cached: Map<String, CheckinRule>? = null
    private var fetchedAt: Instant? = null

    override suspend fun ruleFor(carrierIata: String): CheckinRule? {
        val key = carrierIata.trim().uppercase()
        return rules()[key]
    }

    private suspend fun rules(): Map<String, CheckinRule> = mutex.withLock {
        val now = clock.now()
        val current = cached
        val at = fetchedAt
        if (current != null && at != null && now - at < CACHE_FOR) return current
        val fetched = fetch()
        if (fetched != null) {
            cached = fetched
            fetchedAt = now
            fetched
        } else {
            current ?: BUILT_IN_RULES // keep an older list over the seed; retry on the next call
        }
    }

    private suspend fun fetch(): Map<String, CheckinRule>? = withContext(io) {
        val signedIn = auth.ensureSignedIn()
        if (signedIn != AuthResult.Success && signedIn != AuthResult.GoogleAccountInUse) return@withContext null
        try {
            api.checkinRules().map { it.toRule() }.associateBy { it.carrierIata }
        } catch (_: BackendException) {
            null
        }
    }

    companion object {
        val CACHE_FOR = 12.hours
    }
}

internal fun CheckinRuleDto.toRule() = CheckinRule(
    carrierIata = carrierIata.trim().uppercase(),
    opensHoursBefore = opensHoursBefore,
    closesMinutesBefore = closesMinutesBefore,
    closesMinutesBeforeIntl = closesMinutesBeforeIntl,
    checkinUrl = safeCheckinUrl(checkinUrl),
    verified = verified,
)

/** The seed of `checkin_rules` (P1 contract, section 3), used only while the table cannot be read. */
internal val BUILT_IN_RULES: Map<String, CheckinRule> = listOf(
    CheckinRule("6E", 48, 60, 75, "https://www.goindigo.in/web-check-in.html", verified = true),
    CheckinRule("AI", 48, 60, 75, "https://www.airindia.com/in/en/manage/web-checkin.html", verified = false),
    CheckinRule("IX", 48, 60, 75, "https://www.airindiaexpress.com/checkin-home", verified = false),
    CheckinRule("QP", 48, 60, 75, "https://www.akasaair.com/check-in", verified = false),
    CheckinRule("SG", 48, 60, 75, "https://www.spicejet.com/check-in", verified = false),
).associateBy { it.carrierIata }
