package app.flytrack.feature.live

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toJavaDuration

/**
 * The WorkManager side of the live surfaces: a unique ~15-minute periodic tick while a flight is
 * live or close, and a one-off wake-up when the next live window opens. Calls are idempotent and
 * only touch WorkManager when the wanted state changes.
 */
@Singleton
class LiveScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) {
    private var tickWanted: Boolean? = null
    private var windowWake: Instant? = null

    @Synchronized
    fun apply(plan: LivePlan) {
        val work = WorkManager.getInstance(context)
        if (plan.needsTick != tickWanted) {
            if (plan.needsTick) {
                work.enqueueUniquePeriodicWork(
                    WORK_TICK,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<LiveUpdateWorker>(TICK_MINUTES, TimeUnit.MINUTES).build(),
                )
            } else {
                work.cancelUniqueWork(WORK_TICK)
            }
            tickWanted = plan.needsTick
        }
        val now = clock.now()
        val wake = plan.nextWindowStart?.takeIf { it > now && it - now <= MAX_WAKE_AHEAD }
        if (wake != null && wake != windowWake) {
            // One unique name per wake-up minute, kept: a running wake-up is never cancelled by the
            // plan it produces, and a wake-up left behind by a changed departure is only a spare tick.
            work.enqueueUniqueWork(
                "$WORK_WINDOW:${wake.epochSeconds / 60}",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LiveUpdateWorker>()
                    .setInitialDelay((wake - now + WAKE_SLACK).toJavaDuration())
                    .addTag(WORK_WINDOW)
                    .build(),
            )
            windowWake = wake
        }
    }

    private companion object {
        const val WORK_TICK = "live-tick"
        const val WORK_WINDOW = "live-window-start"
        const val TICK_MINUTES = 15L
        val MAX_WAKE_AHEAD = 7.days

        /** Wake just after the window opens, so the selection already includes the flight. */
        val WAKE_SLACK = 1.minutes
    }
}

/**
 * One tick: asks the backend for the live flight's newest row (best effort; offline is fine, the
 * estimate covers it), then redraws the notification and the widget.
 */
@HiltWorker
class LiveUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val live: LiveCoordinator,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            live.tick()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(LIVE_LOG_TAG, "live tick failed: ${e.javaClass.simpleName}")
        }
        return Result.success()
    }
}

internal const val LIVE_LOG_TAG = "FlyTrackLive"
