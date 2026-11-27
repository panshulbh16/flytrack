package app.flytrack.wear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.flytrack.wear.NextFlightActivity
import app.flytrack.wear.R
import app.flytrack.wear.data.NextFlightRepository
import app.flytrack.wear.ui.ComplicationTexts
import app.flytrack.wear.ui.ResourceWearStrings
import app.flytrack.wear.ui.WatchFlightFormatter
import app.flytrack.wear.ui.WatchNextFlight
import java.time.Instant

/**
 * Next flight complication (SHORT_TEXT: "18:25" over "DEL-BOM"; LONG_TEXT: "6E 2341 · DEL → BOM"
 * with "Delayed 25 min · 18:25 · Gate 42B"). Pushed by the listener service when the phone writes;
 * the manifest also asks for a refresh every 15 minutes so a stale flight drops off.
 */
class NextFlightComplicationService : SuspendingComplicationDataSourceService() {

    private val repository by lazy { NextFlightRepository(this) }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val strings = ResourceWearStrings(this)
        val state = WatchFlightFormatter(strings).format(repository.load(), Instant.now())
        val tap = openApp()
        return when (state) {
            WatchNextFlight.Empty -> empty(request.complicationType, tap)
            is WatchNextFlight.Upcoming -> data(request.complicationType, ComplicationTexts.from(state.flight, strings), tap)
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(
        type,
        ComplicationTexts(
            shortText = "18:25",
            shortTitle = "DEL-BOM",
            longText = "6E 2341 · DEL → BOM",
            longTitle = "${getString(R.string.wear_status_on_time)} · 18:25 · ${getString(R.string.wear_gate, "42B")}",
            contentDescription = getString(R.string.wear_complication_label),
        ),
        tap = null,
    )

    private fun data(type: ComplicationType, texts: ComplicationTexts, tap: PendingIntent?): ComplicationData? {
        val description = plain(texts.contentDescription)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(texts.shortText), description)
                .setTitle(plain(texts.shortTitle))
                .setTapAction(tap)
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(plain(texts.longText), description)
                .setTitle(plain(texts.longTitle))
                .setTapAction(tap)
                .build()
            else -> null
        }
    }

    private fun empty(type: ComplicationType, tap: PendingIntent): ComplicationData {
        val title = getString(R.string.wear_empty_title)
        val description = plain(title)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(getString(R.string.wear_empty_short)), description)
                .setTapAction(tap)
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(plain(title), description)
                .setTapAction(tap)
                .build()
            else -> NoDataComplicationData()
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, NextFlightActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun plain(text: String) = PlainComplicationText.Builder(text).build()
}
