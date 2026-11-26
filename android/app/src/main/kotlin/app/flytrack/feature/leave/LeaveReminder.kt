package app.flytrack.feature.leave

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.flytrack.R
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.data.repo.AuthRepository
import app.flytrack.data.repo.FlightRepository
import app.flytrack.data.repo.SessionState
import app.flytrack.feature.airporthealth.AirportHealthRepository
import app.flytrack.feature.airporthealth.AirportHealthResult
import app.flytrack.feature.billing.FlightProAccess
import app.flytrack.feature.billing.ProRepository
import app.flytrack.feature.social.ProfileFlagsRepository
import app.flytrack.push.notificationIdFor
import app.flytrack.ui.nav.openFlightIntent
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toJavaDuration

/** What the reminder worker does when it wakes up. */
sealed interface ReminderDecision {
    data class Notify(val plan: LeavePlan) : ReminderDecision

    /** The departure moved later (a delay): wake up again at [at]. */
    data class Reschedule(val at: Instant) : ReminderDecision
    data object Skip : ReminderDecision
}

/**
 * Re-checks the plan at fire time, so a delay announced after scheduling moves the reminder instead of
 * sending it too early, and a departed, cancelled or untracked flight sends nothing.
 */
fun reminderDecision(plan: LeavePlan?, eligible: Boolean, now: Instant): ReminderDecision = when {
    !eligible || plan == null -> ReminderDecision.Skip
    plan.reminderAt - now > EARLY_SLACK -> ReminderDecision.Reschedule(plan.reminderAt)
    now - plan.leaveBy > LATE_LIMIT -> ReminderDecision.Skip
    else -> ReminderDecision.Notify(plan)
}

/**
 * Waits until the stored session has been read (or [timeout] passes). The worker usually runs in a freshly
 * started process where the session is still [SessionState.Loading]; Pro state read then would be "free"
 * (no profile yet) and a Pro user's reminder would be dropped. True when the session is known.
 */
suspend fun awaitSessionKnown(session: Flow<SessionState>, timeout: Duration = SESSION_TIMEOUT): Boolean =
    withTimeoutOrNull(timeout) { session.first { it !is SessionState.Loading } } != null

private val SESSION_TIMEOUT = 15.seconds

/** WorkManager may wake a little early; within this the reminder is sent. */
private val EARLY_SLACK = 2.minutes

/** A reminder this far after "leave by" (phone was off, Doze) is no longer useful. */
private val LATE_LIMIT = 30.minutes

/** Schedules the local "time to leave" notification at `leaveBy − 15 min` (contract section 5). */
interface LeaveReminderScheduler {
    fun schedule(flightId: String, at: Instant)
    fun cancel(flightId: String)
}

/** WorkManager: one unique job per flight, replaced when the time changes. Nothing reaches a server. */
@Singleton
class WorkManagerLeaveReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) : LeaveReminderScheduler {

    private val scheduled = HashMap<String, Instant>()

    @Synchronized
    override fun schedule(flightId: String, at: Instant) {
        if (scheduled[flightId] == at) return
        val delay = (at - clock.now()).coerceAtLeast(kotlin.time.Duration.ZERO)
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(flightId),
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LeaveReminderWorker>()
                .setInitialDelay(delay.toJavaDuration())
                .setInputData(workDataOf(KEY_FLIGHT_ID to flightId))
                .addTag(WORK_TAG)
                .build(),
        )
        scheduled[flightId] = at
    }

    @Synchronized
    override fun cancel(flightId: String) {
        if (scheduled.remove(flightId) == null) return
        WorkManager.getInstance(context).cancelUniqueWork(workName(flightId))
    }

    internal companion object {
        const val KEY_FLIGHT_ID = "flight_id"
        const val WORK_TAG = "leave-reminder"
        fun workName(flightId: String) = "leave-reminder:$flightId"
    }
}

@HiltWorker
class LeaveReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val flights: FlightRepository,
    private val travel: TravelTimeStore,
    private val health: AirportHealthRepository,
    private val pro: ProRepository,
    private val auth: AuthRepository,
    private val scheduler: LeaveReminderScheduler,
    private val clock: Clock,
    private val profileFlags: ProfileFlagsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val flightId = inputData.getString(WorkManagerLeaveReminderScheduler.KEY_FLIGHT_ID) ?: return Result.success()
        try {
            val tracked = flights.observeTrackedFlight(flightId).first()?.takeUnless { it.tracking.isArchived }
                ?: return Result.success()
            val flight = tracked.flight
            // Cold start: Pro comes from the signed-in user's cached profile, so the session must be read first.
            if (!awaitSessionKnown(auth.session)) return Result.retry()
            // P3 B12: crew mode mutes when-to-leave reminders (crew report times are not passenger times).
            if (profileFlags.crewMode.first()) return Result.success()
            val eligible = pro.observe().first().accessFor(flightId) != FlightProAccess.LOCKED
            val fog = (health.health(flight.depIata) as? AirportHealthResult.Loaded)?.health?.slowsDeparture() == true
            val minutes = travel.travelMinutes(flight.depIata).first()
            val now = clock.now()
            when (val decision = reminderDecision(leavePlan(flight, minutes, fog, now), eligible, now)) {
                is ReminderDecision.Notify -> LeaveNotifier.show(applicationContext, flightId, flight.designator, flight.depAirport?.displayCity ?: flight.depIata, decision.plan)
                is ReminderDecision.Reschedule -> {
                    // This job is the unique one: forget it so schedule() enqueues the replacement.
                    scheduler.cancel(flightId)
                    scheduler.schedule(flightId, decision.at)
                }
                ReminderDecision.Skip -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(LOG_TAG, "leave reminder failed: ${e.javaClass.simpleName}")
        }
        return Result.success()
    }

    private companion object {
        const val LOG_TAG = "FlyTrackLeave"
    }
}

/** Posts the reminder on its own channel; a tap opens the flight. */
internal object LeaveNotifier {
    const val CHANNEL_ID = "leave_reminders"

    fun show(context: Context, flightId: String, designator: String, depCity: String, plan: LeavePlan) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.leave_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.leave_channel_description)
            },
        )
        val tag = "$flightId:leave"
        val id = notificationIdFor(tag)
        val leaveAt = FlightTimeFormat.time(plan.leaveBy, plan.depTz)
        val departs = FlightTimeFormat.time(plan.departure, plan.depTz)
        val title = context.getString(R.string.leave_notification_title, leaveAt, designator)
        val body = context.getString(R.string.leave_notification_body, departs, plan.travelMinutes, depCity)
        val pending = PendingIntent.getActivity(
            context,
            id,
            openFlightIntent(context, flightId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(context.getColor(R.color.forest))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        try {
            manager.notify(tag, id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS was refused: nothing to show.
        }
    }
}
