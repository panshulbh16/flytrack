package app.flytrack.feature.live

import android.util.Log
import app.flytrack.core.model.TrackedFlightDetails
import app.flytrack.data.repo.FlightRepository
import app.flytrack.di.ApplicationScope
import app.flytrack.di.DefaultDispatcher
import app.flytrack.feature.billing.FlightProAccess
import app.flytrack.feature.billing.ProRepository
import app.flytrack.feature.billing.ProState
import app.flytrack.feature.widget.NextFlightWidgetUpdater
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * What the rest of the app calls to keep the live notification and the widget current. The push
 * sync worker calls [refreshNow] after it wrote a sync payload into Room.
 */
interface LiveUpdates {
    /** Recomputes from Room right now; returns once the notification and the widget are updated. */
    suspend fun refreshNow()
}

/**
 * Drives the live notification (Pro or trial flight) and the Next flight widget (free) from Room.
 *
 * Triggers: Room changes and Pro changes while the process lives ([start], called at app start),
 * FCM sync messages ([refreshNow] from the sync worker), and the WorkManager tick ([tick]).
 * Every path ends in the same pure [LivePlanner].
 */
@Singleton
class LiveCoordinator @Inject constructor(
    private val flights: FlightRepository,
    private val pro: ProRepository,
    private val dismissals: LiveDismissals,
    strings: LiveStrings,
    private val notifier: LiveFlightNotifier,
    private val widget: NextFlightWidgetUpdater,
    private val scheduler: LiveScheduler,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) : LiveUpdates {

    private val planner = LivePlanner(strings)
    private val started = AtomicBoolean(false)
    private val mutex = Mutex()
    private var widgetKey: Any? = null

    private data class Inputs(val tracked: List<TrackedFlightDetails>, val pro: ProState, val dismissed: Set<String>)

    /** Starts following Room. Idempotent; returns immediately. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(default) {
            combine(
                flights.observeTrackedFlights(),
                pro.observe(),
                dismissals.dismissed,
                minuteTicker(),
            ) { tracked, proState, dismissed, _ -> Inputs(tracked, proState, dismissed) }
                .collectLatest { apply(it, forceWidget = false) }
        }
    }

    override suspend fun refreshNow() {
        withContext(default) { guarded("refresh") { apply(currentInputs(), forceWidget = false) } }
    }

    /**
     * The WorkManager tick: fetch the live flight's newest row (best effort, offline is fine: the
     * estimate covers it), then redraw both surfaces. The widget is always redrawn here so its
     * countdown moves on.
     */
    suspend fun tick() = withContext(default) {
        guarded("tick") {
            val before = currentInputs()
            val live = LiveFlightSelector.selectLive(before.tracked, clock.now(), before.pro.eligibility(), before.dismissed)
            if (live != null) flights.applyServerSync(live.id)
        }
        guarded("tick") { apply(currentInputs(), forceWidget = true) }
    }

    /** Callers (the sync worker, the tick) must never fail because of the live surfaces. */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LIVE_LOG_TAG, "live $what failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun currentInputs(): Inputs =
        Inputs(flights.observeTrackedFlights().first(), pro.observe().first(), dismissals.dismissed.value)

    private suspend fun apply(inputs: Inputs, forceWidget: Boolean) = mutex.withLock {
        try {
            val plan = planner.plan(inputs.tracked, clock.now(), inputs.pro.eligibility(), inputs.dismissed)
            notifier.render(plan.notification)
            val key = plan.widgetRedrawKey
            if (forceWidget || key != widgetKey) {
                widget.updateAll()
                widgetKey = key
            }
            // Last: when the plan no longer needs the periodic tick, this cancels the running tick
            // worker itself (and its coroutine), so nothing may come after it.
            scheduler.apply(plan)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never let a notification or widget failure take the app scope down.
            Log.w(LIVE_LOG_TAG, "live update failed: ${e.javaClass.simpleName}")
        }
    }

    private fun ProState.eligibility(): (String) -> Boolean = { flightId -> accessFor(flightId) != FlightProAccess.LOCKED }

    private fun minuteTicker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(1.minutes)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LiveModule {
    @Binds
    @Singleton
    abstract fun bindLiveStrings(impl: AndroidLiveStrings): LiveStrings

    @Binds
    @Singleton
    abstract fun bindLiveUpdates(impl: LiveCoordinator): LiveUpdates
}
