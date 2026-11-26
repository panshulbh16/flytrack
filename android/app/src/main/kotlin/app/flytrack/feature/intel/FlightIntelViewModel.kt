package app.flytrack.feature.intel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightStatus
import app.flytrack.data.repo.FlightRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Everything the intelligence blocks of the detail screen draw. Each block hides itself when its part is empty. */
data class FlightIntelUiState(
    val timeline: TimelineUiModel? = null,
    val prediction: PredictionBlockUiModel = PredictionBlockUiModel.Hidden,
    /** null until the aircraft columns were asked for. */
    val aircraft: AircraftInfo? = null,
    /** Connections that start or end with this flight (Pro). */
    val connections: List<ConnectionUiModel> = emptyList(),
    /** Departure airport zone (aircraft swap times). */
    val depTz: String? = null,
)

/** The answers collected so far; [intelState] turns them into [FlightIntelUiState]. */
data class FlightIntelAnswers(
    val timeline: List<TimelineStep>? = null,
    val prediction: PredictionAnswers = PredictionAnswers(),
    val aircraft: AircraftInfo? = null,
    val connections: List<Connection>? = null,
)

/** Pure: the flight (null while unknown), the answers and the time to the blocks. */
fun intelState(flight: Flight?, answers: FlightIntelAnswers, zones: Map<String, String>, now: Instant): FlightIntelUiState {
    if (flight == null) return FlightIntelUiState()
    return FlightIntelUiState(
        timeline = answers.timeline?.toTimelineUi(flight.depAirport?.tz, flight.arrAirport?.tz, flight.flightDate),
        prediction = predictionBlock(flight, answers.prediction, now),
        aircraft = answers.aircraft,
        connections = answers.connections.orEmpty()
            .map { it.toUi { iata -> zones[iata] ?: if (iata == flight.arrIata) flight.arrAirport?.tz else null } }
            .forFlight(flight.id),
        depTz = flight.depAirport?.tz,
    )
}

/**
 * The fields that change what the intelligence RPCs answer. A live position update alone does not ask again.
 */
private data class IntelKey(
    val status: FlightStatus,
    val schedDep: Instant?,
    val estDep: Instant?,
    val actDep: Instant?,
    val depGate: String?,
    val estArr: Instant?,
    val actArr: Instant?,
    val arrGate: String?,
    val belt: String?,
    val reg: String?,
)

private fun Flight.intelKey() = IntelKey(status, schedDep, estDep, actDep, depGate, estArr, actArr, arrGate, baggageBelt, aircraftReg)

@HiltViewModel(assistedFactory = FlightIntelViewModel.Factory::class)
class FlightIntelViewModel @AssistedInject constructor(
    @Assisted private val flightId: String,
    private val repository: IntelRepository,
    private val flights: FlightRepository,
    private val clock: Clock,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(flightId: String): FlightIntelViewModel
    }

    private val answers = MutableStateFlow(FlightIntelAnswers())

    private val flight: Flow<Flight?> =
        merge(flights.observeTrackedFlight(flightId).map { it?.flight }, flights.observeFlight(flightId)).distinctUntilChanged()

    private val zones: Flow<Map<String, String>> = flights.observeTrackedFlights().map { list -> airportZones(list.map { it.flight }) }

    val uiState: StateFlow<FlightIntelUiState> = combine(flight, answers, zones, ticks()) { f, a, z, now -> intelState(f, a, z, now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FlightIntelUiState())

    init {
        viewModelScope.launch {
            flight.filterNotNull().map { it to it.intelKey() }
                .distinctUntilChanged { old, new -> old.second == new.second }
                .collectLatest { (f, _) -> load(f) }
        }
    }

    /** Pull-to-refresh on the detail screen: asks everything again. */
    fun refresh() {
        viewModelScope.launch { load(flight.filterNotNull().first()) }
    }

    private suspend fun load(f: Flight) {
        val now = clock.now()
        val language = predictionLanguage(Locale.getDefault().language)
        coroutineScope {
            val timeline = async { repository.timeline(flightId) }
            val delay = async { if (f.isPredictionWindowOpen(now)) repository.delayPrediction(flightId, language) else null }
            val gate = async { if (f.wantsGatePrediction()) repository.gatePrediction(flightId) else null }
            val taxiOut = async { if (f.wantsTaxiOut()) repository.taxiStats(f.depIata) else null }
            val taxiIn = async { if (f.wantsTaxiIn()) repository.taxiStats(f.arrIata) else null }
            val aircraft = async { repository.aircraft(flightId) }
            val connections = async { repository.connections() }

            val prediction = PredictionAnswers(delay.await(), gate.await(), taxiOut.await(), taxiIn.await())
            answers.update { old ->
                FlightIntelAnswers(
                    // A failed refresh keeps what the screen already shows.
                    timeline = timeline.await().valueOrNull() ?: old.timeline,
                    prediction = prediction.keepingOld(old.prediction),
                    aircraft = aircraft.await().valueOrNull() ?: old.aircraft,
                    connections = when (val c = connections.await()) {
                        is IntelResult.Ok -> c.value
                        IntelResult.Locked -> emptyList()
                        else -> old.connections
                    },
                )
            }
        }
    }

    private fun ticks() = flow {
        while (true) {
            emit(clock.now())
            delay(30.seconds)
        }
    }
}

/** Offline or failed answers keep the previous one; Ok, Locked and "not asked" replace it. */
private fun PredictionAnswers.keepingOld(old: PredictionAnswers) = PredictionAnswers(
    delay = delay.orOld(old.delay),
    gate = gate.orOld(old.gate),
    taxiOut = taxiOut.orOld(old.taxiOut),
    taxiIn = taxiIn.orOld(old.taxiIn),
)

private fun <T> IntelResult<T>?.orOld(old: IntelResult<T>?): IntelResult<T>? = when (this) {
    null, is IntelResult.Ok, IntelResult.Locked -> this
    else -> old ?: this
}
