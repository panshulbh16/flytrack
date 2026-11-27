package app.flytrack.feature.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.core.estimate.GeoPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What the screen was opened for. */
sealed interface RadarTarget {
    /** "Ground radar" from an airport card. */
    data class Airport(val iata: String) : RadarTarget

    /** "Planes near me": the user's coarse location, asked for on tap. */
    data object NearMe : RadarTarget

    /** Around a known point, such as the user's flight's live or estimated position. */
    data class Around(val point: GeoPoint) : RadarTarget
}

/** The main area of the screen. */
enum class RadarPhase {
    /** "Planes near me" before the user tapped "Use my location". */
    NEEDS_LOCATION,
    LOCATING,
    LOCATION_DENIED,
    LOCATION_OFF,
    LOCATION_UNAVAILABLE,
    LOADING,
    SHOWING,

    /** Feed unavailable or unlicensed: "Radar is coming soon". */
    COMING_SOON,
    PRO_REQUIRED,
    UNKNOWN_AIRPORT,

    /** Offline before anything was shown. */
    OFFLINE,
    NOT_CONFIGURED,
    FAILED,
}

/** A problem with the latest refresh while older data stays on screen. */
enum class RadarNotice { STALE_OFFLINE, STALE_FEED, SLOWED_DOWN }

data class RadarUiState(
    val target: RadarTarget? = null,
    val phase: RadarPhase = RadarPhase.LOADING,
    val rows: List<RadarAircraftUiModel> = emptyList(),
    val center: GeoPoint? = null,
    val radiusKm: Int = 0,
    val asOf: Instant? = null,
    val notice: RadarNotice? = null,
    val selectedKey: String? = null,
) {
    val selected: RadarAircraftUiModel? get() = rows.firstOrNull { it.key == selectedKey }
}

/**
 * Radar screen logic. It refreshes every 10 s while [setVisible] is true (the screen calls it from its
 * lifecycle), and never while the screen is in the background.
 */
@HiltViewModel
class RadarViewModel @Inject constructor(
    private val radar: RadarRepository,
    private val location: CoarseLocationProvider,
    private val clock: Clock,
) : ViewModel() {

    private val state = MutableStateFlow(RadarUiState())
    val uiState: StateFlow<RadarUiState> = state.asStateFlow()

    private var query: RadarQuery? = null
    private var visible = false
    private var loop: Job? = null
    private var locating: Job? = null

    /** Sets what the screen shows; a second call with the same target does nothing. */
    fun start(target: RadarTarget) {
        if (state.value.target == target) return
        loop?.cancel()
        query = when (target) {
            is RadarTarget.Airport -> RadarQuery.Ground(target.iata.uppercase())
            is RadarTarget.Around -> RadarQuery.around(target.point)
            RadarTarget.NearMe -> null
        }
        val phase = when {
            query != null -> RadarPhase.LOADING
            location.hasPermission() -> RadarPhase.LOCATING
            else -> RadarPhase.NEEDS_LOCATION
        }
        state.value = RadarUiState(target = target, phase = phase)
        // Permission already granted earlier: no need to ask the user to tap again.
        if (phase == RadarPhase.LOCATING) locate() else restartLoop()
    }

    /** The screen came to the foreground (true) or left it (false). */
    fun setVisible(isVisible: Boolean) {
        visible = isVisible
        if (isVisible) restartLoop() else loop?.cancel()
    }

    /** "Use my location" was tapped and the permission dialog answered. */
    fun onLocationPermission(granted: Boolean) {
        if (!granted) {
            state.update { it.copy(phase = RadarPhase.LOCATION_DENIED) }
            return
        }
        locate()
    }

    fun retry() {
        when (state.value.phase) {
            RadarPhase.LOCATION_OFF, RadarPhase.LOCATION_UNAVAILABLE -> locate()
            else -> {
                if (state.value.rows.isEmpty() && query != null) state.update { it.copy(phase = RadarPhase.LOADING) }
                restartLoop(force = true)
            }
        }
    }

    fun select(key: String?) {
        state.update { it.copy(selectedKey = if (it.selectedKey == key) null else key) }
    }

    private fun locate() {
        if (locating?.isActive == true) return
        state.update { it.copy(phase = RadarPhase.LOCATING) }
        locating = viewModelScope.launch {
            when (val result = location.current()) {
                is LocationResult.Found -> {
                    query = RadarQuery.around(result.point)
                    state.update { it.copy(phase = RadarPhase.LOADING) }
                    restartLoop(force = true)
                }
                LocationResult.NoPermission -> state.update { it.copy(phase = RadarPhase.NEEDS_LOCATION) }
                LocationResult.Disabled -> state.update { it.copy(phase = RadarPhase.LOCATION_OFF) }
                LocationResult.Unavailable -> state.update { it.copy(phase = RadarPhase.LOCATION_UNAVAILABLE) }
            }
        }
    }

    private fun restartLoop(force: Boolean = false) {
        if (!visible) return
        val q = query ?: return
        if (!force && loop?.isActive == true) return
        loop?.cancel()
        loop = viewModelScope.launch {
            while (isActive) {
                val result = radar.nearby(q)
                apply(result)
                val next = RadarRefreshPolicy.nextDelaySeconds(result) ?: break
                delay(next.seconds)
            }
        }
    }

    private fun apply(result: RadarResult) {
        state.update { s ->
            val hasData = s.phase == RadarPhase.SHOWING
            when (result) {
                is RadarResult.Ok -> {
                    val rows = RadarPresentation.rows(result.snapshot)
                    s.copy(
                        phase = RadarPhase.SHOWING,
                        rows = rows,
                        center = result.snapshot.center,
                        radiusKm = result.snapshot.radiusKm,
                        asOf = result.snapshot.asOf ?: clock.now(),
                        notice = null,
                        selectedKey = s.selectedKey?.takeIf { key -> rows.any { it.key == key } },
                    )
                }
                RadarResult.ComingSoon -> s.copy(phase = RadarPhase.COMING_SOON, rows = emptyList(), notice = null)
                RadarResult.ProRequired -> s.copy(phase = RadarPhase.PRO_REQUIRED, rows = emptyList(), notice = null)
                RadarResult.UnknownAirport -> s.copy(phase = RadarPhase.UNKNOWN_AIRPORT, rows = emptyList(), notice = null)
                RadarResult.NotConfigured -> s.copy(phase = RadarPhase.NOT_CONFIGURED, rows = emptyList(), notice = null)
                RadarResult.RateLimited ->
                    if (hasData) s.copy(notice = RadarNotice.SLOWED_DOWN) else s.copy(phase = RadarPhase.LOADING, notice = RadarNotice.SLOWED_DOWN)
                // A failed feed is shown like "coming soon" until a refresh works (contract section 7).
                RadarResult.FeedError ->
                    if (hasData) s.copy(notice = RadarNotice.STALE_FEED) else s.copy(phase = RadarPhase.COMING_SOON)
                RadarResult.Offline ->
                    if (hasData) s.copy(notice = RadarNotice.STALE_OFFLINE) else s.copy(phase = RadarPhase.OFFLINE)
                is RadarResult.Failed ->
                    if (hasData) s.copy(notice = RadarNotice.STALE_FEED) else s.copy(phase = RadarPhase.FAILED)
            }
        }
    }
}
