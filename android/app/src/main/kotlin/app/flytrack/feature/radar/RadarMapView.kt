package app.flytrack.feature.radar

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.flytrack.R
import app.flytrack.core.estimate.GeoPoint
import app.flytrack.feature.map.MapPalette
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The radar map: the flight map's offline style plus rotated planes with callsign labels
 * ([RadarMapStyle]). The camera fits the search radius when the centre first arrives (or moves);
 * refreshes only move the planes, so the user's own zoom and pan are kept.
 */
@Composable
internal fun RadarMapView(
    rows: List<RadarAircraftUiModel>,
    center: GeoPoint?,
    radiusKm: Int,
    dark: Boolean,
    contentDescription: String,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val padding = with(LocalDensity.current) { 24.dp.roundToPx() }
    val labelBg = MaterialTheme.colorScheme.surfaceContainerLowest.toArgb()
    val labelText = MaterialTheme.colorScheme.onSurface.toArgb()
    val labelRing = MaterialTheme.colorScheme.outline.toArgb()
    val latest by rememberUpdatedState(RadarMapModel(rows, center, radiusKm))
    val latestOnSelect by rememberUpdatedState(onSelect)
    val holder = remember { RadarMapHolder() }
    val mapView = remember { createRadarMapView(context) }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            holder.style = null
            holder.map = null
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView, dark) {
        holder.labels = MapLabelBitmaps(context, background = labelBg, text = labelText, outline = labelRing)
        mapView.getMapAsync { map ->
            holder.map = map
            holder.style = null
            if (!holder.clickListenerAdded) {
                holder.clickListenerAdded = true
                map.addOnMapClickListener { point ->
                    val screen = map.projection.toScreenLocation(point)
                    val key = map.queryRenderedFeatures(screen, RadarMapStyle.LAYER_PLANES, RadarMapStyle.LAYER_LABELS)
                        .firstNotNullOfOrNull { it.getStringProperty(RadarMapStyle.PROP_KEY) }
                    latestOnSelect(key)
                    key != null
                }
            }
            val builder = Style.Builder().fromJson(RadarMapStyle.styleJson(MapPalette.of(dark)))
            ContextCompat.getDrawable(context, R.drawable.ic_map_plane)?.let { builder.withImage(RadarMapStyle.PLANE_IMAGE, it) }
            map.setStyle(builder) { style ->
                if (holder.map !== map) return@setStyle
                holder.style = style
                holder.fittedCenter = null
                render(holder, latest, padding)
            }
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        update = { render(holder, RadarMapModel(rows, center, radiusKm), padding) },
    )
}

private data class RadarMapModel(val rows: List<RadarAircraftUiModel>, val center: GeoPoint?, val radiusKm: Int)

private class RadarMapHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
    var labels: MapLabelBitmaps? = null
    var fittedCenter: GeoPoint? = null
    var clickListenerAdded = false
}

private fun createRadarMapView(context: Context): MapView {
    MapLibre.getInstance(context.applicationContext)
    val options = MapLibreMapOptions.createFromAttributes(context)
        .textureMode(true)
        .attributionEnabled(false) // Natural Earth land is public domain; there are no tiles to credit
        .logoEnabled(false)
        .compassEnabled(false)
        .rotateGesturesEnabled(false)
        .tiltGesturesEnabled(false)
    return MapView(context, options).also { it.onCreate(null) }
}

private fun render(holder: RadarMapHolder, model: RadarMapModel, padding: Int) {
    val style = holder.style ?: return
    if (!style.isFullyLoaded) return
    holder.labels?.addMissing(style, model.rows.associate { RadarMapStyle.labelImageId(it.label) to it.label })
    style.getSourceAs<GeoJsonSource>(RadarMapStyle.SOURCE_PLANES)?.setGeoJson(RadarMapStyle.planesGeoJson(model.rows))
    style.getSourceAs<GeoJsonSource>(RadarMapStyle.SOURCE_CENTER)?.setGeoJson(RadarMapStyle.centerGeoJson(model.center))
    style.getSourceAs<GeoJsonSource>(RadarMapStyle.SOURCE_RANGE)?.setGeoJson(RadarMapStyle.rangeGeoJson(model.center, model.radiusKm))
    val center = model.center ?: return
    if (holder.fittedCenter == center) return
    val map = holder.map ?: return
    val (south, west, north, east) = RadarMapStyle.bounds(center, model.radiusKm.coerceAtLeast(1)).toList()
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(north, east, south, west), padding))
    holder.fittedCenter = center
}
