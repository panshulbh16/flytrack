package app.flytrack.push

import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendApi
import app.flytrack.data.repo.AuthRepository
import app.flytrack.di.ApplicationScope
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Delivery receipts (P2 contract, section 3): the app tells `ack-push` when an alert reached the phone,
 * or when it was opened, whichever happens first. Fire-and-forget: it never blocks the notification and
 * every failure is ignored.
 */
interface PushAckSender {
    /** [eventId] is the FCM `event_id` (a digit string); anything else is ignored. Returns immediately. */
    fun ack(eventId: String?)
}

/** Body of `POST /functions/v1/ack-push`. */
data class PushAckRequest(val eventId: Long, val receivedAt: Instant) {
    fun toJson(): String = buildJsonObject {
        put("event_id", eventId)
        put("received_at", receivedAt.toString())
    }.toString()
}

/** `flight_events.id` is a positive bigint; ack-push accepts at most 15 digits. Null for anything else. */
fun parsePushEventId(value: String?): Long? {
    val text = value?.trim().orEmpty()
    if (!EVENT_ID.matches(text)) return null
    return text.toLong().takeIf { it > 0 }
}

private val EVENT_ID = Regex("^\\d{1,15}$")

/**
 * Remembers which events were already acknowledged in this process, so "arrived" and "opened" send one
 * request between them. Bounded, oldest forgotten first.
 */
class AckedEvents(private val capacity: Int = 256) {
    private val seen = LinkedHashSet<Long>()

    /** True the first time [eventId] is offered. */
    @Synchronized
    fun firstTime(eventId: Long): Boolean {
        if (!seen.add(eventId)) return false
        if (seen.size > capacity) seen.remove(seen.first())
        return true
    }
}

@Singleton
class BackendPushAckSender @Inject constructor(
    private val backend: BackendApi,
    private val auth: AuthRepository,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) : PushAckSender {

    private val acked = AckedEvents()

    override fun ack(eventId: String?) {
        val id = parsePushEventId(eventId) ?: return
        if (!acked.firstTime(id)) return
        val request = PushAckRequest(id, clock.now())
        scope.launch { send(request) }
    }

    /** Never throws (cancellation aside): a lost receipt only makes the latency report a little thinner. */
    internal suspend fun send(request: PushAckRequest) {
        try {
            if (auth.ensureSignedIn() !is AuthResult.Success) return
            backend.invokeFunction(FUNCTION, request.toJson())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Ignored on purpose (contract: "Failures are ignored").
        }
    }

    private companion object {
        const val FUNCTION = "ack-push"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PushAckModule {
    @Binds
    @Singleton
    abstract fun bindPushAckSender(impl: BackendPushAckSender): PushAckSender
}
