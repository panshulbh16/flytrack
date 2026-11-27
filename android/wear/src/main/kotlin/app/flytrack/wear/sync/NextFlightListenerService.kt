package app.flytrack.wear.sync

import android.content.ComponentName
import android.content.Context
import app.flytrack.wear.complication.NextFlightComplicationService
import app.flytrack.wear.data.NextFlightPayload
import app.flytrack.wear.tile.NextFlightTileService
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService

/**
 * Woken by Google Play services when the phone changes `/next_flight` (manifest filter on that
 * path), even when the app is not running. Asks the Tile and the complication to redraw; they read
 * the new item themselves.
 */
class NextFlightListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val relevant = dataEvents.any { it.dataItem.uri.path == NextFlightPayload.PATH }
        if (relevant) NextFlightSurfaces.requestUpdate(this)
    }
}

/** Redraw requests for every surface that shows the next flight. */
object NextFlightSurfaces {
    fun requestUpdate(context: Context) {
        TileService.getUpdater(context).requestUpdate(NextFlightTileService::class.java)
        ComplicationDataSourceUpdateRequester
            .create(context, ComponentName(context, NextFlightComplicationService::class.java))
            .requestUpdateAll()
    }
}
