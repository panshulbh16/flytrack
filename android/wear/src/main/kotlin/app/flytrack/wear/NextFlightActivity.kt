package app.flytrack.wear

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import app.flytrack.wear.data.NextFlightPayload
import app.flytrack.wear.data.NextFlightRepository
import app.flytrack.wear.ui.ResourceWearStrings
import app.flytrack.wear.ui.WatchFlightFormatter
import app.flytrack.wear.ui.WatchNextFlight
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The watch app's one screen (opened from the Tile, the complication or the launcher): the same
 * next flight, a little larger. Plain views keep the watch app small; it follows the Data Layer
 * while visible.
 */
class NextFlightActivity : Activity(), DataClient.OnDataChangedListener {

    private val scope: CoroutineScope = MainScope()
    private var loading: Job? = null
    private val repository by lazy { NextFlightRepository(this) }
    private val formatter by lazy { WatchFlightFormatter(ResourceWearStrings(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_next_flight)
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        refresh()
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onDataChanged(events: DataEventBuffer) {
        if (events.any { it.dataItem.uri.path == NextFlightPayload.PATH }) refresh()
    }

    private fun refresh() {
        loading?.cancel()
        loading = scope.launch { render(formatter.format(repository.load(), Instant.now())) }
    }

    private fun render(state: WatchNextFlight) {
        val title = findViewById<TextView>(R.id.flight_title)
        val route = findViewById<TextView>(R.id.flight_route)
        val status = findViewById<TextView>(R.id.flight_status)
        val time = findViewById<TextView>(R.id.flight_time)
        val gate = findViewById<TextView>(R.id.flight_gate)
        when (state) {
            WatchNextFlight.Empty -> {
                title.setText(R.string.wear_empty_title)
                route.setText(R.string.wear_empty_body)
                listOf(status, time, gate).forEach { it.visibility = View.GONE }
            }
            is WatchNextFlight.Upcoming -> {
                val flight = state.flight
                title.text = flight.designator
                route.text = flight.route
                status.show(flight.statusText)
                status.setTextColor(flight.tone.argb)
                // The offline estimate reads in italics, like "Estimated (offline)" on the phone.
                status.typeface = Typeface.create(Typeface.DEFAULT, if (flight.estimated) Typeface.ITALIC else Typeface.NORMAL)
                time.show(flight.whenText)
                gate.show(flight.gateText)
            }
        }
    }

    private fun TextView.show(value: String?) {
        text = value
        visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
}
