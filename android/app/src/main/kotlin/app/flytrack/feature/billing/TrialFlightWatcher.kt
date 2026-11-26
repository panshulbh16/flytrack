package app.flytrack.feature.billing

import app.flytrack.core.model.UserProfile
import app.flytrack.data.repo.FlightRepository
import app.flytrack.data.repo.ProfileRepository
import app.flytrack.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The server sets `profiles.trial_flight_id` in a trigger when a free user tracks their first
 * flight (contract section 1). Nothing else tells the app, so without this the trial badge and
 * the Pro data on that flight would only appear after the next app start. Whenever a new flight
 * shows up in the tracked list while the profile has no trial flight yet, the profile is
 * re-read once.
 */
@Singleton
class TrialFlightWatcher @Inject constructor(
    private val flights: FlightRepository,
    private val profiles: ProfileRepository,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    /** Idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { watch() }
    }

    /** Runs until cancelled. Internal for tests. */
    internal suspend fun watch() {
        var known: Set<String>? = null
        flights.observeTrackedFlights()
            .map { list -> list.mapTo(HashSet()) { it.tracking.flightId } }
            .distinctUntilChanged()
            .collect { ids ->
                val previous = known
                known = ids
                // The first list is what Room already had; app start refreshes the profile anyway.
                if (previous == null || (ids - previous).isEmpty()) return@collect
                if (needsTrialRefresh(profiles.observeProfile().first(), clock.now())) profiles.refresh()
            }
    }

    companion object {
        /** A profile that may just have been given its trial flight (or is not loaded yet). */
        fun needsTrialRefresh(profile: UserProfile?, now: Instant): Boolean =
            profile == null || (profile.trialFlightId == null && !profile.isPro(now))
    }
}
