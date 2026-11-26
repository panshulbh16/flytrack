package app.flytrack.wear.tile

import android.content.Context
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.ColorScheme
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.material3.titleCard
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.LayoutColor
import androidx.wear.protolayout.types.LayoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import app.flytrack.wear.NextFlightActivity
import app.flytrack.wear.R
import app.flytrack.wear.data.NextFlightRepository
import app.flytrack.wear.ui.ResourceWearStrings
import app.flytrack.wear.ui.WatchFlight
import app.flytrack.wear.ui.WatchFlightFormatter
import app.flytrack.wear.ui.WatchNextFlight
import app.flytrack.wear.ui.WearColors
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import java.time.Instant

/**
 * The Next flight Tile: flight number, route, status (in its status colour), departure time and
 * gate. Redrawn when the phone writes `/next_flight` ([app.flytrack.wear.sync.NextFlightListenerService])
 * and every [FRESHNESS_MS] so a stale flight drops off.
 */
class NextFlightTileService : TileService() {

    private val repository by lazy { NextFlightRepository(this) }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture(Dispatchers.Main.immediate) {
            val strings = ResourceWearStrings(this@NextFlightTileService)
            val state = WatchFlightFormatter(strings).format(repository.load(), Instant.now())
            val layout = NextFlightTileLayout.build(this@NextFlightTileService, requestParams.deviceConfiguration, state)
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(FRESHNESS_MS)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
                .build()
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture(Dispatchers.Main.immediate) {
            // Text only: no images to send.
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
        }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val FRESHNESS_MS = 15L * 60 * 1000
    }
}

/** The Tile's layout (Material 3 for Wear OS: title slot, main card, edge button). */
internal object NextFlightTileLayout {

    private fun color(argb: Int) = LayoutColor(argb)

    /** The brand colours; the watch's dynamic theme is not used, so the Tile matches the phone app. */
    private val scheme = ColorScheme(
        primary = color(WearColors.PRIMARY),
        onPrimary = color(WearColors.ON_PRIMARY),
        primaryContainer = color(WearColors.PRIMARY_CONTAINER),
        onPrimaryContainer = color(WearColors.ON_PRIMARY_CONTAINER),
        tertiary = color(WearColors.TERTIARY),
        surfaceContainer = color(WearColors.SURFACE_CONTAINER),
        onSurface = color(WearColors.ON_SURFACE),
        onSurfaceVariant = color(WearColors.ON_SURFACE_VARIANT),
    )

    fun build(context: Context, device: DeviceParameters, state: WatchNextFlight): LayoutElementBuilders.LayoutElement =
        materialScope(context, device, allowDynamicTheme = false, defaultColorScheme = scheme) {
            when (state) {
                WatchNextFlight.Empty -> empty(context)
                is WatchNextFlight.Upcoming -> upcoming(context, state.flight)
            }
        }

    private fun openApp(context: Context, id: String): Clickable = clickable(
        ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(context.packageName)
                    .setClassName(NextFlightActivity::class.java.name)
                    .build(),
            )
            .build(),
        id,
    )

    private fun MaterialScope.upcoming(context: Context, flight: WatchFlight): LayoutElementBuilders.LayoutElement {
        val open = openApp(context, "open_flight")
        val details = listOfNotNull(flight.whenText, flight.gateText)
        return primaryLayout(
            titleSlot = { text(LayoutString(flight.designator)) },
            mainSlot = {
                titleCard(
                    onClick = open,
                    title = { text(LayoutString(flight.route), maxLines = 1) },
                    content = {
                        val column = LayoutElementBuilders.Column.Builder()
                            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                            .addContent(
                                text(
                                    LayoutString(flight.statusText),
                                    typography = Typography.LABEL_MEDIUM,
                                    color = color(flight.tone.argb),
                                    italic = flight.estimated,
                                    maxLines = 1,
                                ),
                            )
                        details.forEach { line ->
                            column.addContent(
                                text(
                                    LayoutString(line),
                                    typography = Typography.BODY_SMALL,
                                    color = color(WearColors.ON_SURFACE_VARIANT),
                                    maxLines = 1,
                                ),
                            )
                        }
                        column.build()
                    },
                )
            },
            bottomSlot = {
                textEdgeButton(onClick = open) { text(LayoutString(context.getString(R.string.wear_open))) }
            },
        )
    }

    private fun MaterialScope.empty(context: Context): LayoutElementBuilders.LayoutElement = primaryLayout(
        titleSlot = { text(LayoutString(context.getString(R.string.app_name))) },
        mainSlot = {
            LayoutElementBuilders.Column.Builder()
                .addContent(text(LayoutString(context.getString(R.string.wear_empty_title)), typography = Typography.TITLE_MEDIUM, maxLines = 2))
                .addContent(
                    text(
                        LayoutString(context.getString(R.string.wear_empty_body)),
                        typography = Typography.BODY_SMALL,
                        color = color(WearColors.ON_SURFACE_VARIANT),
                        maxLines = 3,
                    ),
                )
                .build()
        },
        onClick = openApp(context, "open_empty"),
    )
}
